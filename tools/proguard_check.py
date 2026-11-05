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

# Доля прикрытых классов приложения, выше которой сжатие фактически отключено.
KEEP_RATIO_WARNING = 0.75


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def keep_targets(text: str) -> list[tuple[int, str]]:
    """`-keep`-директивы вида `-keep [@аннотация] class <цель>` → (строка, цель)."""
    targets: list[tuple[int, str]] = []
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped.startswith("-keep"):
            continue
        match = re.search(r"\bclass\s+([\w.$*]+)", stripped)
        if not match:
            continue
        target = match.group(1)
        if target in {"*", "**"}:
            continue
        targets.append((number, target))
    return targets


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

    return errors, warnings


def self_test() -> int:
    """Проверка самой проверки: фикстура с каждой ошибкой и чистый проект."""
    saved_errors: list[str] = []

    def check(name: str, condition: bool) -> None:
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
            "-keep class com.phoneguard.a.** { *; }\n"
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
        check("прикрытая рефлексия не считается ошибкой", not errors)

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
        check("платформенная рефлексия не требует keep", not errors)

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

        # Существующий, но пустой файл правил не должен падать.
        rules.write_text("# только комментарий\n", encoding="utf-8")
        errors, _ = analyze(rules, gradle, sources)
        check("пустой файл правил без ошибок", not errors)

    if saved_errors:
        for problem in saved_errors:
            print(f"error: proguard_check --self-test: {problem}")
        return 1
    print(f"proguard_check --self-test: ok ({11 - len(saved_errors)} проверок)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="проверить саму проверку")
    parser.add_argument("--verbose", action="store_true", help="перечислить правила и цели")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    errors, warnings = analyze(RULES, BUILD_GRADLE, MAIN_SOURCES)
    if args.verbose:
        for number, target in keep_targets(read(RULES)):
            print(f"  {RULES.name}:{number}: {target}")
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
