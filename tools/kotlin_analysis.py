#!/usr/bin/env python3
"""Проверки Kotlin-кода, которые не умеет `tools/static_audit.py`.

`static_audit.py` работает на регулярных выражениях: он ловит импорты, `R.*`,
Room-запросы, манифест и строки. Здесь — разбор настоящим парсером (tree-sitter),
поэтому видны две вещи, которые иначе проверяются только компилятором:

1. **Синтаксис.** Каждый `.kt` разбирается в дерево; любая `ERROR`-нода означает
   сломанный файл (незакрытая скобка, обрыв строки, испорченный `when`).
2. **Вызов `suspend`-функции вне suspend-контекста.** Самая частая ошибка сборки
   в этом проекте: «suspension functions can be called only within a coroutine
   body». Проверка нашла реальный баг в `PhoneGuardVpnService` (suspend
   `handlePacket` вызывался из обычной функции).

Зависимости необязательные (в отличие от `static_audit.py`, которому нужен только
Python 3):

    python3 -m venv .venv-analysis && . .venv-analysis/bin/activate
    pip install tree-sitter tree-sitter-kotlin
    python3 tools/kotlin_analysis.py

Без них скрипт печатает подсказку и завершается с кодом 0 (проверка пропущена),
поэтому его можно без опаски вызывать в CI рядом с `static_audit.py`.

Ограничения:
- это не компилятор: типы не проверяются;
- имена `suspend`-функций собираются по всему проекту, поэтому одноимённая
  не-suspend функция даёт ложное срабатывание. Такие случаи перечислены в
  `tools/kotlin_analysis_allow.txt` (по одному `путь: имя` в строке, `#` — комментарий).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC_ROOT = ROOT / "app/src"
ALLOWLIST = Path(__file__).resolve().parent / "kotlin_analysis_allow.txt"

# Точки входа, внутри которых вызов suspend-функции легален.
SUSPEND_BUILDERS = {
    "launch", "withContext", "async", "runBlocking", "runTest", "runBlockingTest",
    "withTimeout", "withTimeoutOrNull", "coroutineScope", "supervisorScope", "flow", "flowOf",
    "channelFlow", "callbackFlow", "suspendCoroutine", "produceState", "LaunchedEffect",
    "DisposableEffect", "rememberCoroutineScope", "awaitAll", "coEvery", "coVerify", "coMatch",
    "coAnswers", "onEach", "collect", "collectLatest", "catch", "retry", "retryWhen", "onStart",
    "onCompletion", "transform", "transformLatest", "flatMapLatest", "flatMapMerge", "mapLatest",
    "debounce", "sample", "buffer", "conflate", "shareIn", "stateIn", "scan", "runningFold",
    "runningReduce", "withIndex", "chunked", "windowed", "awaitClose",
}

# Inline-функции stdlib: внутри их lambda вызов suspend-функции разрешён,
# потому что тело lambda встраивается в вызывающую функцию.
INLINE_OK = {
    "let", "run", "also", "apply", "with", "use", "useLines", "repeat", "runCatching",
    "takeIf", "takeUnless", "require", "requireNotNull", "check", "checkNotNull", "map",
    "mapNotNull", "mapIndexed", "mapIndexedNotNull", "filter", "filterNot", "filterIsInstance",
    "forEach", "forEachIndexed", "flatMap", "flatMapIndexed", "any", "all", "none", "count",
    "first", "firstOrNull", "last", "lastOrNull", "find", "findLast", "single", "singleOrNull",
    "groupBy", "associate", "associateBy", "associateWith", "sortedBy", "sortedWith",
    "sortedByDescending", "minByOrNull", "maxByOrNull", "sumOf", "partition", "fold", "reduce",
    "zip", "takeWhile", "dropWhile", "indexOfFirst", "maxOf", "minOf", "getOrElse", "getOrNull",
    "ifEmpty", "ifBlank", "onSuccess", "onFailure", "measureTimeMillis", "measureNanoTime",
    "synchronized", "lazy", "sequence", "listOf", "setOf", "mapOf", "arrayOf", "buildString",
    "buildList", "buildMap", "buildSet", "groupingBy", "sumBy", "filterIndexed", "mapValues",
    "mapKeys", "orEmpty", "alsoIfNull",
}


def load_allowlist() -> set[tuple[str, str]]:
    if not ALLOWLIST.exists():
        return set()
    allowed: set[tuple[str, str]] = set()
    for raw in ALLOWLIST.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line or ":" not in line:
            continue
        path, symbol = line.rsplit(":", 1)
        allowed.add((path.strip(), symbol.strip()))
    return allowed


def main() -> int:
    try:
        import tree_sitter_kotlin as tsk
        from tree_sitter import Language, Parser
    except ImportError:
        print("kotlin_analysis: пропущено — нет tree-sitter "
              "(pip install tree-sitter tree-sitter-kotlin)")
        return 0

    parser = Parser(Language(tsk.language()))
    files = sorted(SRC_ROOT.rglob("*.kt"))
    sources = {f: f.read_bytes() for f in files}
    trees = {f: parser.parse(sources[f]) for f in files}

    errors: list[str] = []
    warnings: list[str] = []

    def text(f: Path, node) -> str:
        return sources[f][node.start_byte:node.end_byte].decode("utf-8", "replace")

    def line_of(f: Path, node) -> int:
        return sources[f][:node.start_byte].count(b"\n") + 1

    # --- 1. Синтаксис -------------------------------------------------------
    for f, tree in trees.items():
        stack = [tree.root_node]
        reported = 0
        while stack and reported < 3:
            node = stack.pop()
            if node.type == "ERROR" or node.is_missing:
                # Известный дефект грамматики: пустой литерал коллекции `[]`
                # (он легален в аргументах аннотаций, например `autoMigrations = []`).
                around = sources[f][max(0, node.start_byte - 4):node.end_byte + 4]
                if node.start_byte == node.end_byte and re.search(rb"\[\s*\]", around):
                    continue
                errors.append(f"{f.relative_to(ROOT)}:{line_of(f, node)}: "
                              f"синтаксическая ошибка рядом с «{text(f, node)[:40]}»")
                reported += 1
                continue
            stack.extend(node.children)

    # --- 2. suspend-функции -------------------------------------------------
    suspend_names = set()
    for f in files:
        for m in re.finditer(
            r"\bsuspend\s+fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(\w+)\s*\(", sources[f].decode("utf-8")
        ):
            suspend_names.add(m.group(1))

    def callee(f: Path, node, depth: int = 0):
        fn = node.child_by_field_name("function")
        if fn is None and node.children:
            fn = node.children[0]
        if fn is None:
            return None
        # `withContext(x) { }` парсится как вызов с вложенным вызовом первым ребёнком.
        if fn.type == "call_expression" and depth < 5:
            return callee(f, fn, depth + 1)
        if fn.type == "identifier":
            return text(f, fn)
        if fn.type == "navigation_expression":
            return text(f, fn.children[-1]).strip().lstrip(".")
        return None

    def lambda_owner(f: Path, lam):
        cur = lam.parent
        while cur is not None:
            if cur.type == "call_expression":
                return callee(f, cur)
            if cur.type in ("function_declaration", "property_declaration", "lambda_literal"):
                return None
            cur = cur.parent
        return None

    allowed = load_allowlist()
    for f, tree in trees.items():
        rel = str(f.relative_to(ROOT))
        stack = [tree.root_node]
        while stack:
            node = stack.pop()
            if node.type == "call_expression" and callee(f, node) in suspend_names:
                name = callee(f, node)
                cur = node.parent
                pending_property = False
                verdict = None
                while cur is not None:
                    ty = cur.type
                    if ty == "lambda_literal":
                        owner = lambda_owner(f, cur)
                        if owner in SUSPEND_BUILDERS or owner in INLINE_OK:
                            verdict = None
                        else:
                            verdict = f"в lambda к «{owner}»"
                        break
                    if ty == "function_declaration":
                        suspend_ctx = "suspend" in text(f, cur).split("fun")[0]
                        verdict = None if suspend_ctx else (
                            "в инициализаторе свойства" if pending_property else "в обычной функции"
                        )
                        break
                    if ty in ("property_declaration", "init_block"):
                        pending_property = True
                    cur = cur.parent
                if verdict and (rel, name) not in allowed:
                    warnings.append(f"{rel}:{line_of(f, node)}: {name}() — suspend-вызов "
                                    f"{verdict}")
            stack.extend(node.children)

    for e in errors:
        print(f"error: {e}")
    for w in warnings:
        print(f"warning: {w}")
    total = len(errors) + len(warnings)
    print(f"\n{len(errors)} error(s), {len(warnings)} warning(s) "
          f"({len(files)} файлов Kotlin)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
