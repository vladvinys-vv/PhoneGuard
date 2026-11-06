#!/usr/bin/env python3
"""Проверяет граф зависимостей Hilt статически — то, что компилятор Kotlin пропускает.

Зачем: разрыв в графе Hilt не является ошибкой компиляции. Приложение собирается, `./gradlew
assemble` зелёный, а падает это на **генерации кода** (dependencies of … cannot be provided) или,
хуже того, в рантайме при первом создании экземпляра. Так уже было дважды: `IntrusionRepository`
требовал `IntrusionDetector`, `IntrusionResponseOrchestrator`, `HoneypotManager` и интерфейс
`IntrusionResponseExecutor` без единого биндинга, а `ShoulderSurferUseCase` — интерфейс
`ShoulderSurferController`.

Проверяется то, что можно установить без генерации кода:

1. **Каждый параметр `@Inject constructor`** (и `@Inject lateinit var`) проектного
   интерфейса/абстрактного класса должен иметь биндинг — `@Provides`, возвращающий этот тип, или
   `@Binds`, возвращающий его же. Параметры с квалификатором (`@Named`, `@ApplicationContext` …)
   пропускаются: там биндинг может отличаться.
2. **Тип, привязанный дважды** (`@Binds` + `@Provides`, или два `@Provides` на один тип) — Hilt
   падает с «bound multiple times».
3. **`@Binds` обязан привязывать реализацию к интерфейсу**: параметр — существующий проектный
   класс, возвращаемый тип — проектный интерфейс/абстрактный класс. Привязка класса к классу —
   почти всегда опечатка.
4. **`@Module` без `@InstallIn`** не устанавливается ни в один компонент, и его биндинги просто
   не существуют.

    python3 tools/hilt_check.py
    python3 tools/hilt_check.py --verbose
    python3 tools/hilt_check.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE_ROOT = ROOT / "app/src/main/java"

# Заглушки и обёртки, которые не являются типом зависимости.
WRAPPERS = {"Lazy", "Provider", "Optional", "Set", "List", "Map", "MutableList", "Collection"}
KEYWORDS = {"val", "var", "private", "internal", "public", "override", "lateinit", "constructor"}

TYPE_DECL = re.compile(
    r"^(?P<mods>(?:(?:public|internal|private|open|abstract|sealed|data|inner|value|annotation|"
    r"enum|fun)\s+)*)"
    r"(?P<kind>class|interface|object)\s+(?P<name>\w+)",
    re.M,
)
INJECT_CONSTRUCTOR = re.compile(r"@Inject\s+constructor\s*\(")
INJECT_FIELD = re.compile(r"@Inject\s+(?:lateinit\s+)?(?:var|val)\s+(\w+)\s*:\s*([\w.]+)")
PARAM = re.compile(r"(?:@\w+(?:\([^)]*\))?\s+)*(?:private |internal |public )?"
                   r"(?:val|var)?\s*\w+\s*:\s*([\w.<>?, ]+)")


@dataclass
class Types:
    """Что объявлено в проекте: имя → вид (`interface`, `abstract`, `class`, `object`)."""

    kinds: dict[str, str] = field(default_factory=dict)
    injectable: set[str] = field(default_factory=set)
    duplicates: set[str] = field(default_factory=set)

    def add(self, name: str, kind: str) -> None:
        if name in self.kinds and self.kinds[name] != kind:
            # `class Foo` и `object Foo` в разных файлах — это уже повод разобраться.
            self.duplicates.add(name)
        self.kinds[name] = kind

    def is_abstraction(self, name: str) -> bool:
        return self.kinds.get(name) in {"interface", "abstract"}


@dataclass
class Bindings:
    """Биндинги по имени возвращаемого типа: чем и где привязан."""

    provided: dict[str, list[str]] = field(default_factory=dict)   # @Provides / DAO-фабрики
    bound: dict[str, list[str]] = field(default_factory=dict)      # @Binds

    def all_for(self, name: str) -> list[str]:
        return self.provided.get(name, []) + self.bound.get(name, [])


def strip_comments(text: str) -> str:
    """Комментарии не компилируются: `@Binds`, упомянутый в KDoc, — это текст, а не биндинг.

    Ровно на этом инструмент и соврал в первый раз: документация `IntrusionModule` объясняет,
    что раньше у интерфейса не было `@Binds`, и проверка приняла слово из комментария за
    настоящую привязку.
    """
    without_block = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(
        line for line in without_block.splitlines() if not line.lstrip().startswith("//")
    )


def strip_wrappers(type_name: str) -> str:
    """`Lazy<VaultDao>` → `VaultDao`, `List<Rule>` → `Rule`; квалификаторы уже срезаны."""
    simple = type_name.split("<", 1)[0].split(".")[-1].strip()
    if simple in WRAPPERS and "<" in type_name:
        inner = type_name[type_name.find("<") + 1 : type_name.rfind(">")]
        return strip_wrappers(inner)
    return simple


def split_params(block: str) -> list[str]:
    """Параметры конструктора с учётом вложенных скобок дженериков."""
    parts: list[str] = []
    depth = 0
    current = ""
    for char in block:
        if char in "(<[":
            depth += 1
        elif char in ")>]":
            depth -= 1
        if char == "," and depth == 0:
            parts.append(current)
            current = ""
            continue
        current += char
    if current.strip():
        parts.append(current)
    return [part.strip() for part in parts if part.strip()]


def balanced_span(text: str, start: int) -> tuple[int, int]:
    """Границы содержимого скобок `(...)` начиная с `start`: (начало, конец без `)`).

    Возвращаются обе границы, потому что по одной нельзя найти тип возвращаемого значения:
    после `fun name(` идёт список параметров, и наивный разбор принимал первый параметр за
    возвращаемый тип (`@Provides` «привязывал» `context`).
    """
    opening = text.find("(", start)
    if opening < 0:
        return (-1, -1)
    depth = 0
    for index in range(opening, len(text)):
        if text[index] == "(":
            depth += 1
        elif text[index] == ")":
            depth -= 1
            if depth == 0:
                return (opening + 1, index)
    return (opening + 1, len(text))


def balanced_block(text: str, start: int) -> str:
    """Содержимое скобок `(...)` начиная с `start`."""
    begin, end = balanced_span(text, start)
    return text[begin:end] if begin >= 0 else ""


def collect_types(sources: dict[Path, str]) -> Types:
    types = Types()
    for text in (strip_comments(text) for text in sources.values()):
        for match in TYPE_DECL.finditer(text):
            mods = match.group("mods").split()
            kind = match.group("kind")
            if kind == "class" and "abstract" in mods:
                kind = "abstract"
            types.add(match.group("name"), kind)
        for match in re.finditer(r"@Inject\s+constructor\s*\(", text):
            # Имя класса — ближайшее объявление перед конструктором.
            prefix = text[: match.start()]
            found = None
            for decl in TYPE_DECL.finditer(prefix):
                found = decl
            if found:
                types.injectable.add(found.group("name"))
        for match in INJECT_FIELD.finditer(text):
            types.injectable.add(strip_wrappers(match.group(2)))
    return types


def collect_bindings(sources: dict[Path, str]) -> Bindings:
    bindings = Bindings()
    for path, raw in sources.items():
        text = strip_comments(raw)
        name = path.name
        for match in re.finditer(r"@Provides\b", text):
            fun = re.search(r"\bfun\s+\w+\s*\(", text[match.end() :])
            if not fun:
                continue
            base = match.end()
            _, end = balanced_span(text, base + fun.start())
            returned = re.match(r"\s*(?::\s*)?([\w.]+)", text[end + 1 :])
            if not returned:
                continue
            bindings.provided.setdefault(strip_wrappers(returned.group(1)), []).append(name)
        for match in re.finditer(r"@Binds\b", text):
            fun = re.search(r"\bfun\s+\w+\s*\(", text[match.end() :])
            if not fun:
                continue
            base = match.end()
            begin, end = balanced_span(text, base + fun.start())
            returned = re.match(r"\s*:\s*([\w.]+)", text[end + 1 :])
            if not returned:
                continue
            param = split_params(text[begin:end])
            impl = strip_wrappers(param[0].split(":", 1)[1]) if param and ":" in param[0] else ""
            bindings.bound.setdefault(strip_wrappers(returned.group(1)), []).append(
                f"{name}:{impl}"
            )
    return bindings


def check(sources: dict[Path, str]) -> list[str]:
    errors: list[str] = []
    sources = {path: strip_comments(text) for path, text in sources.items()}
    types = collect_types(sources)
    bindings = collect_bindings(sources)

    # 1. Параметры @Inject-конструкторов и поля без биндинга.
    for path, text in sources.items():
        for match in INJECT_CONSTRUCTOR.finditer(text):
            block = balanced_block(text, match.start())
            for param in split_params(block):
                if param.lstrip().startswith("@"):
                    continue  # квалификатор: биндинг может быть другим
                type_match = PARAM.match(param)
                if not type_match:
                    continue
                name = strip_wrappers(type_match.group(1))
                if not types.is_abstraction(name):
                    continue
                if bindings.all_for(name):
                    continue
                errors.append(
                    f"{path.name}: `@Inject constructor` требует `{name}`, но биндинга нет — "
                    "Hilt упадёт на генерации кода (нужен `@Binds`/`@Provides`)"
                )
        for match in INJECT_FIELD.finditer(text):
            name = strip_wrappers(match.group(2))
            if not types.is_abstraction(name):
                continue
            if bindings.all_for(name):
                continue
            errors.append(
                f"{path.name}: `@Inject lateinit var {match.group(1)}: {name}` — биндинга нет"
            )

    # 2. Двойной биндинг одного типа.
    for name in sorted(set(bindings.provided) | set(bindings.bound)):
        total = bindings.all_for(name)
        if len(total) > 1:
            errors.append(
                f"тип `{name}` привязан {len(total)} раза ({', '.join(total)}) — "
                "Hilt падает с «bound multiple times»"
            )

    # 3. @Binds: реализация → абстракция.
    for name, entries in sorted(bindings.bound.items()):
        for entry in entries:
            file_name, _, impl = entry.partition(":")
            if not impl:
                continue
            if impl not in types.kinds:
                errors.append(
                    f"{file_name}: `@Binds` привязывает неизвестный класс `{impl}` — "
                    "такого типа в проекте нет"
                )
            elif types.kinds[impl] == "interface":
                errors.append(
                    f"{file_name}: `@Binds` привязывает интерфейс `{impl}` вместо реализации"
                )
            if not types.is_abstraction(name):
                errors.append(
                    f"{file_name}: `@Binds` возвращает `{name}`, а это не интерфейс и не "
                    "абстрактный класс — привязка бессмысленна"
                )

    # 4. @Module без @InstallIn.
    for path, text in sources.items():
        for match in re.finditer(r"@Module\b", text):
            tail = text[match.end() :]
            window = tail[: tail.find("class") + 200] if "class" in tail[:400] else tail[:200]
            if "@InstallIn" not in window and "@InstallIn" not in text[: match.start()][-200:]:
                errors.append(
                    f"{path.name}: `@Module` без `@InstallIn` — модуль не устанавливается ни в "
                    "один компонент, его биндинги не существуют"
                )

    return errors


def read_sources(root: Path) -> dict[Path, str]:
    source_root = root / "app/src/main/java"
    if not source_root.exists():
        return {}
    return {
        path: path.read_text(encoding="utf-8", errors="ignore")
        for path in sorted(source_root.rglob("*.kt"))
    }


def self_test() -> int:
    problems: list[str] = []

    def check_label(label: str, condition: bool) -> None:
        if not condition:
            problems.append(label)

    def files(**sources: str) -> dict[Path, str]:
        return {Path(name.replace("__", "/")): text for name, text in sources.items()}

    good = files(
        Controller__kt=(
            "package x\n\ninterface Controller {\n    fun start(): Boolean\n}\n"
        ),
        Impl__kt=(
            "package x\n\nclass Impl @Inject constructor() : Controller {\n"
            "    override fun start() = true\n}\n"
        ),
        Module__kt=(
            "package x\n\n@Module\n@InstallIn(SingletonComponent::class)\n"
            "abstract class Bindings {\n    @Binds\n    abstract fun bind(impl: Impl): Controller\n}\n"
        ),
        UseCase__kt=(
            "package x\n\nclass UseCase @Inject constructor(\n"
            "    private val controller: Controller\n)\n"
        ),
    )
    check_label("полный граф ошибок не даёт", check(good) == [])

    missing = files(
        Controller__kt="package x\n\ninterface Controller {\n    fun start(): Boolean\n}\n",
        UseCase__kt=(
            "package x\n\nclass UseCase @Inject constructor(\n"
            "    private val controller: Controller\n)\n"
        ),
    )
    errors = check(missing)
    check_label("интерфейс без биндинга найден",
                any("Controller" in error and "биндинга нет" in error for error in errors))

    duplicate = files(
        Controller__kt="package x\n\ninterface Controller {\n    fun start(): Boolean\n}\n",
        Impl__kt="package x\n\nclass Impl @Inject constructor() : Controller {\n"
                 "    override fun start() = true\n}\n",
        Module__kt=(
            "package x\n\n@Module\n@InstallIn(SingletonComponent::class)\n"
            "abstract class Bindings {\n    @Binds\n    abstract fun bind(impl: Impl): Controller\n"
            "    @Provides\n    fun provide(): Controller = Impl()\n}\n"
        ),
    )
    check_label("двойной биндинг найден",
                any("bound multiple times" in error for error in check(duplicate)))

    class_to_class = files(
        Thing__kt="package x\n\nclass Thing @Inject constructor()\n",
        Module__kt=(
            "package x\n\n@Module\n@InstallIn(SingletonComponent::class)\n"
            "abstract class Bindings {\n    @Binds\n    abstract fun bind(impl: Thing): Thing\n}\n"
        ),
    )
    check_label("привязка класса к классу найдена",
                any("не интерфейс" in error for error in check(class_to_class)))

    unknown_impl = files(
        Controller__kt="package x\n\ninterface Controller {\n    fun start(): Boolean\n}\n",
        Module__kt=(
            "package x\n\n@Module\n@InstallIn(SingletonComponent::class)\n"
            "abstract class Bindings {\n    @Binds\n    abstract fun bind(impl: Ghost): Controller\n}\n"
        ),
    )
    check_label("неизвестная реализация найдена",
                any("Ghost" in error for error in check(unknown_impl)))

    no_install = files(
        Module__kt=(
            "package x\n\n@Module\nobject Loose {\n"
            "    @Provides fun provide(): Impl = Impl()\n}\n"
        ),
    )
    check_label("@Module без @InstallIn найден",
                any("InstallIn" in error for error in check(no_install)))

    qualified = files(
        Controller__kt="package x\n\ninterface Controller {\n    fun start(): Boolean\n}\n",
        UseCase__kt=(
            "package x\n\nclass UseCase @Inject constructor(\n"
            "    @Named(\"main\") private val controller: Controller\n)\n"
        ),
    )
    check_label("параметр с квалификатором пропускается", check(qualified) == [])

    lazy_param = files(
        Controller__kt="package x\n\ninterface Controller {\n    fun start(): Boolean\n}\n",
        UseCase__kt=(
            "package x\n\nclass UseCase @Inject constructor(\n"
            "    private val lazyController: Lazy<Controller>\n)\n"
        ),
    )
    check_label("Lazy<интерфейс> разворачивается",
                any("Controller" in error for error in check(lazy_param)))

    if problems:
        for problem in problems:
            print(f"error: hilt_check --self-test: {problem}")
        return 1
    print(
        "hilt_check --self-test: ok (биндинг обязателен, двойной биндинг, привязка класса к "
        "классу, неизвестная реализация, @Module без @InstallIn, квалификаторы, Lazy)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", action="store_true", help="показать собранные типы и биндинги")
    parser.add_argument("--self-test", action="store_true", help="проверить правила на синтетике")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    sources = read_sources(ROOT)
    if not sources:
        print(f"error: нет исходников в {SOURCE_ROOT}")
        return 1

    errors = check(sources)
    types = collect_types(sources)
    bindings = collect_bindings(sources)
    if args.verbose:
        abstractions = sorted(name for name in types.kinds if types.is_abstraction(name))
        for name in abstractions:
            bound = bindings.all_for(name)
            print(f"{name}: {'привязан — ' + ', '.join(bound) if bound else 'биндинга нет'}")
    for problem in errors:
        print(f"error: {problem}")
    print(
        f"\n{len(errors)} error(s), 0 warning(s): {len(types.kinds)} типов, "
        f"{len(types.injectable)} инъецируемых, "
        f"{len(bindings.provided)} @Provides и {len(bindings.bound)} @Binds"
    )
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
