#!/usr/bin/env python3
"""Проверка конфигураций CI: пайплайн должен уметь падать и запускать те же проверки.

Зачем: `.gitlab-ci.yml`, добавленный внешним PR, выглядел как настоящий CI, но не проверял
ничего — каждая команда заканчивалась `|| true`, задачи Gradle были без флейвора
(`testDebugUnitTest` при флейворах `staging`/`production`), а в образе `gradle:8.7-jdk17` нет
Android SDK, без которого AGP не конфигурируется. Такой пайплайн зелёный всегда, в том числе
когда тесты падают, — и заметить это можно только вручную.

Проверки:
  * ни одна команда не глотает код возврата (`|| true`, `set +e`, `continue-on-error: true`);
  * задачи Gradle существуют в этом проекте: флейвор из `productFlavors`, а не «как в туториале»;
  * если запускается Gradle — в конфигурации есть Android SDK (`setup-android`, `sdkmanager`,
    `ANDROID_HOME`), иначе сборка падает на конфигурации;
  * запускается unit-тест-задача `testProductionDebugUnitTest` — та же, что в остальных CI;
  * если CI запускает хоть одну проверку из `tools/`, он запускает весь обязательный набор:
    иначе конфигурации расходятся, и один CI ловит то, чего не ловит другой.

    python3 tools/ci_check.py
    python3 tools/ci_check.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Обязательный набор статических проверок. Все они работают без Android SDK и без сети,
# поэтому «в этом CI их нет» — это решение, а не техническая невозможность.
REQUIRED_CHECKS = (
    "gradle_check.py",
    "proguard_check.py",
    "migration_check.py",
    "static_audit.py",
    "docs_check.py",
    "sync_strings.py",
    "unused_strings.py",
)

UNIT_TEST_TASK = "testProductionDebugUnitTest"

# Конструкции, после которых падение команды перестаёт быть видимым.
SWALLOWED = (
    (r"^\s*set\s+\+e\b", "`set +e` отключает остановку на ошибке"),
    (r"^\s*continue-on-error:\s*true", "`continue-on-error: true` скрывает падение шага"),
)
# `|| true` сам по себе бывает законным (`command -v sdkmanager || true`,
# `yes | sdkmanager --licenses || true`), поэтому он проверяется только в строках,
# которые запускают сборку или проверку: там он означает «упало — не важно».
SWALLOWING_TAIL = re.compile(r"\|\|\s*true\b")
RUNNABLE = ("./gradlew", "gradle ", "tools/")

# Признаки того, что Android SDK в образе есть (или ставится).
SDK_MARKERS = (
    "setup-android",
    "sdkmanager",
    "ANDROID_HOME",
    "ANDROID_SDK_ROOT",
    "android-sdk",
    "commandlinetools",
)

GRADLEW = re.compile(r"\./gradlew\s+((?:[^\n\\]|\\\n)+)")
TOOL_MENTION = re.compile(r"tools/(\w+\.py)")
VARIANT_SUFFIXES = ("Debug", "Release")


def ci_files() -> dict[Path, str]:
    """Все конфигурации CI в репозитории, которые вообще упоминают сборку или tools/."""
    candidates = list((ROOT / ".github/workflows").glob("*.y*ml"))
    candidates += [ROOT / "codemagic.yaml", ROOT / ".gitlab-ci.yml"]
    found: dict[Path, str] = {}
    for path in candidates:
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        if "gradlew" in text or "tools/" in text:
            found[path] = text
    return found


def flavors() -> set[str]:
    """Имена флейворов из `productFlavors` в `app/build.gradle` (Groovy, скобочный разбор)."""
    path = ROOT / "app/build.gradle"
    if not path.exists():
        return set()
    text = path.read_text(encoding="utf-8")
    start = text.find("productFlavors")
    if start < 0:
        return set()
    depth = 0
    names: set[str] = set()
    tail = text[start:]
    for index, char in enumerate(tail):
        if char == "{":
            depth += 1
            if depth == 2:
                match = re.search(r"(\w+)\s*$", tail[:index].rstrip())
                if match:
                    names.add(match.group(1))
        elif char == "}":
            depth -= 1
            if depth == 0:
                break
    return names


def strip_comments(text: str) -> str:
    """Комментарии не запускаются — но упоминать проверки в них можно."""
    lines = []
    for line in text.splitlines():
        stripped = line.lstrip()
        if stripped.startswith("#"):
            continue
        lines.append(line.split(" #")[0] if " #" in line else line)
    return "\n".join(lines)


def variant_of(task: str) -> tuple[str, str, str] | None:
    """`testProductionDebugUnitTest` → ('test', 'Production', 'Debug'); иначе None.

    Флейвор в задаче пишется с заглавной (`Production`), а в `productFlavors` — со строчной
    (`production`): сравнивать их «как есть» бессмысленно, первая же проверка соврала.
    """
    for prefix in ("test", "assemble", "bundle", "lint"):
        if not task.startswith(prefix) or len(task) == len(prefix):
            continue
        rest = task[len(prefix):]
        if prefix == "test":
            if not rest.endswith("UnitTest"):
                continue
            rest = rest[: -len("UnitTest")]
        for variant in VARIANT_SUFFIXES:
            if rest.endswith(variant):
                return prefix, rest[: -len(variant)], variant
    return None


def gradle_tasks(text: str) -> list[str]:
    tasks: list[str] = []
    for match in GRADLEW.finditer(text):
        chunk = match.group(1).replace("\\\n", " ")
        for token in chunk.split():
            if token.startswith("-") or token in {"&&", ";", "|"}:
                continue
            if re.fullmatch(r"[A-Za-z][\w:]*", token):
                tasks.append(token)
    return tasks


def check_text(label: str, text: str, project_flavors: set[str], github: bool) -> list[str]:
    """Проверки одной конфигурации. Вынесено отдельно, чтобы самотест мог подать синтетику."""
    errors: list[str] = []
    body = strip_comments(text)

    for pattern, message in SWALLOWED:
        for match in re.finditer(pattern, body, re.M):
            line = body[: match.start()].count("\n") + 1
            errors.append(f"{label}:{line}: {message}")

    for match in SWALLOWING_TAIL.finditer(body):
        start = body.rfind("\n", 0, match.start()) + 1
        end = body.find("\n", match.end())
        line_text = body[start : end if end >= 0 else len(body)]
        if any(marker in line_text for marker in RUNNABLE):
            line = body[: match.start()].count("\n") + 1
            errors.append(
                f"{label}:{line}: `|| true` после сборки или проверки — job будет зелёным "
                "при падении"
            )

    tasks = gradle_tasks(body)
    for task in tasks:
        parsed = variant_of(task)
        if parsed is None:
            continue
        _, flavor, _variant = parsed
        if not flavor:
            errors.append(
                f"{label}: задача `{task}` без флейвора — в проекте флейворы "
                f"{', '.join(sorted(project_flavors)) or '(нет)'}, "
                f"тесты называются {UNIT_TEST_TASK}"
            )
        elif flavor[0].lower() + flavor[1:] not in project_flavors:
            errors.append(
                f"{label}: задача `{task}` — флейвора `{flavor[0].lower() + flavor[1:]}` "
                "в app/build.gradle нет"
            )

    if tasks:
        has_sdk = any(marker in body for marker in SDK_MARKERS)
        if not has_sdk:
            errors.append(
                f"{label}: запускается Gradle, но Android SDK не настроен "
                f"(нужен `setup-android`, `sdkmanager` или `ANDROID_HOME`) — "
                "AGP падает на конфигурации проекта"
            )
        if UNIT_TEST_TASK not in tasks:
            errors.append(
                f"{label}: запускается Gradle, но нет `{UNIT_TEST_TASK}` — "
                "тесты в этом CI не выполняются"
            )

    release_tasks = [
        task for task in tasks if (parsed := variant_of(task)) and parsed[2] == "Release"
    ]
    if release_tasks and "--mapping" not in body:
        errors.append(
            f"{label}: собирается release ({', '.join(sorted(set(release_tasks)))}), но "
            "`mapping.txt` не проверяется — обфускация останется непроверенной"
        )

    mentioned = set(TOOL_MENTION.findall(body))
    if mentioned:
        missing = [name for name in REQUIRED_CHECKS if name not in mentioned]
        if missing:
            errors.append(
                f"{label}: не запускает {', '.join('tools/' + name for name in missing)} — "
                "проверки расходятся между CI, и ошибку поймает только другой CI"
            )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="проверить сами правила на синтетике")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    files = ci_files()
    project_flavors = flavors()
    errors: list[str] = []
    for path, text in files.items():
        github = path.suffix in {".yml", ".yaml"} and ".github" in path.parts
        errors.extend(check_text(str(path.relative_to(ROOT)), text, project_flavors, github))

    for problem in errors:
        print(f"error: {problem}")
    print(f"\n{len(errors)} error(s), 0 warning(s): {len(files)} конфигураций CI, "
          f"флейворы: {', '.join(sorted(project_flavors)) or 'нет'}")
    return 1 if errors else 0


def self_test() -> int:
    problems: list[str] = []
    project = {"staging", "production"}
    good = """
