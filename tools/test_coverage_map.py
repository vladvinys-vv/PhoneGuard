#!/usr/bin/env python3
"""Карта упоминаний классов приложения в тестах.

Это **не** процент покрытия и не замена JaCoCo: скрипт отвечает на вопрос «есть ли вообще
тест, который трогает этот класс», и показывает, где тестов нет совсем. Так видно, куда
писать тесты, не запуская Gradle (в песочнице без Google Maven он не работает).

Границы честные: упоминание класса в тесте не значит, что покрыты все ветки, а отсутствие
упоминания не всегда пробел — UI, Hilt-модули и сервисы тестируются иначе (Robolectric,
ручные сценарии `docs/MANUAL_TESTING.md`).

Второе число — **выполняемые** тесты. Часть тест-файлов в офлайн-прогоне не исполняется
(MockK, Robolectric, Android-сервисы), и считать их покрытием — самообман: класс, который
упомянут только там, на деле не проверяется ни разу. Поэтому карта печатает два числа:
«упомянуты в тестах» и «из них в выполняемых тестах», а разницу показывает списком.

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


def runnable_test_files() -> tuple[list[Path], list[tuple[Path, str]]]:
    """Тест-файлы, которые JVM-раннер действительно исполнит, и причины пропуска остальных.

    Используется тот же отбор, что в `tools/jvm_unit_test.py` (нет Android/Robolectric/MockK),
    поэтому числа карты и прогона не расходятся.
    """
    import importlib.util

    spec = importlib.util.spec_from_file_location(
        "jvm_unit_test", Path(__file__).resolve().with_name("jvm_unit_test.py")
    )
    if spec is None or spec.loader is None:  # pragma: no cover — сломанная установка
        raise RuntimeError("не удалось загрузить tools/jvm_unit_test.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    index = module.class_index()
    runnable: list[Path] = []
    skipped: list[tuple[Path, str]] = []
    for test in sorted(module.TEST.rglob("*Test.kt")):
        reason = module.blocking_import(test)
        if reason:
            skipped.append((test, f"импорт {reason}"))
            continue
        _, reason = module.resolve_sources(test, index)
        if reason:
            skipped.append((test, reason))
            continue
        runnable.append(test)
    return runnable, skipped


def test_sources() -> str:
    return "\n".join(path.read_text(encoding="utf-8") for root in TESTS if root.exists() for path in root.rglob("*.kt"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--detail", action="store_true", help="показать все классы без тестов")
    args = parser.parse_args()

    sources = test_sources()
    packages = classes_by_package()

    try:
        runnable, skipped = runnable_test_files()
        runnable_sources = "\n".join(path.read_text(encoding="utf-8") for path in runnable)
        runnable_skipped_reason: str | None = None
    except Exception as error:  # noqa: BLE001 — карта не должна падать из-за раннера
        runnable_sources = ""
        skipped = []
        runnable_skipped_reason = str(error)

    total = tested = tested_running = 0
    untested_testable: list[str] = []
    untested_android: list[str] = []
    rows: list[tuple[str, int, int]] = []

    for package, classes in sorted(packages.items()):
        covered = [c for c in classes if re.search(rf"\b{re.escape(c)}\b", sources)]
        running = [c for c in covered if re.search(rf"\b{re.escape(c)}\b", runnable_sources)]
        total += len(classes)
        tested += len(covered)
        tested_running += len(running)
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
    if runnable_skipped_reason:
        print(f"из них в выполняемых тестах: неизвестно ({runnable_skipped_reason})")
    else:
        print(f"из них в выполняемых тестах: {tested_running} "
              f"({tested_running * 100 // max(total, 1)}%); "
              f"тест-файлов выполняется {len(runnable)}, пропущено {len(skipped)}")
    print("\nпо пакетам (классов / с тестами):")
    for package, count, covered in sorted(rows, key=lambda row: (row[2] * 100 // max(row[1], 1), -row[1]))[:12]:
        print(f"  {package:28s} {count:3d} / {covered:3d}")

    print(f"\nлогика без тестов ({len(untested_testable)}):")
    for name in (untested_testable if args.detail else untested_testable[:15]):
        print(f"  {name}")
    if not args.detail and len(untested_testable) > 15:
        print(f"  … ещё {len(untested_testable) - 15} (--detail)")

    if not runnable_skipped_reason:
        covered_by_skipped = sorted(
            f"{package}.{name}" if package else name
            for package, classes in packages.items()
            for name in classes
            if re.search(rf"\b{re.escape(name)}\b", sources)
            and not re.search(rf"\b{re.escape(name)}\b", runnable_sources)
        )
        # Разделяем по природе класса, а не по «жалко/не жалко»: ViewModel и экраны требуют
        # Robolectric и в JVM не поедут никогда, а модель/логику можно оживить фейками.
        robolectric = [name for name in covered_by_skipped
                       if name.split(".")[0] == "ui" or name.startswith("ui.")]
        revivable = [name for name in covered_by_skipped if name not in robolectric]
        print(f"\nпокрыты только не выполняющимися тестами ({len(covered_by_skipped)}):")
        print(f"  только Robolectric/UI — {len(robolectric)}, оживить можно фейками — {len(revivable)}")
        for name in (revivable if args.detail else revivable[:10]):
            print(f"  {name}")
        if not args.detail and len(revivable) > 10:
            print(f"  … ещё {len(revivable) - 10} (--detail)")
        if args.detail:
            print("  --- только Robolectric ---")
            for name in robolectric:
                print(f"  {name}")

    print(f"\nAndroid-связанные без тестов ({len(untested_android)}): UI, Hilt-модули, сервисы — "
          "проверяются Robolectric-тестами и вручную (docs/MANUAL_TESTING.md)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
