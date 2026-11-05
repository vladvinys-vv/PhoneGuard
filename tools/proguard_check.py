#!/usr/bin/env python3
"""Проверка `proguard-rules.pro` против реального кода и зависимостей.

Зачем: правила R8 проверяются **только** релизной сборкой, а её в песочнице нет. Ошибки
в правилах делятся на два дорогих класса:

1. **Правило для того, чего нет.** `-keep class com.phoneguard.privacyscanner.**` — такого
   пакета в проекте не существует (есть `ui.screens.privacyscanner`, но не верхнеуровневый).
   Правило бесполезно и маскирует настоящую дыру: кажется, что «приватность прикрыта»,
   хотя прикрыт несуществующий пакет.
2. **Нет правила для того, что есть.** `Class.forName("com.phoneguard.…")` и рефлексивные
   сериализаторы (Gson) в релизе падают на переименованных R8 классах — в debug всё
   работает, поэтому ошибка доходит до пользователя.

Дополнительно скрипт считает, какую долю собственных классов прикрывают `-keep`:
`-keep class com.phoneguard.<пакет>.**` для всех пакетов подряд отключает сжатие и
обфускацию, то есть делает всю релизную оптимизацию бессмысленной.

    python3 tools/proguard_check.py
    python3 tools/proguard_check.py --self-test
    python3 tools/proguard_check.py --verbose     # список правил и их целей
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RULES = ROOT / "app/proguard-rules.pro"
BUILD_GRADLE = ROOT / "app/build.gradle"
MAIN_SOURCES = ROOT / "app/src/main/java"

# Библиотеки, для которых в проекте есть правила: префикс класса → подстрока зависимости.
# Правило для отсутствующей зависимости — мусор, который никто не удалит годами.
LIBRARY_PREFIXES = {
    "com.google.gson": "gson",
    "com.google.mlkit": "mlkit",
    "net.zetetic.database.sqlcipher": "sqlcipher",
    "net.sqlcipher": "sqlcipher",
    "net.dongliu.apk.parser": "apk-parser",
    "net.dongliu.commons": "apk-parser",
    "com.google.i18n.phonenumbers": "libphonenumber",
    "androidx.camera": "camera-",
    "androidx.biometric": "biometric",
    "androidx.security.crypto": "security-crypto",
    "androidx.work": "work-runtime",
    "androidx.navigation.compose": "navigation-compose",
    "androidx.room": "room-",
    "com.google.android.gms": "play-services",
    "okhttp3": "okhttp",
    "okio": "okio",
}

# Платформенные классы (`android.jar`): правила для них не требуют зависимости в Gradle.
PLATFORM_PREFIXES = ("org.json.", "org.w3c.", "org.xmlpull.", "javax.crypto.")

# Рефлексивная сериализация: без `Signature` R8 съедает generic-параметры, и Gson падает
# на `List<T>` уже в рантайме.
SIGNATURE_USERS = ("Gson", "fromJson", "toJson")

# Доля собственных классов под `-keep`: выше предупреждаем, выше второго порога — ошибка.
# До батча 48 под `-keep` было 183 класса из 252 (73 %): обфускация не работала вообще.
KEEP_RATIO_WARNING = 0.10
KEEP_RATIO_ERROR = 0.25


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


# Виды директив, которые вообще встречаются в проекте. Разница принципиальна:
# `-keep` запрещает и удаление, и переименование, `-keepnames` — только переименование,
# `-keepclassmembers` относится к членам класса, а не к самому классу.
FULL_KEEP_KINDS = ("keep", "keepclasseswithmembers")
ALL_KINDS = ("keep", "keepnames", "keepclassmembers", "keepclasseswithmembers")


def keep_directives(text: str) -> list[tuple[int, str, str]]:
    """`-keep*`-директивы вида `-keep [@аннотация] class <цель>` → (строка, вид, цель)."""
    found: list[tuple[int, str, str]] = []
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped.startswith("-keep"):
            continue
        match = re.match(r"-(keep\w*)", stripped)
        kind = match.group(1) if match else "keep"
        if kind not in ALL_KINDS:
            continue
        class_match = re.search(r"\bclass\s+([\w.$*]+)", stripped)
        if not class_match:
            continue
        target = class_match.group(1)
        if target in {"*", "**"}:
            continue
        found.append((number, kind, target))
    return found


def keep_targets(text: str) -> list[tuple[int, str]]:
    """Только `-keep`-директивы на классы: именно они отключают сжатие и обфускацию."""
    return [
        (number, target)
        for number, kind, target in keep_directives(text)
        if kind in FULL_KEEP_KINDS
    ]


def project_packages(sources: Path) -> set[str]:
    """Пакеты проекта, в которых есть хотя бы один `.kt`/`.java`-файл."""
    packages: set[str] = set()
    for path in sources.rglob("*"):
        if path.suffix not in {".kt", ".java"} or not path.is_file():
            continue
        relative = path.relative_to(sources)
        packages.add(".".join(relative.parts[:-1]))
    return packages


def project_classes(sources: Path) -> set[str]:
    classes: set[str] = set()
    for path in sources.rglob("*"):
        if path.suffix not in {".kt", ".java"} or not path.is_file():
            continue
        relative = path.relative_to(sources)
        package = ".".join(relative.parts[:-1])
        classes.add(f"{package}.{path.stem}" if package else path.stem)
    return classes


def rule_covers(target: str, fqcn: str) -> bool:
    """Покрывает ли правило с целью [target] класс [fqcn]."""
    if target.endswith(".**"):
        base = target[:-3]
        return fqcn == base or fqcn.startswith(base + ".")
    if target.endswith(".*"):
        base = target[:-2]
        rest = fqcn[len(base) + 1:]
        return fqcn.startswith(base + ".") and "." not in rest
    if target.endswith("*"):
        return fqcn.startswith(target[:-1])
    return fqcn == target


def package_exists(target: str, packages: set[str], classes: set[str]) -> bool:
    """Существует ли в проекте пакет (или класс), на который ссылается правило."""
    if target.endswith(".**"):
        base = target[:-3]
        return base in packages or any(p.startswith(base + ".") for p in packages)
    if target.endswith(".*"):
        base = target[:-2]
        return any(p == base for p in packages)
    return target in classes or any(c.startswith(target + "$") for c in classes)


def reflection_targets(sources: Path) -> list[tuple[Path, int, str]]:
    """Строковые аргументы `Class.forName("…")` — единственная рефлексия по своим классам."""
    found: list[tuple[Path, int, str]] = []
    pattern = re.compile(r'Class\.forName\(\s*"([\w.$]+)"')
    for path in sources.rglob("*"):
        if path.suffix not in {".kt", ".java"} or not path.is_file():
            continue
        for number, line in enumerate(read(path).splitlines(), start=1):
            for match in pattern.finditer(line):
                found.append((path, number, match.group(1)))
    return found


def uses_serializer(sources: Path) -> list[Path]:
    users: list[Path] = []
    for path in sources.rglob("*"):
        if path.suffix not in {".kt", ".java"} or not path.is_file():
            continue
        text = read(path)
        if any(marker in text for marker in SIGNATURE_USERS):
            users.append(path)
    return users


def kept_ratio(rules: list[tuple[int, str]], classes: set[str]) -> tuple[int, int]:
    """Сколько собственных классов прикрыто `-keep`-правилами."""
    own = sorted(c for c in classes if c.startswith("com.phoneguard."))
    if not own:
        return 0, 0
    covered = sum(1 for fqcn in own if any(rule_covers(t, fqcn) for _, t in rules))
    return covered, len(own)


def persists_enum_names(sources: Path) -> bool:
    """Пишет ли код `enum.name` в базу или в файл настроек (то есть строкой, а не номером)."""
    markers = ("enumValueOf<", "@TypeConverter", "isTransferable(key.name)")
    for path in sources.rglob("*"):
        if path.suffix not in {".kt", ".java"} or not path.is_file():
            continue
        text = read(path)
        if any(marker in text for marker in markers):
            return True
    return False


def keeps_enum_names(rules_text: str) -> bool:
    """Есть ли правило, сохраняющее имена полей enum (то есть имена констант)."""
    in_block = False
    for line in rules_text.splitlines():
        stripped = line.strip()
        if stripped.startswith("-keepclassmembers") and re.search(r"\benum\b", stripped):
            in_block = True
            continue
        if not in_block:
            continue
        if "<fields>" in stripped or stripped.startswith("*;"):
            return True
        if stripped == "}" or stripped.startswith("-"):
            in_block = False
    return False


def parse_mapping(text: str) -> dict[str, tuple[str, list[tuple[str, str]]]]:
    """`mapping.txt` R8 → {исходный класс: (переименованный, [(член, новый член)])}."""
    classes: dict[str, tuple[str, list[tuple[str, str]]]] = {}
    current: str | None = None
    for line in text.splitlines():
        if not line.strip():
            continue
        if not line.startswith(" ") and line.rstrip().endswith(":"):
            match = re.match(r"(.+?) -> (.+?):$", line.strip())
            if match:
                current = match.group(1)
                classes[current] = (match.group(2), [])
            continue
        if current is not None and line.startswith("    "):
            match = re.match(r"(.+?) -> (.+)$", line.strip())
            if match:
                classes[current][1].append((match.group(1), match.group(2)))
    return classes


def enum_constants(fqcn: str, members: list[tuple[str, str]]) -> list[tuple[str, str]]:
    """Константы enum в `mapping.txt`: строки вида `<класс> KONSTANTE -> KONSTANTE`."""
    simple = fqcn.split(".")[-1]
    constants: list[tuple[str, str]] = []
    for original, renamed in members:
        if "(" in original:
            continue
        parts = original.rsplit(" ", 1)
        if len(parts) != 2:
            continue
        type_part, name = parts
        if type_part.split(".")[-1] != simple:
            continue
        if not name or not name.isupper():
            continue
        constants.append((name, renamed.split("(")[0]))
    return constants


def analyze_mapping(
    mapping_text: str,
    rules_text: str,
    sources: Path,
) -> tuple[list[str], list[str]]:
    """Проверка настоящего `mapping.txt` из релизной сборки.

    Что она ловит: правила R8 проверяются только сборкой, а в песочнице её нет. Когда
    `mapping.txt` наконец есть (артефакт Codemagic), по нему видно две вещи, которые
    иначе проверяются вручную и никогда:

    1. **Обфускация действительно произошла.** Если собственные классы в `mapping.txt`
       отображаются сами в себя — `-keep` снова всё прикрыл, и релиз отличается от debug
       только размером.
    2. **Имена констант enum не тронуты.** Они уходят в SQLite и в файл настроек строкой;
       переименование ломает чтение данных, записанных прошлой версией, — молча.
    """
    errors: list[str] = []
    warnings: list[str] = []
    mapping = parse_mapping(mapping_text)
    own = {name: value for name, value in mapping.items() if name.startswith("com.phoneguard.")}
    top_level = {name: value for name, value in own.items() if "$" not in name}

    if not top_level:
        errors.append(
            "mapping.txt: в файле нет собственных классов `com.phoneguard.*` — это маппинг "
            "другого модуля или пустая сборка"
        )
        return errors, warnings

    renamed = sum(1 for name, (new, _) in top_level.items() if new != name)
    share = renamed * 100 // len(top_level)
    if renamed == 0:
        errors.append(
            f"mapping.txt: ни один из {len(top_level)} собственных классов не переименован — "
            f"обфускация фактически отключена, проверьте `-keep`-правила"
        )
    elif share < 50:
        warnings.append(
            f"mapping.txt: переименовано {renamed} из {len(top_level)} классов ({share}%) — "
            f"меньше половины, слишком много правил `-keep`"
        )

    if persists_enum_names(sources) and not keeps_enum_names(rules_text):
        errors.append(
            "mapping.txt: правила не сохраняют имена констант enum, а код пишет `enum.name` "
            "в базу — после обновления старые строки перестанут читаться"
        )

    unstable: list[str] = []
    for name, (_, members) in own.items():
        for constant, renamed_to in enum_constants(name, members):
            if constant != renamed_to:
                unstable.append(f"{name}.{constant} -> {renamed_to}")
    for example in unstable[:5]:
        errors.append(
            f"mapping.txt: константа enum переименована ({example}) — строки в базе и файле "
            f"настроек, записанные прошлой версией, перестанут читаться"
        )
    if len(unstable) > 5:
        errors.append(f"mapping.txt: переименовано ещё {len(unstable) - 5} констант enum")

    for _, _, fqcn in reflection_targets(sources):
        if not fqcn.startswith("com.phoneguard."):
            continue
        entry = mapping.get(fqcn)
        if entry and entry[0] != fqcn:
            errors.append(
                f"mapping.txt: `{fqcn}` переименован в `{entry[0]}`, но код обращается к нему "
                f"по имени (`Class.forName`) — правило `-keep` потеряно"
            )

    warnings.append(
        f"mapping.txt: переименовано {renamed} из {len(top_level)} собственных классов "
        f"({share}%), констант enum с изменённым именем: {len(unstable)}"
    )
    return errors, warnings


def analyze(
    rules_path: Path,
    build_gradle: Path,
    sources: Path,
) -> tuple[list[str], list[str]]:
    """Возвращает (ошибки, предупреждения) для указанного проекта."""
    errors: list[str] = []
    warnings: list[str] = []
    if not rules_path.is_file():
        errors.append(f"{rules_path.name}: файл правил не найден")
        return errors, warnings

    rules_text = read(rules_path)
    rules = keep_targets(rules_text)
    packages = project_packages(sources)
    classes = project_classes(sources)
    dependencies = read(build_gradle) if build_gradle.is_file() else ""

    # 1. Правило для несуществующего пакета/класса проекта.
    for number, target in rules:
        if not target.startswith("com.phoneguard."):
            continue
        if not package_exists(target, packages, classes):
            errors.append(
                f"{rules_path.name}:{number}: `{target}` — такого пакета/класса в проекте нет; "
                f"правило ничего не прикрывает (ощущение защиты без защиты)"
            )

    # 2. Правило для библиотеки, которой нет в зависимостях. Проверяются только `-keep`:
    #    `-dontwarn` как раз и пишут для классов, которых в сборке нет (транзитивные
    #    optional-зависимости), поэтому требовать там артефакт бессмысленно.
    for number, target in rules:
        if target.startswith(PLATFORM_PREFIXES):
            continue
        for prefix, artifact in LIBRARY_PREFIXES.items():
            if target.startswith(prefix) and artifact not in dependencies:
                errors.append(
                    f"{rules_path.name}:{number}: `{target}` — зависимости `{artifact}` в "
                    f"app/build.gradle нет; правило мусорное"
                )
                break

    # 3. Рефлексия по своим классам без прикрывающего правила.
    for path, number, fqcn in reflection_targets(sources):
        relative = path.relative_to(sources.parent.parent.parent)
        if not fqcn.startswith("com.phoneguard."):
            continue  # платформенные классы (android.os.SystemProperties) R8 не переименовывает
        if not any(rule_covers(target, fqcn) for _, target in rules):
            errors.append(
                f"{relative}:{number}: `Class.forName(\"{fqcn}\")` — класс не прикрыт ни одним "
                f"`-keep`; в release R8 переименует его и рефлексия упадёт"
            )

    # 4. Рефлексивная сериализация требует атрибута Signature.
    if uses_serializer(sources) and "-keepattributes" in rules_text:
        attributes_line = next(
            (line for line in rules_text.splitlines() if line.strip().startswith("-keepattributes")),
            "",
        )
        if "Signature" not in attributes_line and "*Annotation*" not in attributes_line:
            errors.append(
                f"{rules_path.name}: в `-keepattributes` нет `Signature`, а код использует "
                f"рефлексивную сериализацию — R8 съест generic-параметры"
            )

    # 5. Сколько собственных классов прикрыто (сжатие почти не работает?).
    covered, total = kept_ratio(rules, classes)
    if total and covered / total > KEEP_RATIO_WARNING:
        warnings.append(
            f"{rules_path.name}: `-keep` прикрывает {covered} из {total} собственных классов "
            f"({covered * 100 // total}%) — сжатие и обфускация для них не работают"
        )
    if total and covered / total > KEEP_RATIO_ERROR:
        errors.append(
            f"{rules_path.name}: `-keep` прикрывает {covered} из {total} собственных классов "
            f"({covered * 100 // total}%) — при таком охвате релизная сборка не сжимает и не "
            f"переименовывает код; оставьте правила только для того, к чему обращаются по имени"
        )

    # 6. Имена констант enum уходят в базу и в файл настроек строкой.
    if persists_enum_names(sources) and not keeps_enum_names(rules_text):
        errors.append(
            f"{rules_path.name}: код пишет `enum.name` в базу/файл настроек, а правила не "
            f"сохраняют имена констант (`-keepclassmembers enum * {{ <fields>; }}`) — R8 "
            f"переименует константы, и строки прошлой версии перестанут читаться"
        )

    return errors, warnings


def self_test() -> int:
    """Проверка самой проверки: фикстура с каждой ошибкой и чистый проект."""
    saved_errors: list[str] = []
    total = 0

    def check(name: str, condition: bool) -> None:
        nonlocal total
        total += 1
        if not condition:
            saved_errors.append(name)

    with tempfile.TemporaryDirectory() as raw:
        root = Path(raw)
        sources = root / "app/src/main/java"
        (sources / "com/phoneguard/a").mkdir(parents=True)
        (sources / "com/phoneguard/b").mkdir(parents=True)
        (sources / "com/phoneguard/a/A.kt").write_text(
            "package com.phoneguard.a\n\nclass A\n", encoding="utf-8"
        )
        (sources / "com/phoneguard/b/B.kt").write_text(
            "package com.phoneguard.b\n\nclass B\n", encoding="utf-8"
        )
        gradle = root / "app/build.gradle"
        gradle.write_text("dependencies {\n    implementation 'androidx.room:room-runtime:2.7.2'\n}\n",
                          encoding="utf-8")
        rules = root / "app/proguard-rules.pro"

        # Чистые правила: существующий пакет, реальная зависимость, ничего лишнего.
        rules.write_text(
            "-keep class androidx.room.RoomDatabase\n"
            "-keepattributes Signature,*Annotation*\n",
            encoding="utf-8",
        )
        errors, warnings = analyze(rules, gradle, sources)
        check("чистые правила без ошибок", not errors)
        check("нет предупреждения о доле", not warnings)

        # Правило для несуществующего пакета — ошибка.
        rules.write_text("-keep class com.phoneguard.ghost.** { *; }\n", encoding="utf-8")
        errors, _ = analyze(rules, gradle, sources)
        check("мёртвое правило пакета найдено",
              any("такого пакета/класса в проекте нет" in e for e in errors))

        # Правило для библиотеки без зависимости — ошибка; с зависимостью — нет.
        rules.write_text("-keep class com.google.gson.** { *; }\n", encoding="utf-8")
        errors, _ = analyze(rules, gradle, sources)
        check("правило для отсутствующей зависимости найдено",
              any("зависимости `gson`" in e for e in errors))
        rules.write_text("-keep class androidx.room.RoomDatabase\n"
                         "-keep class com.google.gson.** { *; }\n", encoding="utf-8")
        gradle_with_gson = root / "app/build.gradle.gson"
        gradle_with_gson.write_text(
            "dependencies {\n"
            "    implementation 'androidx.room:room-runtime:2.7.2'\n"
            "    implementation 'com.google.code.gson:gson:2.11.0'\n"
            "}\n",
            encoding="utf-8",
        )
        errors, _ = analyze(rules, gradle_with_gson, sources)
        check("правило для существующей зависимости не считается ошибкой", not errors)

        # Рефлексия по своему классу без keep — ошибка, с keep — нет.
        (sources / "com/phoneguard/a/A.kt").write_text(
            "package com.phoneguard.a\n\n"
            "class A {\n"
            "    fun load() = Class.forName(\"com.phoneguard.b.B\")\n"
            "}\n",
            encoding="utf-8",
        )
        rules.write_text("-keep class com.phoneguard.a.** { *; }\n", encoding="utf-8")
        errors, _ = analyze(rules, gradle, sources)
        check("неприкрытая рефлексия найдена",
              any("Class.forName" in e for e in errors))
        rules.write_text("-keep class com.phoneguard.a.** { *; }\n"
                         "-keep class com.phoneguard.b.** { *; }\n", encoding="utf-8")
        errors, _ = analyze(rules, gradle, sources)
        check("прикрытая рефлексия не считается ошибкой",
              not any("Class.forName" in e for e in errors))

        # Платформенный Class.forName не требует правил.
        (sources / "com/phoneguard/a/A.kt").write_text(
            "package com.phoneguard.a\n\n"
            "class A {\n"
            "    fun load() = Class.forName(\"android.os.SystemProperties\")\n"
            "}\n",
            encoding="utf-8",
        )
        rules.write_text("-keep class com.phoneguard.a.** { *; }\n", encoding="utf-8")
        errors, _ = analyze(rules, gradle, sources)
        check("платформенная рефлексия не требует keep",
              not any("Class.forName" in e for e in errors))

        # Сериализатор без Signature — ошибка.
        (sources / "com/phoneguard/a/A.kt").write_text(
            "package com.phoneguard.a\n\n"
            "class A {\n"
            "    fun save() = Gson().toJson(1)\n"
            "}\n",
            encoding="utf-8",
        )
        rules.write_text("-keep class com.phoneguard.a.** { *; }\n"
                         "-keepattributes InnerClasses\n", encoding="utf-8")
        errors, _ = analyze(rules, gradle, sources)
        check("отсутствие Signature при сериализации найдено",
              any("Signature" in e for e in errors))

        # Широкий keep почти на весь код — предупреждение.
        rules.write_text(
            "-keep class com.phoneguard.a.** { *; }\n"
            "-keep class com.phoneguard.b.** { *; }\n"
            "-keepattributes Signature\n",
            encoding="utf-8",
        )
        _, warnings = analyze(rules, gradle, sources)
        check("широкий keep даёт предупреждение о сжатии",
              any("сжатие и обфускация" in w for w in warnings))

        # Широкий keep своих пакетов — ошибка, а не только предупреждение.
        rules.write_text(
            "-keep class com.phoneguard.a.** { *; }\n"
            "-keep class com.phoneguard.b.** { *; }\n"
            "-keepattributes Signature\n",
            encoding="utf-8",
        )
        errors, _ = analyze(rules, gradle, sources)
        check("широкий keep своих классов — ошибка",
              any("релизная сборка не сжимает" in e for e in errors))

        # Enum-имена уходят в базу строкой: без правила — ошибка, с правилом — нет.
        (sources / "com/phoneguard/a/A.kt").write_text(
            "package com.phoneguard.a\n\n"
            "class A {\n"
            "    fun read(value: String) = enumValueOf<E>(value)\n"
            "}\n"
            "enum class E { UNKNOWN, OTHER }\n",
            encoding="utf-8",
        )
        rules.write_text(
            "-keep class androidx.room.RoomDatabase\n"
            "-keepattributes Signature\n",
            encoding="utf-8",
        )
        errors, _ = analyze(rules, gradle, sources)
        check("отсутствие правила для имён enum найдено",
              any("enum.name" in e for e in errors))

        enum_rules = (
            "-keep class androidx.room.RoomDatabase\n"
            "-keepattributes Signature\n"
            "-keepclassmembers enum * {\n"
            "    public static **[] values();\n"
            "    public static ** valueOf(java.lang.String);\n"
            "    <fields>;\n"
            "}\n"
        )
        rules.write_text(enum_rules, encoding="utf-8")
        errors, _ = analyze(rules, gradle, sources)
        check("правило для имён enum снимает ошибку", not errors)

        # mapping.txt: константа enum переименована — ошибка.
        mapping = root / "mapping.txt"
        mapping.write_text(
            "com.phoneguard.a.A -> a.a:\n"
            "com.phoneguard.a.E -> a.b:\n"
            "    com.phoneguard.a.E UNKNOWN -> UNKNOWN\n"
            "    com.phoneguard.a.E OTHER -> a\n",
            encoding="utf-8",
        )
        m_errors, _ = analyze_mapping(mapping.read_text(), enum_rules, sources)
        check("mapping: переименование константы enum найдено",
              any("константа enum переименована" in e for e in m_errors))

        # mapping.txt: ничего не переименовано — обфускация отключена.
        mapping.write_text(
            "com.phoneguard.a.A -> com.phoneguard.a.A:\n"
            "com.phoneguard.a.E -> com.phoneguard.a.E:\n"
            "    com.phoneguard.a.E UNKNOWN -> UNKNOWN\n",
            encoding="utf-8",
        )
        m_errors, _ = analyze_mapping(mapping.read_text(), enum_rules, sources)
        check("mapping: отключённая обфускация найдена",
              any("обфускация фактически отключена" in e for e in m_errors))

        # mapping.txt: нормальная сборка — без ошибок, но с метрикой.
        mapping.write_text(
            "com.phoneguard.a.A -> a.a:\n"
            "com.phoneguard.a.E -> a.c:\n"
            "    com.phoneguard.a.E UNKNOWN -> UNKNOWN\n"
            "    com.phoneguard.a.E OTHER -> OTHER\n",
            encoding="utf-8",
        )
        m_errors, m_warnings = analyze_mapping(mapping.read_text(), enum_rules, sources)
        check("mapping: корректная обфускация без ошибок", not m_errors)
        check("mapping: метрика переименования печатается",
              any("переименовано 2 из 2" in w for w in m_warnings))

        # Существующий, но пустой файл правил не должен падать.
        # Ошибка про enum-имена тут ожидаема (в фикстуре есть `enumValueOf`), важно, что
        # разбор не падает и остальные проверки не выдумывают проблем.
        rules.write_text("# только комментарий\n", encoding="utf-8")
        errors, _ = analyze(rules, gradle, sources)
        check("пустой файл правил разбирается без падения",
              all("enum.name" in e for e in errors))

    if saved_errors:
        for problem in saved_errors:
            print(f"error: proguard_check --self-test: {problem}")
        return 1
    print(f"proguard_check --self-test: ok ({total - len(saved_errors)} из {total} проверок)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="проверить саму проверку")
    parser.add_argument("--verbose", action="store_true", help="перечислить правила и цели")
    parser.add_argument(
        "--mapping",
        metavar="FILE",
        help="проверить настоящий mapping.txt из релизной сборки (артефакт Codemagic)",
    )
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if args.mapping:
        mapping_path = Path(args.mapping)
        if not mapping_path.is_file():
            print(f"error: {mapping_path}: файл не найден")
            print("mapping.txt лежит в артефактах сборки: app/build/outputs/mapping/**")
            return 1
        m_errors, m_warnings = analyze_mapping(
            read(mapping_path), read(RULES), MAIN_SOURCES
        )
        for warning in m_warnings:
            print(f"info: {warning}")
        for error in m_errors:
            print(f"error: {error}")
        if m_errors:
            print(f"\nproguard_check --mapping: {len(m_errors)} проблем в релизной обфускации")
            return 1
        print("proguard_check --mapping: обфускация выполнена, имена enum стабильны")
        return 0

    errors, warnings = analyze(RULES, BUILD_GRADLE, MAIN_SOURCES)
    if args.verbose:
        for number, kind, target in keep_directives(read(RULES)):
            print(f"  {RULES.name}:{number}: -{kind} {target}")
    for warning in warnings:
        print(f"warning: {warning}")
    for error in errors:
        print(f"error: {error}")
    if errors:
        print(f"\nproguard_check: {len(errors)} ошибок в правилах R8")
        return 1
    covered, total = kept_ratio(keep_targets(read(RULES)), project_classes(MAIN_SOURCES))
    print(
        f"proguard_check: правила согласованы с кодом "
        f"({len(keep_targets(read(RULES)))} правил, прикрыто {covered} из {total} классов)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
