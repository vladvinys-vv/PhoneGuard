#!/usr/bin/env python3
"""Проверка ресурсов и манифеста настоящим `aapt2` (то, что делает Gradle-задача
`processDebugResources`).

Зачем: Kotlin-компилятор и регексп-аудит не видят самого частого источника падений
сборки — ресурсов. `aapt2 compile` ловит неэкранированные апострофы, битый XML,
неизвестные типы ресурсов; `aapt2 link` проверяет манифест против `android.jar`,
ссылки `@string/…` и генерирует `R.java`. Именно так были найдены строки
«'which su' succeeded» в `values-en`/`values-de`, на которых сборка падала бы.

    python3 tools/aapt2_check.py                     # compile + link
    python3 tools/aapt2_check.py --compile-only      # только ресурсы

Где взять инструменты (в изолированной песочнице без dl.google.com):
    python3 tools/fetch_android_jar.sh               # android.jar из GitHub-зеркала
    npm pack aaptjs3 && tar xzf aaptjs3-*.tgz        # пакет содержит aapt2 ≥ 2.20
    export AAPT2="$PWD/package/bin/x64/linux/aapt2"

В CI с установленным Android SDK ничего указывать не нужно: скрипт сам возьмёт
`$ANDROID_HOME/build-tools/*/aapt2` и `$ANDROID_HOME/platforms/android-*/android.jar`.
PyPI-пакет `aapt2` даёт версию 2.19 — для `compile` годится, но не умеет читать
`resources.arsc` из modern `android.jar`, поэтому для `link` нужен aapt2 ≥ 2.20.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
RES_DIR = ROOT / "app/src/main/res"
NAMESPACE = "com.phoneguard"
# Значения манифест-плейсхолдеров из app/build.gradle (flavor production + debug/release).
PLACEHOLDERS = {
    "${appLabel}": "PhoneGuard",
    "${applicationId}": NAMESPACE,
}


def find_aapt2(explicit: str | None) -> str | None:
    if explicit:
        return explicit if Path(explicit).is_file() else None
    env = os.environ.get("AAPT2")
    if env and Path(env).is_file():
        return env
    on_path = shutil.which("aapt2")
    if on_path:
        return on_path
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(var)
        if not root:
            continue
        build_tools = Path(root) / "build-tools"
        if build_tools.is_dir():
            for candidate in sorted(build_tools.glob("*/aapt2"), reverse=True):
                if candidate.is_file():
                    return str(candidate)
    # PyPI-пакет aapt2 кладёт бинарник в aapt2/bin/<OS>/aapt2
    try:
        import aapt2 as aapt2_pkg  # type: ignore

        base = Path(aapt2_pkg.__file__).parent / "bin"
        for name in ("Linux", "Darwin", "Windows"):
            candidate = base / name / ("aapt2.exe" if name == "Windows" else "aapt2")
            if candidate.is_file():
                return str(candidate)
    except ImportError:
        pass
    return None


API_DIR_RE = re.compile(r"android-(\d+)")


def read_compile_sdk() -> int | None:
    """`compileSdk 35` из `app/build.gradle` — эталон версии `android.jar` для link."""
    try:
        text = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    except OSError:
        return None
    match = re.search(r"\bcompileSdk(?:Version)?\s*=?\s*(\d+)", text)
    return int(match.group(1)) if match else None


def jar_api_level(jar: str | Path) -> int | None:
    """Уровень API по пути `.../android-35/android.jar` (иначе None — версия неизвестна)."""
    match = API_DIR_RE.search(str(jar))
    return int(match.group(1)) if match else None


def _platform_jars(roots: list[Path]) -> list[tuple[int, Path]]:
    """Все `platforms/android-NN/android.jar` с ЧИСЛОВОЙ сортировкой по API.

    Лексикографическая сортировка путей опасна: `android-9` оказывается «новее»
    `android-35`, и тогда манифест проверяется против древнего android.jar — именно
    из-за этого Codemagic получал ложную ошибку про `specialUse`.
    """
    found: list[tuple[int, Path]] = []
    for root in roots:
        for candidate in (root / "platforms").glob("android-*/android.jar"):
            level = jar_api_level(candidate)
            if level is not None and candidate.is_file():
                found.append((level, candidate))
    return sorted(found, key=lambda item: item[0])


def find_android_jar(explicit: str | None, prefer: int | None = None) -> str | None:
    if explicit:
        return explicit if Path(explicit).is_file() else None
    env = os.environ.get("ANDROID_JAR")
    if env and Path(env).is_file():
        return env

    roots = [
        Path(value)
        for value in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"))
        if value
    ]
    cache = Path(os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache")
    roots.append(cache / "phoneguard/android-jar")

    jars = _platform_jars(roots)
    if not jars:
        return None
    # 1) точное совпадение с compileSdk, 2) ближайший больший, 3) самый новый доступный
    for level, path in jars:
        if prefer is not None and level == prefer:
            return str(path)
    if prefer is not None:
        newer = [item for item in jars if item[0] >= prefer]
        if newer:
            return str(newer[0][1])
    return str(jars[-1][1])


def self_test() -> int:
    """Регресс-тест выбора `android.jar`: `android-9` не должен побеждать `android-35`."""
    problems: list[str] = []
    with tempfile.TemporaryDirectory() as tmp_dir:
        sdk = Path(tmp_dir) / "sdk"
        for level in (9, 35):
            target = sdk / "platforms" / f"android-{level}" / "android.jar"
            target.parent.mkdir(parents=True)
            target.write_bytes(b"")
        jars = [level for level, _ in _platform_jars([sdk])]
        if jars != [9, 35]:
            problems.append(f"числовая сортировка платформ сломалась: {jars}")
        best = _platform_jars([sdk])[-1][1]
        if jar_api_level(best) != 35:
            problems.append(f"выбран android.jar уровня {jar_api_level(best)}, ожидался 35")
        old = sdk / "platforms" / "android-9" / "android.jar"
        if jar_api_level(old) != 9:
            problems.append("уровень API определяется по пути неверно")

    for problem in problems:
        print(f"error: {problem}")
    if problems:
        print("\naapt2_check --self-test: провален")
        return 1
    print("aapt2_check --self-test: ok (android-9 не считается новее android-35)")
    return 0


def prepare_manifest(target: Path) -> None:
    """Подставляет manifest-плейсхолдеры и `package`, которые обычно добавляет AGP."""
    text = MANIFEST.read_text(encoding="utf-8")
    for placeholder, value in PLACEHOLDERS.items():
        text = text.replace(placeholder, value)
    if not re.search(r'<manifest\b[^>]*\bpackage="', text):
        text = re.sub(r'(<manifest\b)', r'\1 package="' + NAMESPACE + '"', text, count=1)
    target.write_text(text, encoding="utf-8")


R_REF_RE = re.compile(r"(?<!android\.)\bR\.(\w+)\.(\w+)")


def parse_r_java(path: Path) -> dict[str, set[str]]:
    """`R.java` от aapt2 -> {тип: {имена}} — эталон того, что реально существует."""
    defined: dict[str, set[str]] = {}
    current: str | None = None
    for line in path.read_text(encoding="utf-8").splitlines():
        cls = re.search(r"public static final class (\w+)", line)
        if cls:
            current = cls.group(1)
            defined.setdefault(current, set())
            continue
        field = re.search(r"public static final int (\w+)=", line)
        if field and current:
            defined[current].add(field.group(1))
    return defined


def check_r_references(defined: dict[str, set[str]]) -> list[str]:
    """Ссылки `R.type.name` в Kotlin должны существовать по данным aapt2."""
    problems: list[str] = []
    for path in sorted((ROOT / "app/src/main/java").rglob("*.kt")):
        rel = path.relative_to(ROOT)
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            for kind, name in R_REF_RE.findall(line):
                if kind not in defined:
                    problems.append(f"{rel}:{number}: R.{kind} — такого типа ресурсов нет")
                elif name not in defined[kind]:
                    problems.append(f"{rel}:{number}: R.{kind}.{name} не найден в R.java")
    return problems


def run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aapt2", default=None, help="путь к aapt2 (иначе $AAPT2/PATH/SDK)")
    parser.add_argument("--android-jar", default=None, help="android.jar для link")
    parser.add_argument("--compile-only", action="store_true", help="не линковать манифест")
    parser.add_argument("--self-test", action="store_true", help="проверить выбор android.jar")
    parser.add_argument(
        "--require-compile-sdk",
        action="store_true",
        help="падать, если android.jar старее compileSdk (для CI, где SDK ставится явно)",
    )
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    aapt2 = find_aapt2(args.aapt2)
    if aapt2 is None:
        print(
            "aapt2_check: пропущено — aapt2 не найден "
            "(нужен aapt2 ≥ 2.20: npm pack aaptjs3, либо Android SDK build-tools)"
        )
        return 0

    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp = Path(tmp_dir)
        res_zip = tmp / "res.zip"

        proc = run([aapt2, "compile", "--dir", str(RES_DIR), "-o", str(res_zip)])
        log = (proc.stdout or "") + (proc.stderr or "")
        if proc.returncode != 0 or "error:" in log:
            print(log.strip())
            print(f"\naapt2_check: ресурсы не компилируются ({RES_DIR.relative_to(ROOT)})")
            return 1

        if args.compile_only:
            print("aapt2_check: ресурсы компилируются")
            return 0

        compile_sdk = read_compile_sdk()
        android_jar = find_android_jar(args.android_jar, compile_sdk)
        if android_jar is None:
            print(
                "aapt2_check: ресурсы компилируются, link пропущен — нет android.jar "
                "(см. tools/fetch_android_jar.sh)"
            )
            return 0

        # Проверять манифест против android.jar старее compileSdk бессмысленно: aapt2
        # отвергает атрибуты, которых в этом API ещё нет (`specialUse` появился в 34).
        # Gradle линкует настоящий compileSdk, поэтому это предупреждение, а не ошибка.
        jar_level = jar_api_level(android_jar)
        if compile_sdk is not None and jar_level is not None and jar_level < compile_sdk:
            message = (
                f"aapt2_check: android.jar API {jar_level} < compileSdk {compile_sdk} "
                f"({android_jar}) — манифест не линкуется, чтобы не ловить ложные ошибки "
                f"(`specialUse` и другие атрибуты появились позже); "
                f"ресурсы проверены, полную проверку манифеста делает Gradle"
            )
            if args.require_compile_sdk:
                print(f"error: {message}")
                return 1
            print(message)
            return 0

        manifest = tmp / "AndroidManifest.xml"
        prepare_manifest(manifest)
        gen_dir = tmp / "gen"
        gen_dir.mkdir()

        proc = run(
            [
                aapt2,
                "link",
                "-I", android_jar,
                "--manifest", str(manifest),
                "-o", str(tmp / "resources.apk"),
                "--java", str(gen_dir),
                "--min-sdk-version", "26",
                "--target-sdk-version", "35",
                "--version-code", "1",
                "--version-name", "1.0.0",
                "--auto-add-overlay",
                str(res_zip),
            ]
        )
        log = (proc.stdout or "") + (proc.stderr or "")
        if proc.returncode != 0 or "error:" in log:
            print(log.strip())
            print("\naapt2_check: манифест/ссылки на ресурсы не проходят link")
            return 1

        r_java = next(gen_dir.rglob("R.java"), None)
        if r_java is None:
            print("aapt2_check: R.java не сгенерирован")
            return 1
        defined = parse_r_java(r_java)
        r_count = sum(len(names) for names in defined.values())
        problems = check_r_references(defined)
        if problems:
            for problem in problems:
                print(f"error: {problem}")
            print(f"\naapt2_check: {len(problems)} ссылок на несуществующие ресурсы")
            return 1

    print(f"aapt2_check: ресурсы и манифест проходят compile+link ({r_count} ресурсов в R.java)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
