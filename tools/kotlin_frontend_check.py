#!/usr/bin/env python3
"""Проверка Kotlin-фронтендом: ловит ошибки, которые видны только компилятору.

Зачем: `tools/static_audit.py` работает на регулярных выражениях, а сборка требует
Android SDK. Этот скрипт запускает настоящий компилятор Kotlin **без** Android SDK,
поэтому он не может разрешить `android.*`, `androidx.*`, `kotlinx.coroutines` и т.п.,
но при этом полностью проверяет синтаксис, объявления, вывод типов в местах, где типы
известны, и правила языка.

Ключевой приём — `-Xrender-internal-diagnostic-names`: компилятор помечает каждую
ошибку внутренним именем (`UNRESOLVED_REFERENCE`, `SYNTAX`, `VOLATILE_ON_VALUE`, …).
Ошибки, которые заведомо вызваны отсутствующими внешними библиотеками, отфильтрованы;
всё остальное — реальные проблемы кода. Именно так в проекте были найдены:
`@Volatile` на `val`, приватный метод, вызываемый из UI, и `return` внутри lambda
`withContext`.

Требования: `kotlinc` 2.1+ и JDK 17 (`JAVA_HOME`). Если `kotlinc` нет — скрипт
печатает подсказку и завершается с кодом 0 (как и `tools/kotlin_analysis.py`).

    export JAVA_HOME=/path/to/jdk-17
    export PATH="$JAVA_HOME/bin:$PATH"
    python3 tools/kotlin_frontend_check.py            # main + test + androidTest
    python3 tools/kotlin_frontend_check.py --scope main

В песочнице без доступа к Maven Central компилятор можно взять из npm:

    npm pack kotlin-compiler@2.1.0 && tar xzf kotlin-compiler-2.1.0.tgz
    export PATH="$PWD/package/bin:$PATH"

## Заглушки kotlinx.coroutines

Без корутин на classpath типы `Flow`, `StateFlow`, `first()`, `stateIn()` не разрешаются,
и настоящие ошибки типов **молча теряются**: именно так в проект попал
`SecurityService.getTrustedContacts()` (`isBlank()` у `String?`) и передача
`SpywareScanResult` туда, где ждали `List<SpywareApp>` — app-модуль не собирался, а
проверка показывала «0 проблем». Поэтому скрипт собирает `tools/stubs/*.kt`
(сигнатуры API корутин без реализации) в jar и подкладывает его в classpath.
С `--no-stubs` можно посмотреть, что видно на «голом» classpath.

С `--android-jar` (или `ANDROID_JAR`/`ANDROID_HOME`) проверка становится строже: ошибки
в сигнатурах `android.*` перестают быть «шумом». Так были найдены `Geocoder.getFromLocation()`
без проверки на null и два обращения к `PackageInfo.applicationInfo` без проверки.
Где взять android.jar без dl.google.com — см. `tools/fetch_android_jar.sh`.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCOPES = {
    "main": [ROOT / "app/src/main/java"],
    "test": [ROOT / "app/src/test", ROOT / "app/src/androidTest"],
}

# Диагностики, которые неизбежны, пока на classpath нет Android SDK / AndroidX /
# корутин / Hilt / mockk. Формат: имя → почему это шум.
EXPECTED: dict[str, str] = {
    "UNRESOLVED_REFERENCE": "нет android/androidx/kotlinx/hilt на classpath",
    "UNRESOLVED_IMPORT": "нет android/androidx/kotlinx/hilt на classpath",
    "UNRESOLVED_REFERENCE_WRONG_RECEIVER": "получатель не разрешён (внешний тип)",
    "CANNOT_INFER_PARAMETER_TYPE": "тип lambda выводится из внешнего API",
    "NEW_INFERENCE_NO_INFORMATION_FOR_PARAMETER": "параметр выводится из внешнего API",
    "TYPE_INFERENCE_ONLY_INPUT_TYPES_ERROR": "вывод типа зависит от внешнего API",
    "ILLEGAL_SUSPEND_FUNCTION_CALL": "suspend-контекст задаёт внешний launch/withContext",
    "NON_LOCAL_SUSPENSION_POINT": "inline-lambda внешней библиотеки",
    "NOTHING_TO_OVERRIDE": "переопределение метода неразрешённого базового класса",
    "ARGUMENT_TYPE_MISMATCH": "аргументы неразрешённых типов",
    "TYPE_MISMATCH": "следствие неразрешённых типов",
    "EQUALITY_NOT_APPLICABLE": "сравнение с константой неразрешённого класса",
    "OPERATOR_MODIFIER_REQUIRED": "сравнение со значением неразрешённого типа",
    "NOT_AN_ANNOTATION_CLASS": "аннотация из внешней библиотеки не разрешена",
    "ANNOTATION_ARGUMENT_MUST_BE_CONST": "KClass внешней аннотации не разрешён",
    "NONE_APPLICABLE": "нет подходящего кандидата среди внешних перегрузок",
    "OVERLOAD_RESOLUTION_AMBIGUITY": "перегрузки внешнего типа не разрешены",
    "COMPONENT_FUNCTION_AMBIGUITY": "деструктуризация значения неразрешённого типа",
    "DELEGATE_SPECIAL_FUNCTION_NONE_APPLICABLE": "`by lazy` для неразрешённого типа",
    "NULL_FOR_NONNULL_TYPE": "кандидат выбран из неразрешённого типа (Sequence.ifEmpty)",
    "WRONG_NUMBER_OF_TYPE_ARGUMENTS": "неразрешённый Result/Data из WorkManager",
    "NO_VALUE_FOR_PARAMETER": "неразрешённый конструктор WorkManager",
    "DEPRECATION_ERROR": "deprecated-перегрузка выбрана из stdlib вместо внешней",
    "INVISIBLE_REFERENCE": "проверяется как обычная ошибка — см. ниже",
    "NAMED_PARAMETER_NOT_FOUND": "параметры неразрешённого вызова",
    "UNSAFE_CALL": "получатель неразрешённого типа",
    "RETURN_TYPE_MISMATCH": "тип возврата зависит от внешнего API",
    "NO_ELSE_IN_WHEN": "subject `when` имеет неразрешённый тип",
    "FUNCTION_CALL_EXPECTED": "`it.first` разрешился в Iterable.first() из-за неразрешённого Pair",
    "INCOMPATIBLE_ENUM_COMPARISON_ERROR": "orEmpty() выбрал CharSequence-перегрузку для неразрешённого типа",
    "VOLATILE_ON_VALUE": "проверяется как обычная ошибка",
    "RETURN_NOT_ALLOWED": "проверяется как обычная ошибка",
    "SYNTAX": "проверяется как обычная ошибка",
}

# Эти диагностики остаются настоящими ошибками даже при пустом classpath.
ALWAYS_REAL = {
    "INVISIBLE_REFERENCE",
    "VOLATILE_ON_VALUE",
    "RETURN_NOT_ALLOWED",
    "SYNTAX",
    "REDECLARATION",
    "CONFLICTING_OVERLOADS",
    "CYCLIC_INHERITANCE_HIERARCHY",
    "SUPERCLASS_NOT_INITIALIZED",
    "ABSTRACT_MEMBER_NOT_IMPLEMENTED",
    "ACCIDENTAL_OVERRIDE",
    "UNSUPPORTED_FEATURE",
    "INVALID_CHARACTERS",
    "EXPECTING_CONDITION",
    "UNEXPECTED_TOKENS",
    "MISSING_RETURN_STATEMENT",
    "VARIABLE_EXPECTED",
    "EXPRESSION_EXPECTED",
    "UNRESOLVED_LABEL",
    "ILLEGAL_ANNOTATION",
}

DIAG_RE = re.compile(r"^(?P<path>.+?\.kt):(?P<line>\d+):(?P<col>\d+): error: \[(?P<name>[A-Z_]+)\] (?P<msg>.*)$")

# С Android SDK на classpath часть «шумных» диагностик перестаёт быть шумом: если в
# сообщении тип `android.*` уже разрешён, значит проблема не во внешней библиотеке.
# Так были найдены два обращения к `PackageInfo.applicationInfo` без проверки на null.
ANDROID_TYPED_REAL = [
    # UNSAFE_CALL на полностью разрешённом android-типе — настоящий NPE-риск.
    (re.compile(r"^UNSAFE_CALL$"), re.compile(r"nullable receiver of type 'android\.")),
]


# Диагностики, которые зависят от разрешённости типов. Пока `Flow` не разрешался,
# любые несовпадения списывались на «внешние» типы, и реальные ошибки типов терялись.
TYPED_DIAGS = {
    "TYPE_MISMATCH",
    "ARGUMENT_TYPE_MISMATCH",
    "RETURN_TYPE_MISMATCH",
    "UNSAFE_CALL",
    "NULL_FOR_NONNULL_TYPE",
}
# Упоминание типа из недоступной библиотеки — значит, вывод типа шёл через неё.
EXTERNAL_TYPE = re.compile(
    r"android\.|androidx\.|com\.google|dagger|kotlinx\.|okhttp3|org\.json|retrofit|realm|"
    r"app\.celzero|net\.sqlcipher|org\.bouncycastle"
)
# Заглушки и неразрешённые generic-вызовы дают сообщения вида «'R', but 'K'», «'kotlin.Any'».
TYPE_VARIABLE_NOISE = re.compile(
    r"kotlin\.Any|kotlin\.Nothing|kotlin\.Unit|MatchGroup|Function\d|"
    r"'[A-Z]\??'|<[A-Z](,|>)|ERROR CLASS"
)
# Тип проекта в сообщении — значит, вывод типов дошёл до наших классов и ошибка настоящая.
PROJECT_TYPE = re.compile(r"com\.phoneguard\.[\w.]+")


def stub_typed_real(name: str, message: str) -> bool:
    """Настоящая ошибка типов: в сообщении есть наш тип, а не «шумовой» внешний.

    «Шумовые» сообщения появляются, когда получатель выражения не разрешён
    (DataStore, Room, Compose): тогда компилятор примеряет stdlib-перегрузки и
    сообщает про `Char`/`Any`/`R` вместо реального типа.
    """
    if name not in TYPED_DIAGS:
        return False
    if EXTERNAL_TYPE.search(message) or TYPE_VARIABLE_NOISE.search(message):
        return False
    if PROJECT_TYPE.search(message):
        return True
    # `isBlank()` у `Flow<String?>.first()`: в сообщении только kotlin-тип.
    return name == "UNSAFE_CALL" and "nullable receiver of type 'kotlin." in message


def find_kotlinc() -> str | None:
    explicit = os.environ.get("KOTLINC")
    if explicit and Path(explicit).exists():
        return explicit
    return shutil.which("kotlinc")



STUBS_DIR = ROOT / "tools/stubs"


def build_stubs_jar(kotlinc: str) -> str | None:
    """Компилирует `tools/stubs/*.kt` в jar (кеш по хешу исходников)."""
    sources = sorted(STUBS_DIR.glob("*.kt"))
    if not sources:
        return None
    digest = hashlib.sha256()
    for path in sources:
        digest.update(path.name.encode())
        digest.update(path.read_bytes())
    cache = Path(tempfile.gettempdir()) / f"phoneguard-stubs-{digest.hexdigest()[:16]}.jar"
    if cache.is_file():
        return str(cache)

    with tempfile.TemporaryDirectory() as out:
        cmd = [kotlinc, "-nowarn", "-jvm-target", "17", "-d", out, *[str(p) for p in sources]]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            print("kotlin_frontend_check: не удалось собрать заглушки корутин")
            print((proc.stdout or "") + (proc.stderr or ""))
            return None
        with zipfile.ZipFile(cache, "w", zipfile.ZIP_DEFLATED) as jar:
            for file in Path(out).rglob("*"):
                if file.is_file():
                    jar.write(file, file.relative_to(out).as_posix())
    return str(cache)


def collect_sources(scope: str) -> list[str]:
    roots = SCOPES["main"] if scope == "main" else SCOPES["main"] + SCOPES["test"]
    return [str(p) for root in roots for p in sorted(root.rglob("*.kt"))]


def find_android_jar(explicit: str | None) -> str | None:
    """android.jar из установленного SDK: явный путь → ANDROID_JAR → ANDROID_HOME/ANDROID_SDK_ROOT."""
    if explicit:
        return explicit if Path(explicit).is_file() else None
    env_jar = os.environ.get("ANDROID_JAR")
    if env_jar and Path(env_jar).is_file():
        return env_jar
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(var)
        if not root:
            continue
        platforms = Path(root) / "platforms"
        if not platforms.is_dir():
            continue
        # самая новая платформа, у которой есть android.jar
        for candidate in sorted(platforms.glob("android-*/android.jar"), reverse=True):
            if candidate.is_file():
                return str(candidate)
    return None


# Частые классы JDK. Нужны, чтобы ловить «используется без импорта» даже когда сам импорт
# удалён из проекта (тогда вывести имя из импортов невозможно). Список не претендует на
# полноту: он покрывает то, что реально встречается в Android-коде.
COMMON_JDK_CLASSES = {
    "ArrayList", "Arrays", "Base64", "BigDecimal", "BigInteger", "BufferedReader", "BufferedWriter",
    "ByteArrayInputStream", "ByteArrayOutputStream", "Calendar", "Callable", "Charset",
    "Collectors", "Collections", "Comparator", "ConcurrentHashMap", "CopyOnWriteArrayList",
    "CountDownLatch", "Date", "DateFormat", "DateTimeFormatter", "Duration", "EnumMap", "EnumSet",
    "ExecutorService", "Executors", "File", "FileInputStream", "FileNotFoundException",
    "FileOutputStream", "Files", "HashMap", "HashSet", "IOException", "InetAddress",
    "InputStreamReader", "Instant", "LinkedHashMap", "LinkedHashSet", "Locale", "LocalDate",
    "LocalDateTime", "Matcher", "MessageDigest", "Objects", "Optional", "OutputStreamWriter",
    "Path", "Paths", "Pattern", "Predicate", "Random", "ReentrantLock", "Runnable", "SecureRandom",
    "Semaphore", "ServerSocket", "SimpleDateFormat", "Socket", "SortedMap", "StandardCharsets",
    "Stream", "Supplier", "System", "Thread", "TimeUnit", "Timer", "TimerTask", "TreeMap",
    "UUID", "ZonedDateTime", "ZoneId",
}


def import_index(sources: list[str]) -> tuple[set[str], dict[str, str]]:
    """(JDK-классы, которые где-то импортируются; класс проекта -> его пакет)."""
    jdk: set[str] = set(COMMON_JDK_CLASSES)
    project: dict[str, str] = {}
    for path in sources:
        try:
            text = Path(path).read_text(encoding="utf-8")
        except OSError:
            continue
        jdk |= set(re.findall(r"^import\s+java[\w.]*\.(\w+)$", text, re.M))
        package = re.search(r"^package\s+([\w.]+)", text, re.M)
        for name in re.findall(
            r"^(?:internal |private |public |abstract |open |sealed |data |enum )*"
            r"(?:class|object|interface) (\w+)", text, re.M
        ):
            project[name] = package.group(1) if package else ""
    return jdk, project


def missing_import(path: str, line: str, message: str, jdk: set[str], project: dict[str, str]) -> str | None:
    """Ловит настоящую ошибку компиляции: JDK/свой класс используется, но не импортирован.

    `UNRESOLVED_REFERENCE` обычно списывают на отсутствующие android/androidx-зависимости,
    и так пропускается реальная поломка вроде `List<File>` без `import java.io.File`.
    """
    match = re.search(r"unresolved reference '(\w+)'", message, re.IGNORECASE)
    if match is None:
        return None
    symbol = match.group(1)
    try:
        text = Path(path).read_text(encoding="utf-8")
    except OSError:
        return None
    if re.search(rf"^import\s+[\w.]*\.{re.escape(symbol)}$", text, re.M):
        return None
    if re.search(rf"^import\s+[\w.]+\.\*$", text, re.M) and symbol not in jdk:
        return None  # звёздный импорт внешней библиотеки — не наша ошибка
    package = re.search(r"^package\s+([\w.]+)", text, re.M)
    if symbol in jdk:
        return f"JDK-класс `{symbol}` используется без импорта (строка {line}) — сборка упадёт"
    if symbol in project and project[symbol] != (package.group(1) if package else ""):
        return (f"класс проекта `{symbol}` из пакета {project[symbol]} без импорта "
                f"(строка {line}) — сборка упадёт")
    return None


# Проверка самой классификации: сюда попадают настоящие сообщения компилятора,
# которые уже встречались в проекте (реальные ошибки и «шум» от неразрешённых типов).
SELF_TEST_CASES: list[tuple[str, str, bool]] = [
    (
        "UNSAFE_CALL",
        "Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable "
        "receiver of type 'kotlin.String?'.",
        True,
    ),
    (
        "ARGUMENT_TYPE_MISMATCH",
        "Argument type mismatch: actual type is 'com.phoneguard.model.SpywareScanResult', "
        "but 'kotlin.collections.List<com.phoneguard.model.SpywareApp>' was expected.",
        True,
    ),
    (
        "RETURN_TYPE_MISMATCH",
        "Return type mismatch: expected 'com.phoneguard.data.FullScanRepository', "
        "actual 'com.phoneguard.data.ScanHistoryEntity'.",
        True,
    ),
    (
        "ARGUMENT_TYPE_MISMATCH",
        "Argument type mismatch: actual type is 'kotlin.Any', but 'kotlin.Int' was expected.",
        False,
    ),
    (
        "ARGUMENT_TYPE_MISMATCH",
        "Argument type mismatch: actual type is 'kotlin.Int', but 'kotlin.Char' was expected.",
        False,
    ),
    (
        "ARGUMENT_TYPE_MISMATCH",
        "Argument type mismatch: actual type is 'R', but 'K' was expected.",
        False,
    ),
    (
        "NULL_FOR_NONNULL_TYPE",
        "Null cannot be a value of a non-null type 'kotlin.sequences.Sequence<T>'.",
        False,
    ),
    (
        "TYPE_MISMATCH",
        "Type mismatch: inferred type is 'ERROR CLASS: Symbol not found for CancellationException', "
        "but 'kotlin.Throwable' was expected.",
        False,
    ),
    (
        "TYPE_MISMATCH",
        "Type mismatch: inferred type is 'android.os.Bundle?', but 'kotlin.String' was expected.",
        False,
    ),
    (
        "UNSAFE_CALL",
        "Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable "
        "receiver of type 'android.content.Context?'.",
        False,
    ),
    ("UNRESOLVED_REFERENCE", "Unresolved reference 'X'.", False),
]


def self_test() -> int:
    """Проверяет классификацию диагностик на сообщениях реального компилятора."""
    failures = []
    for name, message, expected in SELF_TEST_CASES:
        actual = stub_typed_real(name, message)
        if actual != expected:
            failures.append((name, message, expected, actual))
    if failures:
        for name, message, expected, actual in failures:
            print(f"kotlin_frontend_check --self-test: [{name}] ожидалось {expected}, получено {actual}")
            print(f"  {message}")
        return 1
    print(f"kotlin_frontend_check --self-test: ok ({len(SELF_TEST_CASES)} сообщений, "
          "реальные ошибки типов не путаются с шумом неразрешённых зависимостей)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=["main", "all"], default="all")
    parser.add_argument(
        "--android-jar",
        default=None,
        help="android.jar из Android SDK: снимает тысячи ложных «неразрешённых ссылок» "
             "и проверяет реальные сигнатуры android.* API (по умолчанию берётся "
             "из $ANDROID_JAR / $ANDROID_HOME/platforms)",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="проверить классификацию диагностик на сохранённых сообщениях компилятора",
    )
    parser.add_argument(
        "--no-stubs",
        action="store_true",
        help="не подкладывать заглушки kotlinx.coroutines (видно только то, что разрешается без них)",
    )
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    kotlinc = find_kotlinc()
    if kotlinc is None:
        print("kotlin_frontend_check: пропущено — нет kotlinc "
              "(нужен Kotlin 2.1+ и JDK 17, см. docstring файла)")
        return 0

    sources = collect_sources(args.scope)
    if not sources:
        print("kotlin_frontend_check: не найдено исходников")
        return 0

    android_jar = find_android_jar(args.android_jar)
    stubs_jar = None if args.no_stubs else build_stubs_jar(kotlinc)
    classpath = ":".join(p for p in (android_jar, stubs_jar) if p)
    with tempfile.TemporaryDirectory() as out:
        cmd = [
            kotlinc,
            "-Xrender-internal-diagnostic-names",
            "-jvm-target", "17",
            "-nowarn",
        ]
        if classpath:
            cmd += ["-classpath", classpath]
        cmd += ["-d", out, *sources]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        log = (proc.stdout or "") + (proc.stderr or "")

    jdk_symbols, project_classes = import_index(sources)
    real: list[str] = []
    noise = 0
    for line in log.splitlines():
        m = DIAG_RE.match(line.strip())
        if not m:
            continue
        name = m.group("name")
        rel = str(Path(m.group("path")).relative_to(ROOT)) if m.group("path").startswith(str(ROOT)) else m.group("path")
        entry = f"{rel}:{m.group('line')}: [{name}] {m.group('msg')}"
        if name in ("UNRESOLVED_REFERENCE", "UNRESOLVED_REFERENCE_WRONG_RECEIVER"):
            hint = missing_import(m.group("path"), m.group("line"), m.group("msg"), jdk_symbols, project_classes)
            if hint:
                real.append(f"{entry} -> {hint}")
                continue
        if stub_typed_real(name, m.group("msg")):
            real.append(entry)
        elif name in ALWAYS_REAL or name not in EXPECTED:
            real.append(entry)
        elif android_jar and any(
            name_re.match(name) and msg_re.search(m.group("msg"))
            for name_re, msg_re in ANDROID_TYPED_REAL
        ):
            real.append(entry)
        else:
            noise += 1

    for entry in real:
        print(f"error: {entry}")
    classpath_note = (
        f"android.jar: {android_jar}" if android_jar
        else "нет Android SDK на classpath"
    )
    if stubs_jar:
        classpath_note += ", заглушки корутин подключены"
    print(f"\n{len(real)} problem(s), {noise} ожидаемых диагностик "
          f"({classpath_note}), {len(sources)} файлов")
    if not real:
        print("kotlin_frontend_check: ошибок, не связанных с отсутствующими зависимостями, нет")
    return 1 if real else 0


if __name__ == "__main__":
    sys.exit(main())
