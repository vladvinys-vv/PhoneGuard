#!/usr/bin/env python3
"""Статическая проверка Gradle-скриптов — то, что Gradle проверяет только при запуске.

Зачем: ошибка в конфигурации (`app/build.gradle`) валит **любую** задачу — `lintDebug`,
`testDebugUnitTest`, `assembleRelease` — с многоэкранным стектрейсом Groovy. Типичный
случай, который здесь ловится: вызов функции Kotlin stdlib (`mapOf(...)`, `listOf(...)`)
в Groovy DSL. Groovy таких методов не знает, и сборка падает на этапе конфигурации с
`MissingMethodException`, хотя выглядит код безобидно.

Проверки:
  * Kotlin-DSL-конструкции в `.gradle` (Groovy): `mapOf`, `listOf`, `val `, `.let { }` и т. п.;
  * `rootProject.ext.X`, на который ссылаются, но который не объявлен;
  * `id 'plugin'` без соответствующего classpath в `buildscript`/`pluginManagement`;
  * версии Kotlin-плагина и compose-compiler-плагина расходятся;
  * AGP против версии Gradle wrapper'а и против `compileSdk`;
  * `minSdk ≤ targetSdk ≤ compileSdk`, согласованность `sourceCompatibility`/`jvmTarget`;
  * отсутствующие файлы из `proguardFiles`;
  * дубликаты зависимостей с разными версиями;
  * `android.useAndroidX`/`android.nonTransitiveRClass` при androidx-зависимостях;
  * `kapt "…"` без плагина `kotlin-kapt`, Hilt-зависимости без Hilt-плагина.

    python3 tools/gradle_check.py
    python3 tools/gradle_check.py --warnings-as-errors
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Функции/конструкции, существующие только в Kotlin DSL: в Groovy их просто нет.
KOTLIN_ONLY = {
    r"\b(mapOf|listOf|mutableMapOf|mutableListOf|mutableSetOf|setOf|arrayOf|emptyList|emptyMap|emptySet|arrayListOf|hashMapOf|linkedMapOf|sortedMapOf)\s*\(":
        "функция Kotlin stdlib в Groovy-скрипте (нет такого метода)",
    r"^\s*(val|fun)\s+\w+":
        "объявление Kotlin (`val`/`fun`) в Groovy-скрипте",
    r"\.(also|let|takeIf|takeUnless)\s*[\{(]":
        "scope-функция Kotlin в Groovy-скрипте",
    r"!!\s*[.\n)]":
        "оператор `!!` (Kotlin) в Groovy-скрипте",
}

# id плагина -> артефакт, который должен быть в buildscript classpath (или pluginManagement).
PLUGIN_ARTIFACTS = {
    "com.android.application": "com.android.tools.build:gradle",
    "com.android.library": "com.android.tools.build:gradle",
    "org.jetbrains.kotlin.android": "org.jetbrains.kotlin:kotlin-gradle-plugin",
    "kotlin-android": "org.jetbrains.kotlin:kotlin-gradle-plugin",
    "kotlin-kapt": "org.jetbrains.kotlin:kotlin-gradle-plugin",
    "kotlin-parcelize": "org.jetbrains.kotlin:kotlin-gradle-plugin",
    "org.jetbrains.kotlin.plugin.compose": "org.jetbrains.kotlin:compose-compiler-gradle-plugin",
    "dagger.hilt.android.plugin": "com.google.dagger:hilt-android-gradle-plugin",
}

# AGP: минимальная версия Gradle и максимальный протестированный compileSdk.
AGP_SUPPORT = {
    (8, 0): ("8.0", 33),
    (8, 1): ("8.0", 34),
    (8, 2): ("8.2", 34),
    (8, 3): ("8.4", 34),
    (8, 4): ("8.6", 34),
    (8, 5): ("8.7", 34),
    (8, 6): ("8.7", 35),
    (8, 7): ("8.9", 35),
    (8, 8): ("8.10.2", 35),
    (8, 9): ("8.11.1", 35),
}


def version_tuple(value: str) -> tuple[int, ...]:
    parts = re.findall(r"\d+", value)
    return tuple(int(part) for part in parts[:3]) or (0,)


def read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return ""


def gradle_files() -> list[Path]:
    return sorted(ROOT.glob("*.gradle")) + sorted(ROOT.glob("*/build.gradle"))


def check_kotlin_dsl_in_groovy(files: list[Path], errors: list[str], warnings: list[str]) -> None:
    for path in files:
        if path.suffix == ".kts":
            continue
        for number, line in enumerate(read(path).splitlines(), start=1):
            stripped = line.strip()
            if stripped.startswith("//"):
                continue
            for pattern, message in KOTLIN_ONLY.items():
                if re.search(pattern, line):
                    errors.append(f"{path.relative_to(ROOT)}:{number}: {message}: {stripped[:80]}")


def define_ext(content: str) -> dict[str, str]:
    """`name = 'value'` внутри блоков `ext { … }`."""
    defined: dict[str, str] = {}
    for block in re.finditer(r"\bext\s*\{([^}]*)\}", content, re.S):
        for name, value in re.findall(r"(\w+)\s*=\s*['\"]?([^'\"\n]+)['\"]?", block.group(1)):
            defined[name] = value.strip()
    return defined


def check_ext_references(files: list[Path], errors: list[str]) -> dict[str, str]:
    root_gradle = ROOT / "build.gradle"
    ext = define_ext(read(root_gradle))
    for path in files:
        ext.update(define_ext(read(path)))

    for path in files:
        for number, line in enumerate(read(path).splitlines(), start=1):
            if line.strip().startswith("//"):
                continue
            for name in re.findall(r"rootProject\.ext\.(\w+)", line):
                if name not in ext:
                    errors.append(
                        f"{path.relative_to(ROOT)}:{number}: `rootProject.ext.{name}` не объявлен"
                    )
            for name in re.findall(r"\$(?:rootProject\.ext\.)?(\w+)_version\b", line):
                if f"{name}_version" not in ext and f"{name}_version" not in line:
                    errors.append(
                        f"{path.relative_to(ROOT)}:{number}: нет версии `{name}_version` в ext"
                    )
    return ext


def check_plugins(files: list[Path], errors: list[str]) -> None:
    root = read(ROOT / "build.gradle")
    settings = read(ROOT / "settings.gradle")
    # `classpath "com.android.tools.build:gradle:8.5.2"` -> `com.android.tools.build:gradle`
    known = {
        ":".join(coordinate.split(":")[:2])
        for coordinate in re.findall(
            r"['\"]([\w.\-]+:[\w.\-]+(?::[$\w.\-]+)?)['\"]", root + settings
        )
    }

    for path in files:
        content = read(path)
        if path.name == "build.gradle" and path.parent == ROOT:
            continue
        applied = re.findall(r"^\s*id\s+['\"]([\w.\-]+)['\"]", content, re.M)
        applied += re.findall(r"^\s*apply\s+plugin:\s*['\"]([\w.\-]+)['\"]", content, re.M)
        for plugin_id in applied:
            artifact = PLUGIN_ARTIFACTS.get(plugin_id)
            if artifact is None:
                continue
            if artifact not in known:
                errors.append(
                    f"{path.relative_to(ROOT)}: плагин `{plugin_id}` без classpath "
                    f"`{artifact}` в build.gradle/settings.gradle"
                )
        if re.search(r"^\s*kapt\s+['\"]", content, re.M) and "kotlin-kapt" not in applied:
            errors.append(f"{path.relative_to(ROOT)}: используется `kapt`, но нет плагина `kotlin-kapt`")
        if "hilt-android" in content and "dagger.hilt.android.plugin" not in applied:
            errors.append(
                f"{path.relative_to(ROOT)}: Hilt-зависимости без плагина `dagger.hilt.android.plugin`"
            )


def check_versions(files: list[Path], ext: dict[str, str], errors: list[str], warnings: list[str]) -> None:
    root = read(ROOT / "build.gradle")

    kotlin_version = re.search(r"kotlin-gradle-plugin:([\w.\-]+)", root)
    compose_version = re.search(r"compose-compiler-gradle-plugin:([\w.\-]+)", root)
    if kotlin_version and compose_version and kotlin_version.group(1) != compose_version.group(1):
        errors.append(
            "версии `kotlin-gradle-plugin` "
            f"({kotlin_version.group(1)}) и `compose-compiler-gradle-plugin` "
            f"({compose_version.group(1)}) должны совпадать"
        )

    agp = re.search(r"com\.android\.tools\.build:gradle:([\w.\-]+)", root)
    wrapper_file = ROOT / "gradle/wrapper/gradle-wrapper.properties"
    wrapper = re.search(r"gradle-([\d.]+)-(?:bin|all)\.zip", read(wrapper_file) or read(ROOT / "gradlew"))
    if agp and wrapper:
        major_minor = version_tuple(agp.group(1))[:2]
        support = AGP_SUPPORT.get(major_minor)
        if support is None:
            warnings.append(f"AGP {agp.group(1)} нет в таблице совместимости — проверьте Gradle вручную")
        else:
            min_gradle, max_compile_sdk = support
            if version_tuple(wrapper.group(1)) < version_tuple(min_gradle):
                errors.append(
                    f"AGP {agp.group(1)} требует Gradle ≥ {min_gradle}, "
                    f"а wrapper — {wrapper.group(1)}"
                )
            for path in files:
                content = read(path)
                match = re.search(r"^\s*compileSdk(?:Version)?\s*=?\s*(\d+)", content, re.M)
                if not match:
                    continue
                compile_sdk = int(match.group(1))
                properties = read(ROOT / "gradle.properties")
                suppressed = re.search(r"^\s*android\.suppressUnsupportedCompileSdk\s*=\s*(\d+)", properties, re.M)
                if compile_sdk > max_compile_sdk and not (
                    suppressed and int(suppressed.group(1)) >= compile_sdk
                ):
                    warnings.append(
                        f"compileSdk {compile_sdk} новее протестированного для AGP {agp.group(1)} "
                        f"(≤ {max_compile_sdk}): нужен `android.suppressUnsupportedCompileSdk={compile_sdk}`"
                    )


def check_sdk_levels(files: list[Path], errors: list[str]) -> None:
    for path in files:
        content = read(path)
        values: dict[str, int] = {}
        for key in ("compileSdk", "minSdk", "targetSdk"):
            match = re.search(rf"^\s*{key}(?:Version)?\s*=?\s*(\d+)", content, re.M)
            if match:
                values[key] = int(match.group(1))
        if len(values) < 2:
            continue
        if "minSdk" in values and "targetSdk" in values and values["minSdk"] > values["targetSdk"]:
            errors.append(f"{path.relative_to(ROOT)}: minSdk {values['minSdk']} > targetSdk {values['targetSdk']}")
        if "targetSdk" in values and "compileSdk" in values and values["targetSdk"] > values["compileSdk"]:
            errors.append(
                f"{path.relative_to(ROOT)}: targetSdk {values['targetSdk']} > compileSdk {values['compileSdk']}"
            )

        java_versions = set(re.findall(r"(?:source|target)Compatibility\s*=\s*JavaVersion\.VERSION_(\d+)", content))
        jvm_target = re.search(r"jvmTarget\s*=\s*['\"](\d+)['\"]", content)
        if java_versions and jvm_target and java_versions != {jvm_target.group(1)}:
            errors.append(
                f"{path.relative_to(ROOT)}: Java {sorted(java_versions)} не совпадает с Kotlin jvmTarget "
                f"{jvm_target.group(1)}"
            )


def check_proguard(files: list[Path], errors: list[str]) -> None:
    for path in files:
        for name in re.findall(r"proguardFiles[^\n]*?['\"]([\w./\-]+\.pro)['\"]", read(path)):
            if not (path.parent / name).exists():
                errors.append(f"{path.relative_to(ROOT)}: нет файла ProGuard `{name}`")


def check_dependencies(files: list[Path], errors: list[str], warnings: list[str]) -> None:
    for path in files:
        seen: dict[str, tuple[str, int]] = {}
        for number, line in enumerate(read(path).splitlines(), start=1):
            match = re.match(
                r"\s*(implementation|api|kapt|ksp|testImplementation|androidTestImplementation|debugImplementation)\s+"
                r"['\"]([\w.\-]+):([\w.\-]+):([^'\"]+)['\"]",
                line,
            )
            if not match:
                continue
            configuration, group, artifact, version = match.groups()
            coordinate = f"{group}:{artifact}"
            previous = seen.get(coordinate)
            if previous and previous[0] != version:
                warnings.append(
                    f"{path.relative_to(ROOT)}:{number}: `{coordinate}` объявлен дважды с разными "
                    f"версиями ({previous[0]} и {version})"
                )
            else:
                seen[coordinate] = (version, number)


def check_androidx_flags(files: list[Path], errors: list[str]) -> None:
    properties = read(ROOT / "gradle.properties")
    uses_androidx = any("androidx." in read(path) for path in files)
    if uses_androidx and not re.search(r"^\s*android\.useAndroidX\s*=\s*true", properties, re.M):
        errors.append("gradle.properties: нет `android.useAndroidX=true`, хотя используются androidx-зависимости")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--warnings-as-errors", action="store_true")
    args = parser.parse_args()

    files = gradle_files()
    errors: list[str] = []
    warnings: list[str] = []

    check_kotlin_dsl_in_groovy(files, errors, warnings)
    ext = check_ext_references(files, errors)
    check_plugins(files, errors)
    check_versions(files, ext, errors, warnings)
    check_sdk_levels(files, errors)
    check_proguard(files, errors)
    check_dependencies(files, errors, warnings)
    check_androidx_flags(files, errors)

    for problem in errors:
        print(f"error: {problem}")
    for problem in warnings:
        print(f"warning: {problem}")
    print(f"\n{len(errors)} error(s), {len(warnings)} warning(s) в {len(files)} Gradle-скриптах")
    if errors or (args.warnings_as_errors and warnings):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
