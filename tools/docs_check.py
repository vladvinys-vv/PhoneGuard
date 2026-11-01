#!/usr/bin/env python3
"""Сверка чисел в документации с фактическим состоянием репозитория.

Зачем: цифры в `README.md` и `docs/*.md` («217 файлов», «106 из 167 классов») стареют молча —
добавили файл, и документация начинает врать. Ручная правка уже дважды отставала от кода,
поэтому проверка автоматическая и стоит в `./tools/check_all.sh` и в CI.

Проверяется только то, что можно посчитать без Gradle и без компилятора:

1. количество Kotlin-файлов, на которых работает компиляторная проверка;
2. числа карты покрытия («N классов, M упомянуты в тестах»);
3. согласованность утверждений «N тестов в M файлах» между документами
   (сам прогон делает `tools/jvm_unit_test.py`; здесь ловим расхождение текстов).

Использование:
    python3 tools/docs_check.py            # 0 error(s) — всё сходится
    python3 tools/docs_check.py --verbose  # печатать найденные утверждения
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = [ROOT / "README.md", ROOT / "docs/DEVELOPMENT.md", ROOT / "docs/CHANGELOG.md"]

KT_FILES_IN_LINE = re.compile(r"(\d+)\s+файл(?:ах|ов|а)?")
COVERAGE_CLAIM = re.compile(r"(\d+)\s+класс(?:ов|а)?,\s*(\d+)\s+упомянут")
COVERAGE_SHORT = re.compile(r"(\d+)\s+из\s+(\d+)")
TESTS_CLAIM = re.compile(r"(\d+)\s+тестов в\s+(\d+)\s+файлах")


def docs() -> dict[Path, str]:
    return {path: path.read_text(encoding="utf-8") for path in DOCS if path.exists()}


def kotlin_files() -> int:
    return len(list((ROOT / "app/src").rglob("*.kt")))


def coverage() -> tuple[int, int] | None:
    try:
        out = subprocess.run(
            [sys.executable, str(ROOT / "tools/test_coverage_map.py")],
            capture_output=True, text=True, timeout=120, check=False,
        ).stdout
    except Exception:  # noqa: BLE001 — без карты проверяем остальное
        return None
    match = re.search(r"классов в приложении: (\d+), упомянуты в тестах: (\d+)", out)
    return (int(match.group(1)), int(match.group(2))) if match else None


def check() -> tuple[list[str], list[str]]:
    errors: list[str] = []
    notes: list[str] = []
    texts = docs()
    actual_files = kotlin_files()

    # Утверждение о числе файлов ищем построчно и только там, где рядом речь о компиляторе
    # или о наборе исходников (`main`/`test`/`androidTest`) — иначе поймаем «672 ресурса» и прочее.
    claimed_files: list[tuple[str, int]] = []
    for path, text in texts.items():
        for number, line in enumerate(text.splitlines(), start=1):
            if not any(marker in line for marker in ("Kotlin", "компилятор", "`main`")):
                continue
            for match in KT_FILES_IN_LINE.finditer(line):
                claimed_files.append((f"{path.name}:{number}", int(match.group(1))))
    if not claimed_files:
        notes.append("утверждений о количестве Kotlin-файлов в документации нет")
    for where, claimed in claimed_files:
        if claimed != actual_files:
            errors.append(f"{where}: указано {claimed} Kotlin-файлов, фактически {actual_files}")

    map_numbers = coverage()
    if map_numbers is None:
        notes.append("карта покрытия недоступна — числа «N классов» не проверялись")
    else:
        classes, mentioned = map_numbers
        for path, text in texts.items():
            for match in COVERAGE_CLAIM.finditer(text):
                got_classes, got_mentioned = int(match.group(1)), int(match.group(2))
                line = text[:match.start()].count("\n") + 1
                if (got_classes, got_mentioned) != (classes, mentioned):
                    errors.append(
                        f"{path.name}:{line}: указано {got_mentioned} из {got_classes} классов, "
                        f"фактически {mentioned} из {classes}"
                    )
            for match in COVERAGE_SHORT.finditer(text):
                # «106 из 167» в скобках — тот же смысл, но только в контексте «упомянуты в тестах»
                context = text[max(0, match.start() - 120):match.start()]
                if "упомянут" not in context:
                    continue
                got_mentioned, got_classes = int(match.group(1)), int(match.group(2))
                line = text[:match.start()].count("\n") + 1
                if (got_classes, got_mentioned) != (classes, mentioned):
                    errors.append(
                        f"{path.name}:{line}: указано {got_mentioned} из {got_classes} классов, "
                        f"фактически {mentioned} из {classes}"
                    )

    # Утверждения «N тестов в M файлах» должны совпадать между документами: прогон
    # выполняет другой инструмент, а вот расхождение текстов — заведомая ошибка.
    claims: list[tuple[str, int, int]] = []
    for path, text in texts.items():
        for match in TESTS_CLAIM.finditer(text):
            line = text[:match.start()].count("\n") + 1
            claims.append((f"{path.name}:{line}", int(match.group(1)), int(match.group(2))))
    if claims:
        values = {(tests, files) for _, tests, files in claims}
        if len(values) > 1:
            described = "; ".join(f"{where} — {tests} в {files}" for where, tests, files in claims)
            errors.append(f"документы расходятся в числе выполняемых тестов: {described}")
        else:
            tests, files = values.pop()
            notes.append(f"заявлено выполняемых тестов: {tests} в {files} файлах")

    return errors, notes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    errors, notes = check()
    if args.verbose:
        for note in notes:
            print(f"  {note}")
    for problem in errors:
        print(f"error: {problem}")
    print(f"{len(errors)} error(s), 0 warning(s): {kotlin_files()} Kotlin-файлов")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
