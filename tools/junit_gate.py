#!/usr/bin/env python3
"""Сверяет тест-классы в дереве с JUnit-XML из реального прогона.

Зачем: молча не запустившийся тест-файл — самый неприятный вид поломки в тестах. Gradle
возвращает код 0, отчёт зелёный, а класса в нём просто нет. Причины бывают бытовые: файл
переехал в другой source set, класс стал `abstract` при рефакторинге, появился
`testOptions.unitTests.all { filter { ... } }`, тест-класс переименовали и забыли, что Gradle
ищет тесты по шаблону `*Test`. Всё это выглядит как «всё хорошо» и находится через месяцы.

Проверка сравнивает два независимых источника:

* **дерево** — каждый Kotlin-файл в `app/src/test` с `@Test`-функциями: сколько их и в каком
  классе;
* **отчёт** — `TEST-*.xml`, который Gradle пишет в `app/build/test-results/<task>/`.

Класс из дерева, которого нет в отчёте, и класс, у которого в отчёте меньше кейсов, чем
`@Test` в исходнике, — ошибка. Лишние кейсы в отчёте не ошибка: параметризованные и
унаследованные тесты — законная причина.

Локально (без Gradle) отчёта нет, поэтому по умолчанию сверка **пропускается** с явным
сообщением, а `--require-report` делает её обязательной — так этот флаг и стоит в CI.

    python3 tools/junit_gate.py                 # локально: отчёта нет — пропуск
    python3 tools/junit_gate.py --require-report  # в CI: отчёта нет — ошибка
    python3 tools/junit_gate.py --verbose
    python3 tools/junit_gate.py --self-test

Порог `--min-cases N` отвечает на вопрос, который сверка по классам не закрывает: **сколько
тестов выполнилось всего**. Сверка по классам пропускает то, что внесено в allow-файл, а фильтр
Gradle (`--tests`, `unitTests.all { filter { ... } }`) может оставить от каждого класса по одному
кейсу, и все классы при этом «есть в отчёте». Порог в CI задаётся числом ниже текущего дерева
(сейчас 1085 `@Test`): он не мешает добавлять тесты, но падает, когда прогон потерял сотню.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_ROOT = ROOT / "app/src/test"
REPORT_ROOT = ROOT / "app/build/test-results/testProductionDebugUnitTest"
ALLOWLIST = ROOT / "tools/junit_gate_allow.txt"

# Объявление класса на верхнем уровне файла: дальше идут либо тесты этого класса, либо
# вспомогательные классы, которые тестами не считаются.
CLASS_DECL = re.compile(
    r"^(?P<mods>(?:(?:private|internal|public|open|sealed|data|inner)\s+)*)"
    r"class\s+(?P<name>\w+)"
)
TEST_FN = re.compile(r"^\s+@Test\b")


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip(" \t"))


def scan_sources(test_root: Path) -> dict[str, tuple[Path, int]]:
    """`Класс → (файл, сколько @Test)` для всех тест-классов дерева.

    Считаются только классы верхнего уровня, а `@Test`-функции — по отступу внутри них:
    вложенные вспомогательные классы (`private class RecordingDb`) идут с отступом и в
    тест-классы не попадают, иначе проверка требовала бы их в отчёте.
    """
    found: dict[str, tuple[Path, int]] = {}
    for path in sorted(test_root.rglob("*.kt")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        current: str | None = None
        current_abstract = False
        counts: dict[str, int] = {}
        for line in text.splitlines():
            if indent_of(line) == 0:
                match = CLASS_DECL.match(line)
                if match:
                    current = match.group("name")
                    current_abstract = "abstract" in match.group("mods").split()
                    counts.setdefault(current, 0)
                    continue
            if current is not None and not current_abstract and TEST_FN.match(line):
                counts[current] = counts.get(current, 0) + 1
        for name, count in counts.items():
            if count > 0:
                found[name] = (path, count)
    return found


def scan_report(report_root: Path) -> dict[str, int]:
    """`Класс → сколько кейсов` по JUnit-XML.

    Имя класса в отчёте — полное (`com.phoneguard.FooTest`), у вложенного —
    `Outer$Inner`; для сверки достаточно простого имени внешнего класса.
    """
    cases: dict[str, int] = {}
    for path in sorted(report_root.glob("TEST-*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:  # обрезанный файл = непрочитанный отчёт, а не «ноль»
            raise ValueError(f"{path.name}: не разбирается как XML ({error})") from error
        for case in root.iter("testcase"):
            classname = case.get("classname") or ""
            simple = classname.rsplit(".", 1)[-1].split("$")[0]
            if simple:
                cases[simple] = cases.get(simple, 0) + 1
    return cases


def read_allowlist(path: Path) -> dict[str, str]:
    """`Класс — причина` (разделитель `—`, `#` или два пробела). Причина обязательна."""
    allowed: dict[str, str] = {}
    if not path.exists():
        return allowed
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        for separator in ("—", "#", "  "):
            if separator in stripped:
                name, reason = stripped.split(separator, 1)
                break
        else:
            name, reason = stripped, ""
        allowed[name.strip()] = reason.strip()
    return allowed


def compare(
    sources: dict[str, tuple[Path, int]],
    report: dict[str, int],
    allowed: dict[str, str],
    test_root: Path,
) -> tuple[list[str], int, int]:
    """Возвращает (ошибки, сколько классов сверено, сколько кейсов в отчёте)."""
    errors: list[str] = []
    checked = 0
    for name, (path, declared) in sorted(sources.items()):
        if name in allowed:
            continue
        checked += 1
        relative = path.relative_to(test_root.parent.parent.parent)
        if name not in report:
            errors.append(f"{relative}: класс `{name}` в отчёте отсутствует — тесты не запускались")
        elif report[name] < declared:
            errors.append(
                f"{relative}: в отчёте `{name}` — {report[name]} из {declared} тестов"
            )
    for name, reason in allowed.items():
        if not reason:
            errors.append(f"tools/junit_gate_allow.txt: у `{name}` нет причины")
        if name not in sources:
            errors.append(
                f"tools/junit_gate_allow.txt: `{name}` больше не тест-класс — запись устарела"
            )
    return errors, checked, sum(report.values())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test-dir", type=Path, default=TEST_ROOT, help="корень unit-тестов")
    parser.add_argument("--report-dir", type=Path, default=REPORT_ROOT, help="каталог TEST-*.xml")
    parser.add_argument(
        "--require-report",
        action="store_true",
        help="отчёт обязателен (CI): без него проверка падает, а не пропускается",
    )
    parser.add_argument(
        "--min-cases",
        type=int,
        default=0,
        help="минимальное число тестов в отчёте (0 — порога нет)",
    )
    parser.add_argument("--verbose", action="store_true", help="показать сверенные классы")
    parser.add_argument("--self-test", action="store_true", help="проверить правила на синтетике")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if not args.test_dir.exists():
        print(f"error: {args.test_dir} не найден")
        return 1

    sources = scan_sources(args.test_dir)
    if not sources:
        # Ноль найденных тест-классов означает сломанный разбор или пустой каталог, а не
        # «тесты не нужны»: молча зелёный результат здесь хуже всего.
        print(f"error: в {args.test_dir} не найдено ни одного класса с @Test")
        return 1

    reports = sorted(args.report_dir.glob("TEST-*.xml")) if args.report_dir.exists() else []
    if not reports:
        message = f"отчёта {args.report_dir.relative_to(ROOT) if args.report_dir.is_relative_to(ROOT) else args.report_dir} нет — сверка пропущена (её делает CI)"
        if args.require_report:
            print(f"error: {message}; нужен --require-report-режим с реальным прогоном Gradle")
            return 1
        print(f"junit_gate: {message}")
        return 0

    try:
        report = scan_report(args.report_dir)
    except ValueError as error:
        print(f"error: {error}")
        return 1
    allowed = read_allowlist(ALLOWLIST)
    errors, checked, cases = compare(sources, report, allowed, args.test_dir)
    if args.min_cases and cases < args.min_cases:
        errors.append(
            f"в отчёте {cases} тестов при пороге {args.min_cases} — прогон потерял тесты "
            "(--tests, filter, отключённый source set), хотя классы формально на месте"
        )

    for problem in errors:
        print(f"error: {problem}")
    if args.verbose:
        for name, (path, declared) in sorted(sources.items()):
            print(f"{name}: дерево {declared}, отчёт {report.get(name, 0)} ({path.name})")

    print(
        f"\n{len(errors)} error(s), 0 warning(s): {checked} тест-классов сверено, "
        f"{cases} тестов в отчёте"
        + (f" (разрешено {len(allowed)})" if allowed else "")
        + (f", порог {args.min_cases}" if args.min_cases else "")
    )
    return 1 if errors else 0


def self_test() -> int:
    """Правила проверяются на синтетическом дереве, а не на живом отчёте."""
    problems: list[str] = []

    def check(label: str, condition: bool) -> None:
        if not condition:
            problems.append(label)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        tests = root / "app/src/test/java/com/example"
        tests.mkdir(parents=True)
        (tests / "GoodTest.kt").write_text(
            "package com.example\n\n"
            "class GoodTest {\n"
            "    @Test\n"
            "    fun one() {}\n\n"
            "    @Test\n"
            "    fun two() {}\n"
            "}\n",
            encoding="utf-8",
        )
        # Вложенный вспомогательный класс с отступом не должен требовать себя в отчёте.
        (tests / "NestedTest.kt").write_text(
            "package com.example\n\n"
            "class NestedTest {\n"
            "    @Test\n"
            "    fun works() {}\n\n"
            "    private class RecordingDb\n"
            "}\n",
            encoding="utf-8",
        )
        # Абстрактный базовый класс с @Test-хелпером тестом не считается.
        (tests / "BaseTest.kt").write_text(
            "package com.example\n\n"
            "abstract class BaseTest {\n"
            "    @Test\n"
            "    fun helper() {}\n"
            "}\n",
            encoding="utf-8",
        )

        sources = scan_sources(root / "app/src/test")
        check("класс с тестами найден", sources.get("GoodTest", (None, 0))[1] == 2)
        check("вложенный класс не считается тестом", "RecordingDb" not in sources)
        check("абстрактный класс не считается тестом", "BaseTest" not in sources)

        report_dir = root / "app/build/test-results/task"
        report_dir.mkdir(parents=True)

        def write_report(name: str, body: str) -> None:
            (report_dir / name).write_text(body, encoding="utf-8")

        def suite(classname: str, cases: int) -> str:
            inner = "".join(
                f'    <testcase name="t{i}" classname="{classname}"/>\n' for i in range(cases)
            )
            return f'<testsuite name="{classname}" tests="{cases}">\n{inner}</testsuite>\n'

        write_report(
            "TEST-GoodTest.xml",
            '<?xml version="1.0" encoding="UTF-8"?>\n<testsuites>\n'
            + suite("com.example.GoodTest", 2)
            + suite("com.example.NestedTest", 1)
            + "</testsuites>\n",
        )
        report = scan_report(report_dir)
        allowed: dict[str, str] = {}
        errors, checked, _ = compare(sources, report, allowed, root / "app/src/test")
        check("полный отчёт ошибок не даёт", errors == [])
        check("сверены оба класса", checked == 2)

        # Один кейс выпал — это должно быть видно, а не «всё хорошо».
        write_report(
            "TEST-GoodTest.xml",
            '<?xml version="1.0" encoding="UTF-8"?>\n<testsuites>\n'
            + suite("com.example.GoodTest", 1)
            + "</testsuites>\n",
        )
        errors, _, _ = compare(sources, scan_report(report_dir), allowed, root / "app/src/test")
        check("недостача кейсов — ошибка", len(errors) == 2)
        check(
            "в ошибке видно, сколько тестов дошло",
            any("1 из 2 тестов" in error for error in errors),
        )

        # Класс вообще не попал в отчёт.
        write_report(
            "TEST-GoodTest.xml",
            '<?xml version="1.0" encoding="UTF-8"?>\n<testsuites>\n'
            + suite("com.example.GoodTest", 2)
            + suite("com.example.NestedTest", 1)
            + "</testsuites>\n",
        )
        report = scan_report(report_dir)
        report.pop("NestedTest", None)
        errors, _, _ = compare(sources, report, allowed, root / "app/src/test")
        check(
            "отсутствующий в отчёте класс — ошибка",
            any("NestedTest" in error and "отсутствует" in error for error in errors),
        )

        # Вложенный класс в отчёте (`Outer$Inner`) сверяется с внешним.
        check(
            "вложенный класс в отчёте считается внешним",
            scan_report_cases('com.example.Outer$Inner') == {"Outer": 1},
        )

        allow = root / "allow.txt"
        allow.write_text("NestedTest — проверяется только в Robolectric-прогоне\n", encoding="utf-8")
        allowed = read_allowlist(allow)
        report = {"GoodTest": 2}
        errors, checked, _ = compare(sources, report, allowed, root / "app/src/test")
        check("разрешённый класс не требуется в отчёте", errors == [])
        check("разрешённый класс не считается сверенным", checked == 1)

        allow.write_text("NestedTest\n", encoding="utf-8")
        errors, _, _ = compare(sources, {"GoodTest": 2}, read_allowlist(allow), root / "app/src/test")
        check("запись без причины — ошибка", any("нет причины" in error for error in errors))

        # Главное правило включения: без отчёта локально — пропуск, в CI — падение.
        empty = root / "empty-results"
        empty.mkdir()
        script = str(Path(__file__).resolve())
        base = [sys.executable, script, "--test-dir", str(root / "app/src/test")]
        strict = subprocess.run(
            base + ["--report-dir", str(empty), "--require-report"],
            capture_output=True,
            text=True,
            check=False,
        )
        check("--require-report без отчёта — ошибка", strict.returncode == 1)
        check(
            "в строгом режиме видно, чего не хватает",
            "отчёта" in strict.stdout,
        )
        lenient = subprocess.run(
            base + ["--report-dir", str(empty)],
            capture_output=True,
            text=True,
            check=False,
        )
        check(
            "без --require-report отсутствие отчёта — явный пропуск",
            lenient.returncode == 0 and "пропущена" in lenient.stdout,
        )

        # Порог по общему числу тестов: классы на месте, но прогон потерял кейсы.
        lost = subprocess.run(
            base + ["--report-dir", str(report_dir), "--min-cases", "5"],
            capture_output=True,
            text=True,
            check=False,
        )
        check("порог по числу тестов ловится", lost.returncode == 1)
        check("в ошибке порога видно число тестов", "пороге 5" in lost.stdout)
        kept = subprocess.run(
            base + ["--report-dir", str(report_dir), "--min-cases", "3"],
            capture_output=True,
            text=True,
            check=False,
        )
        check("выполненный порог не мешает", kept.returncode == 0)

        allow.write_text("GhostTest — удалённый класс\n", encoding="utf-8")
        errors, _, _ = compare(sources, {"GoodTest": 2}, read_allowlist(allow), root / "app/src/test")
        check("устаревшая запись — ошибка", any("устарела" in error for error in errors))

    if problems:
        for problem in problems:
            print(f"error: junit_gate --self-test: {problem}")
        return 1
    print(
        "junit_gate --self-test: ok (полный отчёт, недостача кейсов, отсутствующий класс, "
        "вложенные и абстрактные классы, allow-файл, порог по числу тестов)"
    )
    return 0


def scan_report_cases(classname: str) -> dict[str, int]:
    """Разбор одного имени класса из отчёта (для self-test)."""
    simple = classname.rsplit(".", 1)[-1].split("$")[0]
    return {simple: 1}


if __name__ == "__main__":
    sys.exit(main())