jobs:
  unit-test:
    steps:
      - uses: android-actions/setup-android@v3
      - run: |
          python3 tools/gradle_check.py
          python3 tools/proguard_check.py
          python3 tools/migration_check.py
          python3 tools/static_audit.py
          python3 tools/docs_check.py
          python3 tools/sync_strings.py --check
          python3 tools/unused_strings.py
      - run: ./gradlew testProductionDebugUnitTest --no-daemon
      - run: ./gradlew assembleProductionRelease --no-daemon
      - run: python3 tools/proguard_check.py --mapping app/build/outputs/mapping/productionRelease/mapping.txt
"""
    if check_text("good.yml", good, project, True):
        problems.append(f"правильная конфигурация не должна ругаться: {check_text('good.yml', good, project, True)}")

    if not any("|| true" in problem for problem in check_text(
        "bad.yml", "./gradlew testProductionDebugUnitTest || true", project, False
    )):
        problems.append("`|| true` не пойман")

    if not any("без флейвора" in problem for problem in check_text(
        "bad.yml", "android-actions/setup-android\n./gradlew testDebugUnitTest", project, True
    )):
        problems.append("задача без флейвора не поймана")

    if not any("Android SDK" in problem for problem in check_text(
        "bad.yml", "./gradlew testProductionDebugUnitTest", project, False
    )):
        problems.append("отсутствие Android SDK не поймано")

    if not any("proguard_check.py" in problem for problem in check_text(
        "bad.yml",
        "python3 tools/gradle_check.py\npython3 tools/static_audit.py",
        project,
        False,
    )):
        problems.append("неполный набор проверок не пойман")

    if not any("Флейвора" in problem or "флейвора" in problem for problem in check_text(
        "bad.yml", "android-actions/setup-android\n./gradlew testBetaDebugUnitTest", project, True
    )):
        problems.append("несуществующий флейвор не пойман")

    if not any("mapping.txt" in problem for problem in check_text(
        "bad.yml",
        "android-actions/setup-android@v3\n./gradlew testProductionDebugUnitTest "
        "assembleProductionRelease",
        project,
        True,
    )):
        problems.append("release-сборка без проверки mapping.txt не поймана")

    if problems:
        for problem in problems:
            print(f"error: ci_check --self-test: {problem}")
        return 1
    print("ci_check --self-test: ok (6 правил: || true, флейвор, SDK, обязательные проверки, "
          "unit-тесты, mapping.txt)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
