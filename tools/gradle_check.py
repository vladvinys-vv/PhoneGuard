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


DEPENDENCY_RE = re.compile(
    r"\s*(implementation|api|kapt|ksp|testImplementation|androidTestImplementation|debugImplementation)"
    r"\s+(?:platform\()?['\"]([\w.\-]+):([\w.\-]+):([^'\"]+)['\"]"
)

# Артефакт-рантайм -> артефакт-процессор: версии обязаны совпадать, иначе kapt падает.
COMPILER_PAIRS = {
    "com.google.dagger:hilt-android": "com.google.dagger:hilt-android-compiler",
    "androidx.room:room-runtime": "androidx.room:room-compiler",
    "androidx.hilt:hilt-work": "androidx.hilt:hilt-compiler",
}

# Известные риски зависимостей: старые артефакты, которые всё ещё собираются, но блокируют релиз.
KNOWN_RISKS = {
    "net.zetetic:android-database-sqlcipher": (
        "артефакт закрыт (последняя версия 4.5.4, 2023) и не поддерживает 16 КБ страницы памяти — "
        "нужен `net.zetetic:sqlcipher-android` (там `APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true`), "
        "иначе Play не примет обновление приложения с targetSdk 35+ (см. docs/RELEASE.md)"
    ),
}


def resolve_version(raw: str, ext: dict[str, str]) -> str:
    """`$rootProject.ext.hilt_version` -> `2.55`."""
    match = re.search(r"ext\.(\w+)", raw)
    if match:
        return ext.get(match.group(1), raw)
    return raw.strip("'\"")


def collect_dependencies(files: list[Path], ext: dict[str, str]) -> dict[str, list[tuple[str, int, str]]]:
    """{group:artifact: [(конфигурация, строка, версия)]} по всем Gradle-скриптам."""
    found: dict[str, list[tuple[str, int, str]]] = {}
    for path in files:
        for number, line in enumerate(read(path).splitlines(), start=1):
            match = DEPENDENCY_RE.match(line)
            if not match:
                continue
            configuration, group, artifact, raw_version = match.groups()
            version = resolve_version(raw_version, ext)
            found.setdefault(f"{group}:{artifact}", []).append((configuration, number, version))
    return found


def check_dependencies(files: list[Path], ext: dict[str, str], errors: list[str], warnings: list[str]) -> None:
    dependencies = collect_dependencies(files, ext)

    for coordinate, entries in dependencies.items():
        versions = {version for _, _, version in entries}
        if len(versions) > 1:
            warnings.append(
                f"`{coordinate}` объявлен с разными версиями: {', '.join(sorted(versions))} "
                f"(строки: {', '.join(str(number) for _, number, _ in entries)})"
            )

    for runtime, compiler in COMPILER_PAIRS.items():
        if runtime not in dependencies and compiler not in dependencies:
            continue
        runtime_versions = {version for _, _, version in dependencies.get(runtime, [])}
        compiler_versions = {version for _, _, version in dependencies.get(compiler, [])}
        if runtime_versions and compiler_versions and runtime_versions != compiler_versions:
            errors.append(
                f"версии `{runtime}` ({', '.join(sorted(runtime_versions))}) и `{compiler}` "
                f"({', '.join(sorted(compiler_versions))}) должны совпадать"
            )

    for coordinate, why in KNOWN_RISKS.items():
        if coordinate in dependencies:
            warnings.append(f"`{coordinate}`: {why}")
    for coordinate, entries in dependencies.items():
        if coordinate.startswith(("androidx.security:security-crypto", "androidx.biometric:biometric")):
            for _, number, version in entries:
                if "alpha" in version or "beta" in version:
                    warnings.append(
                        f"{coordinate}:{number}: предрелизная версия {version} — проверьте, нет ли стабильной"
                    )

    if "net.zetetic:sqlcipher-android" in dependencies and "androidx.sqlite:sqlite" not in dependencies:
        warnings.append(
            "`net.zetetic:sqlcipher-android` подключён без `androidx.sqlite:sqlite` — библиотека "
            "объявляет её как compile-зависимость, но не тянет транзитивно (см. её README)"
        )

    compose_bom = [
        version
        for _, _, version in dependencies.get("androidx.compose:compose-bom", [])
    ]
    if compose_bom:
        for coordinate, entries in dependencies.items():
            if not coordinate.startswith("androidx.compose") or coordinate == "androidx.compose:compose-bom":
                continue
            for _, number, version in entries:
                warnings.append(
                    f"{coordinate}:{number}: явная версия {version} при подключённом compose-bom "
                    f"{compose_bom[0]} — BOM перестаёт управлять этим артефактом"
                )


PROGUARD_DIRECTIVES = {
    "-keep", "-keepclassmembers", "-keepnames", "-keepclassmembernames",
    "-keepclasseswithmembers", "-keepclasseswithmembernames",
    "-dontwarn", "-dontnote", "-dontoptimize", "-dontobfuscate", "-dontshrink",
    "-dontpreverify", "-dontcompress", "-optimizationpasses", "-optimizations",
    "-allowaccessmodification", "-keepattributes", "-include", "-injars", "-outjars",
    "-libraryjars", "-assumenosideeffects", "-assumenoexternalsideeffects",
    "-assumevalues", "-whyareyoukeeping", "-verbose", "-repackageclasses",
    "-flattenpackagehierarchy", "-adaptclassstrings", "-adaptresourcefilecontents",
    "-adaptresourcefilenames", "-printmapping", "-printseeds", "-printusage",
    "-applymapping", "-if", "-neverinline", "-alwaysinline", "-identifiernamestring",
    "-keepdirectories", "-keeppackagenames", "-keepparameternames", "-renamesourcefileattribute",
    "-dontusemixedcaseclassnames", "-dontskipnonpubliclibraryclasses",
    "-dontskipnonpubliclibraryclassmembers", "-overloadaggressively", "-useuniqueclassmembernames",
    "-dontusemixedcaseclassnames", "-ignorewarnings", "-dontnote", "-keepattributes",
}

