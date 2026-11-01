#!/usr/bin/env python3
"""Карта упоминаний классов приложения в тестах.

Это **не** процент покрытия и не замена JaCoCo: скрипт отвечает на вопрос «есть ли вообще
тест, который трогает этот класс», и показывает, где тестов нет совсем. Так видно, куда
писать тесты, не запуская Gradle (в песочнице без Google Maven он не работает).

Границы честные: упоминание класса в тесте не значит, что покрыты все ветки, а отсутствие
упоминания не всегда пробел — UI, Hilt-модули и сервисы тестируются иначе (Robolectric,
ручные сценарии `docs/MANUAL_TESTING.md`).

Использование:
    python3 tools/test_coverage_map.py             # сводка по пакетам + список без тестов
    python3 tools/test_coverage_map.py --detail    # вывести все классы без упоминаний
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java"
TESTS = (ROOT / "app/src/test/java", ROOT / "app/src/androidTest/java")

DECLARATION = re.compile(
    r"^(?:internal |private |public |abstract |open |sealed |data |enum |annotation )*"
    r"(?:class|object|interface) (\w+)",
    re.M,
)

# То, что осмысленно покрывать unit-тестами: логика, модели, парсеры, репозитории.
TESTABLE_PREFIXES = ("util", "data", "domain", "firewall", "intrusion", "simswap", "callblocker",
                     "deviceaudit", "fullscan", "threatintel", "model", "antitheft", "spywarescan")
# То, что по своей природе требует Android-окружения.
ANDROID_BOUND_PREFIXES = ("ui", "di")


def classes_by_package() -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for path in MAIN.rglob("*.kt"):
        package = re.sub(r"^com\.phoneguard\.?", "", path.parent.relative_to(MAIN / "com/phoneguard").as_posix()
                         .replace("/", ".")) if path.parent != MAIN / "com/phoneguard" else ""
        for name in DECLARATION.findall(path.read_text(encoding="utf-8")):
            result.setdefault(package, []).append(name)
    return result


def test_sources() -> str:
    return "\n".join(path.read_text(encoding="utf-8") for root in TESTS if root.exists() for path in root.rglob("*.kt"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--detail", action="store_true", help="показать все классы без тестов")
    args = parser.parse_args()

    sources = test_sources()
    packages = classes_by_package()

    total = tested = 0
    untested_testable: list[str] = []
    untested_android: list[str] = []
    rows: list[tuple[str, int, int]] = []

    for package, classes in sorted(packages.items()):
        covered = [c for c in classes if re.search(rf"\b{re.escape(c)}\b", sources)]
        total += len(classes)
        tested += len(covered)
        rows.append((package or ".", len(classes), len(covered)))
        for name in classes:
            if name in covered:
                continue
            if package.split(".")[0] in ANDROID_BOUND_PREFIXES:
                untested_android.append(f"{package}.{name}")
            elif package.split(".")[0] in TESTABLE_PREFIXES:
                untested_testable.append(f"{package}.{name}")

    print(f"классов в приложении: {total}, упомянуты в тестах: {tested} "
          f"({tested * 100 // max(total, 1)}%)")
    print("\nпо пакетам (классов / с тестами):")
    for package, count, covered in sorted(rows, key=lambda row: (row[2] * 100 // max(row[1], 1), -row[1]))[:12]:
        print(f"  {package:28s} {count:3d} / {covered:3d}")

    print(f"\nлогика без тестов ({len(untested_testable)}):")
    for name in (untested_testable if args.detail else untested_testable[:15]):
        print(f"  {name}")
    if not args.detail and len(untested_testable) > 15:
        print(f"  … ещё {len(untested_testable) - 15} (--detail)")

    print(f"\nAndroid-связанные без тестов ({len(untested_android)}): UI, Hilt-модули, сервисы — "
          "проверяются Robolectric-тестами и вручную (docs/MANUAL_TESTING.md)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
