#!/usr/bin/env python3
"""Держит долг перед MockK под потолком и не даёт ему вырасти молча.

Зачем: MockK — единственная тяжёлая тестовая зависимость проекта. Файл, который его импортирует,
не выполняется в JVM-прогоне без сети (MockK нет в офлайн-наборе), поэтому такой тест
существует только для Gradle и Codemagic. Это допустимо — но только пока таких файлов немного
и пока их не становится больше «по привычке»: заглушка пишется быстрее фейка, и через полгода
MockK оказывается в каждом тесте.

Проверка — бюджет, а не вкус. Ни один из порогов не доказывает, что тест хороший; он доказывает,
что **долг не вырос**: новый файл с MockK или новая `relaxed`-заглушка требуют осознанного
решения (поднять потолок отдельным коммитом с объяснением, а не «так получилось»).

Что считается:

* файлы в `app/src/test`, импортирующие `io.mockk` (бюджет [BUDGET_FILES]);
* `mockk(relaxed = true)` / `mockk<T>(relaxed = true)` — relaxed-мок возвращает пустые значения
  вместо поведения, поэтому неожидаемый вызов не роняет тест (бюджет [BUDGET_RELAXED]).

    python3 tools/mockk_audit.py
    python3 tools/mockk_audit.py --verbose
    python3 tools/mockk_audit.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_ROOT = ROOT / "app/src/test"

# Текущее состояние. Понижать можно свободно (это и есть цель), повышать — только осознанно:
# например, когда тест на ViewModel невозможно написать без MockK.
BUDGET_FILES = 20
BUDGET_RELAXED = 33

MOCKK_IMPORT = re.compile(r"^import\s+io\.mockk", re.M)
RELAXED = re.compile(r"\b(?:mockk|mockkClass|spyk)\s*(?:<[^>]+>)?\s*\(\s*relaxed\s*=\s*true")
MOCK_CALL = re.compile(r"\bmockk(?:Class)?\s*(?:<[^>]+>)?\s*\(")


def measure(root: Path) -> dict[str, int]:
    """`файлы`, `relaxed`, `моки` по каталогу тестов."""
    test_root = root / "app/src/test"
    files = 0
    relaxed = 0
    mocks = 0
    if not test_root.exists():
        return {"files": 0, "relaxed": 0, "mocks": 0}
    for path in sorted(test_root.rglob("*.kt")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        if MOCKK_IMPORT.search(text):
            files += 1
        relaxed += len(RELAXED.findall(text))
        mocks += len(MOCK_CALL.findall(text))
    return {"files": files, "relaxed": relaxed, "mocks": mocks}


def per_file(root: Path) -> list[tuple[str, int, int]]:
    """`(файл, моков, relaxed)` по файлам, где MockK вообще используется."""
    rows: list[tuple[str, int, int]] = []
    test_root = root / "app/src/test"
    for path in sorted(test_root.rglob("*.kt")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        mocks = len(MOCK_CALL.findall(text))
        relaxed = len(RELAXED.findall(text))
        if mocks or relaxed:
            rows.append((path.relative_to(root).as_posix(), mocks, relaxed))
    return rows


def audit(
    stats: dict[str, int],
    budget_files: int = BUDGET_FILES,
    budget_relaxed: int = BUDGET_RELAXED,
) -> list[str]:
    errors: list[str] = []
    if stats["files"] > budget_files:
        errors.append(
            f"тест-файлов с MockK {stats['files']} при бюджете {budget_files} — новые тесты "
            "пишите с фейками (интерфейс + ручная реализация), MockK повышайте отдельным коммитом"
        )
    if stats["relaxed"] > budget_relaxed:
        errors.append(
            f"relaxed-моков {stats['relaxed']} при бюджете {budget_relaxed} — relaxed скрывает "
            "неожидаемый вызов: задайте поведение явно или используйте фейк"
        )
    return errors


def self_test() -> int:
    problems: list[str] = []

    def check(label: str, condition: bool) -> None:
        if not condition:
            problems.append(label)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        tests = root / "app/src/test/java/com/example"
        tests.mkdir(parents=True)
        (tests / "PlainTest.kt").write_text(
            "package com.example\n\nclass PlainTest {\n    @Test fun works() {}\n}\n",
            encoding="utf-8",
        )
        (tests / "MockTest.kt").write_text(
            "package com.example\n\n"
            "import io.mockk.mockk\n"
            "import io.mockk.every\n\n"
            "class MockTest {\n"
            "    private val dao: Dao = mockk(relaxed = true)\n"
            "    private val other: Other = mockk()\n"
            "    @Test fun works() { every { dao.load() } returns 1 }\n"
            "}\n",
            encoding="utf-8",
        )
        stats = measure(root)
        check("файл с MockK посчитан один", stats["files"] == 1)
        # В импорте `io.mockk.mockk` слово `mockk(` не встречается — считаются только вызовы.
        check("моки посчитаны", stats["mocks"] == 2)
        check("relaxed посчитан", stats["relaxed"] == 1)
        check("файл без MockK в бюджет не входит",
              all(not row[0].endswith("PlainTest.kt") for row in per_file(root)))

        check("в пределах бюджета — ошибок нет", audit(stats, 1, 1) == [])
        check("превышение по файлам поймано",
              any("бюджете" in error for error in audit(stats, 0, 5)))
        check("превышение по relaxed поймано",
              any("relaxed" in error for error in audit(stats, 5, 0)))

        empty = root / "empty"
        empty.mkdir()
        check("пустой каталог — нули", measure(empty) == {"files": 0, "relaxed": 0, "mocks": 0})

    if problems:
        for problem in problems:
            print(f"error: mockk_audit --self-test: {problem}")
        return 1
    print(
        "mockk_audit --self-test: ok (подсчёт файлов, моков и relaxed; бюджет по обоим "
        "порогам; файл без MockK в бюджет не входит; пустой каталог)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", action="store_true", help="показать файлы с MockK")
    parser.add_argument("--self-test", action="store_true", help="проверить правила на синтетике")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    stats = measure(ROOT)
    errors = audit(stats)
    for problem in errors:
        print(f"error: {problem}")
    if args.verbose:
        for name, mocks, relaxed in per_file(ROOT):
            print(f"{mocks:3d} моков, {relaxed:2d} relaxed — {name}")
    print(
        f"\n{len(errors)} error(s), 0 warning(s): MockK в {stats['files']} файлах "
        f"(бюджет {BUDGET_FILES}), {stats['mocks']} моков, {stats['relaxed']} relaxed "
        f"(бюджет {BUDGET_RELAXED})"
    )
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