PROGUARD_ATTRIBUTES = {
    "Signature", "InnerClasses", "EnclosingMethod", "Exceptions", "SourceFile",
    "LineNumberTable", "Deprecated", "MethodParameters", "RuntimeVisibleAnnotations",
    "RuntimeInvisibleAnnotations", "RuntimeVisibleParameterAnnotations",
    "RuntimeInvisibleParameterAnnotations", "AnnotationDefault", "LocalVariableTable",
    "LocalVariableTypeTable", "*Annotation*",
}


def check_proguard_rules(files: list[Path], errors: list[str], warnings: list[str]) -> None:
    """Синтаксис proguard-файлов: R8 падает на неизвестной директиве только в релизной сборке."""
    for path in files:
        for name in re.findall(r"proguardFiles[^\n]*?['\"]([\w./\-]+\.pro)['\"]", read(path)):
            rules = path.parent / name
            if not rules.exists():
                continue
            depth = 0
            for number, line in enumerate(read(rules).splitlines(), start=1):
                stripped = line.strip()
                if not stripped or stripped.startswith("#"):
                    continue
                if depth > 0:  # тело `-keep ... { … }` — директив внутри быть не может
                    depth += stripped.count("{") - stripped.count("}")
                    continue
                if not stripped.startswith("-"):
                    warnings.append(f"{rules.name}:{number}: строка вне директивы: {stripped[:60]}")
                    continue
                directive = stripped.split()[0]
                if directive not in PROGUARD_DIRECTIVES:
                    errors.append(
                        f"{rules.name}:{number}: неизвестная директива `{directive}` — R8 упадёт "
                        f"на релизной сборке"
                    )
                    continue
                if stripped.endswith("{") and stripped.count("{") > stripped.count("}"):
                    depth = 1
                if directive == "-keepattributes":
                    for attribute in re.split(r"[,\s]+", stripped[len(directive):].strip()):
                        if attribute and attribute not in PROGUARD_ATTRIBUTES and "*" not in attribute:
                            warnings.append(
                                f"{rules.name}:{number}: необычный атрибут `{attribute}` в -keepattributes"
                            )


# Задачи Gradle, которые существуют только в проекте БЕЗ flavor'ов. При наличии productFlavors
# Gradle создаёт `lintProductionDebug`/`testProductionDebugUnitTest`, а `lintDebug` становится
# неоднозначным (`Task 'lintDebug' is ambiguous`) — сборка падает ещё до компиляции.
VARIANTLESS_TASK = re.compile(
    r"^:?(?:app:)?(assemble|bundle|install|uninstall|test|lint|lintVital|connected|compile|kapt|ksp|"
    r"merge|package|minify|shrink)(Debug|Release)(AndroidTest|UnitTest)?$"
)


def read_flavors() -> list[str]:
    """Имена flavor'ов из `productFlavors { … }` в `app/build.gradle`."""
    text = read(ROOT / "app/build.gradle")
    if "productFlavors" not in text:
        return []
    block = text[text.index("productFlavors"):]
    flavors: list[str] = []
    depth = 0
    for line in block.splitlines():
        stripped = line.strip()
        match = re.fullmatch(r"(\w+)\s*\{", stripped)
        if depth == 1 and match:
            flavors.append(match.group(1))
        depth += line.count("{") - line.count("}")
        if depth <= 0 and flavors:
            break
    return flavors


def check_task_names(errors: list[str]) -> None:
    """`./gradlew lintDebug` в CI/доках при наличии flavor'ов — гарантированное падение."""
    flavors = read_flavors()
    if not flavors:
        return
    sources = [
        *sorted((ROOT / ".github/workflows").glob("*.yml")),
        ROOT / "codemagic.yaml",
        *sorted(ROOT.glob("*.sh")),
        *sorted((ROOT / "tools").glob("*.sh")),
        ROOT / "README.md",
        ROOT / "AGENTS.md",
        ROOT / "HANDOFF.md",
        # CHANGELOG описывает прошлые ошибки — исторические упоминания задач не проверяем.
        *[path for path in sorted((ROOT / "docs").glob("*.md")) if path.name != "CHANGELOG.md"],
    ]
    for path in sources:
        if not path.exists():
            continue
        for number, line in enumerate(read(path).splitlines(), start=1):
            if "gradlew" not in line:
                continue
            for raw in line.split("gradlew", 1)[1].split():
                task = raw.strip("\"'`")
                if not task or task.startswith("-") or "$" in task:
                    continue
                if not VARIANTLESS_TASK.match(task):
                    continue
                if any(flavor.lower() in task.lower() for flavor in flavors):
                    continue
                preferred = "production" if "production" in flavors else flavors[0]
                example = f"lint{preferred.capitalize()}Debug" if task.startswith("lint") else ""
                hint = f" (например `{example}`)" if example else ""
                errors.append(
                    f"{path.relative_to(ROOT)}:{number}: `{task}` неоднозначна при flavor'ах "
                    f"{flavors} — нужен вариант с flavor{hint}"
                )


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
    check_proguard_rules(files, errors, warnings)
    check_dependencies(files, ext, errors, warnings)
    check_androidx_flags(files, errors)
    check_task_names(errors)

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
