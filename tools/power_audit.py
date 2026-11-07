#!/usr/bin/env python3
"""Ищет то, что расходует батарею: тихие будильники, точные тревоги, вечные wake-lock'и.

Зачем отдельный инструмент: эти ошибки не ломают ни сборку, ни тесты, и их не видно в коде
рядом. Три самые обидные:

* **Периодическая работа WorkManager чаще 15 минут.** WorkManager молча поднимает интервал до
  15 минут, поэтому `PeriodicWorkRequestBuilder(5, MINUTES)` — это не «каждые 5 минут», а
  «каждые 15», и настройка в интерфейсе врёт пользователю.
* **Точный будильник** (`setExact`, `setExactAndAllowWhileIdle`, `setAlarmClock`) будит телефон
  вопреки режиму энергосбережения — оправдан только там, где иначе теряется смысл функции, и
  такой случай должен быть записан с причиной.
* **`WakeLock.acquire()` без таймаута**: если код упадёт между `acquire()` и `release()`,
  телефон останется бодрствовать до перезагрузки — а виноватым будет «PhoneGuard жрёт батарею».

Ещё проверяется приёмник, зарегистрированный без `unregisterReceiver`: он держит контекст
(активность — целиком). `registerReceiver(null, filter)` — это синхронное чтение sticky-интента,
а не регистрация, и ложной ошибкой не считается.

    python3 tools/power_audit.py
    python3 tools/power_audit.py --verbose
    python3 tools/power_audit.py --self-test

Исключения — `tools/power_audit_allow.txt` строкой `файл: правило — причина`; запись без причины
и устаревшая запись (правило больше не срабатывает) — ошибки.
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE_ROOT = ROOT / "app/src/main/java"
ALLOWLIST = ROOT / "tools/power_audit_allow.txt"

# WorkManager молча поднимает любой интервал меньше 15 минут (`MIN_PERIODIC_INTERVAL_MILLIS`).
WORK_MIN_INTERVAL_MS = 15 * 60 * 1000

PERIODIC = re.compile(
    r"PeriodicWorkRequestBuilder\s*<\s*\w+\s*>\s*\(\s*(\d+)\s*,\s*TimeUnit\.(\w+)"
)
TIME_UNITS = {
    "MILLISECONDS": 1,
    "SECONDS": 1000,
    "MINUTES": 60 * 1000,
    "HOURS": 60 * 60 * 1000,
    "DAYS": 24 * 60 * 60 * 1000,
}
EXACT_ALARM = re.compile(r"\.(setExactAndAllowWhileIdle|setExact|setAlarmClock|setRepeating)\s*\(")
WAKE_LOCK = re.compile(r"\.acquire\s*\(\s*\)|\.acquire\s*\(\s*-1")
REGISTER = re.compile(r"\.registerReceiver\s*\(\s*([^,]+),")
UNREGISTER = re.compile(r"\.unregisterReceiver\s*\(")
PACKAGE_ENUM = re.compile(r"getInstalled(?:Packages|Applications)\s*\(")


def interval_ms(unit: str) -> int:
    return TIME_UNITS.get(unit.upper(), 1)


def check_text(label: str, text: str) -> list[tuple[str, str]]:
    """`[(правило, сообщение)]` для одного файла: правила зависят от текста, а не от пути."""
    problems: list[tuple[str, str]] = []

    for match in PERIODIC.finditer(text):
        amount = int(match.group(1))
        unit = match.group(2)
        total = amount * interval_ms(unit)
        if total < WORK_MIN_INTERVAL_MS:
            problems.append((
                "periodic",
                f"{label}: периодическая работа `{amount} {unit}` — WorkManager поднимет "
                f"интервал до 15 минут, настройка будет врать пользователю",
            ))

    for match in EXACT_ALARM.finditer(text):
        problems.append((
            "exact-alarm",
            f"{label}: `{match.group(1)}` будит телефон в обход энергосбережения — "
            "нужна причина в allow-файле",
        ))

    if WAKE_LOCK.search(text):
        problems.append((
            "wakelock",
            f"{label}: `acquire()` без таймаута — при падении между acquire/release "
            "телефон не уснёт до перезагрузки",
        ))

    registered = [m for m in REGISTER.finditer(text) if m.group(1).strip() != "null"]
    if registered and not UNREGISTER.search(text):
        problems.append((
            "receiver-leak",
            f"{label}: `registerReceiver` без `unregisterReceiver` — приёмник держит контекст",
        ))
    return problems


def scan(root: Path) -> dict[str, str]:
    source_root = root / "app/src/main/java"
    if not source_root.exists():
        return {}
    return {
        path.relative_to(root).as_posix(): path.read_text(encoding="utf-8", errors="ignore")
        for path in sorted(source_root.rglob("*.kt"))
    }


def read_allowlist(path: Path) -> dict[str, str]:
    allowed: dict[str, str] = {}
    if not path.exists():
        return allowed
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        for separator in ("—", "  "):
            if separator in stripped:
                key, reason = stripped.split(separator, 1)
                break
        else:
            key, reason = stripped, ""
        allowed[key.strip()] = reason.strip()
    return allowed


def audit(root: Path, allowed: dict[str, str]) -> tuple[list[str], int, int]:
    """(ошибки, сколько файлов проверено, сколько перечислений пакетов)."""
    files = scan(root)
    errors: list[str] = []
    hits: set[str] = set()
    enumerations = 0
    for label, text in files.items():
        enumerations += len(PACKAGE_ENUM.findall(text))
        for rule, message in check_text(label, text):
            key = f"{label}:{rule}"
            hits.add(key)
            if key in allowed:
                continue
            errors.append(message + f" (правило `{rule}`)")
    for key, reason in allowed.items():
        if not reason:
            errors.append(f"tools/power_audit_allow.txt: у `{key}` нет причины")
        if key not in hits:
            errors.append(
                f"tools/power_audit_allow.txt: `{key}` больше не срабатывает — запись устарела"
            )
    return errors, len(files), enumerations


def self_test() -> int:
    problems: list[str] = []

    def check(label: str, condition: bool) -> None:
        if not condition:
            problems.append(label)

    def rules(text: str) -> list[str]:
        return [rule for rule, _ in check_text("x.kt", text)]

    check("работа раз в неделю — норма",
          rules("PeriodicWorkRequestBuilder<ScanWorker>(7, TimeUnit.DAYS)") == [])
    check("работа раз в 15 минут — норма",
          rules("PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES)") == [])
    check("работа раз в 5 минут поймана",
          rules("PeriodicWorkRequestBuilder<ScanWorker>(5, TimeUnit.MINUTES)") == ["periodic"])
    check("работа в секундах тоже считается",
          rules("PeriodicWorkRequestBuilder<ScanWorker>(600, TimeUnit.SECONDS)") == ["periodic"])
    check("ровно 15 минут в секундах — норма",
          rules("PeriodicWorkRequestBuilder<ScanWorker>(900, TimeUnit.SECONDS)") == [])
    check("точный будильник пойман",
          rules("alarmManager.setExactAndAllowWhileIdle(1, at, pi)") == ["exact-alarm"])
    check("повторяющийся будильник пойман",
          rules("alarmManager.setRepeating(0, 0, 60000, pi)") == ["exact-alarm"])
    check("wake-lock без таймаута пойман", rules("lock.acquire()") == ["wakelock"])
    check("wake-lock с таймаутом не считается", rules("lock.acquire(10_000)") == [])
    check("wake-lock с -1 пойман", rules("lock.acquire(-1)") == ["wakelock"])
    check("приёмник без unregister пойман",
          rules("context.registerReceiver(receiver, filter)") == ["receiver-leak"])
    check("sticky-чтение без регистрации не считается",
          rules("context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))") == [])
    check("регистрация с unregister не считается",
          rules("context.registerReceiver(r, f)\ncontext.unregisterReceiver(r)") == [])
    check("пустой файл без замечаний", rules("class A") == [])

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        sources = root / "app/src/main/java/com/example"
        sources.mkdir(parents=True)
        (sources / "Bad.kt").write_text(
            "class Bad {\n"
            "    fun start() { alarmManager.setExactAndAllowWhileIdle(0, 0, pi) }\n"
            "}\n",
            encoding="utf-8",
        )
        (sources / "Fine.kt").write_text(
            "class Fine { fun packages() = pm.getInstalledApplications(0) }\n",
            encoding="utf-8",
        )
        errors, files, enumerations = audit(root, {})
        check("нарушение в файле найдено",
              any("Bad.kt" in error and "exact-alarm" in error for error in errors))
        check("файлы считаются", files == 2)
        check("перечисления пакетов считаются", enumerations == 1)

        key = "app/src/main/java/com/example/Bad.kt:exact-alarm"
        errors, _, _ = audit(root, {key: "будильник для режима потери"})
        check("исключение с причиной снимает ошибку", errors == [])
        errors, _, _ = audit(root, {key: ""})
        check("исключение без причины — ошибка", any("нет причины" in error for error in errors))
        errors, _, _ = audit(root, {"app/src/main/java/com/example/Gone.kt:wakelock": "старое"})
        check("устаревшее исключение — ошибка", any("устарела" in error for error in errors))

    if problems:
        for problem in problems:
            print(f"error: power_audit --self-test: {problem}")
        return 1
    print(
        "power_audit --self-test: ok (интервал WorkManager, точные будильники, wake-lock без "
        "таймаута, registerReceiver без unregister, sticky-чтение, allow-файл)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", action="store_true", help="показать метрики по файлам")
    parser.add_argument("--self-test", action="store_true", help="проверить правила на синтетике")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    errors, files, enumerations = audit(ROOT, read_allowlist(ALLOWLIST))
    if args.verbose:
        for label, text in scan(ROOT).items():
            count = len(PACKAGE_ENUM.findall(text))
            if count:
                print(f"{count} перечислений пакетов — {label}")
    for problem in errors:
        print(f"error: {problem}")
    print(
        f"\n{len(errors)} error(s), 0 warning(s): {files} файлов, "
        f"{enumerations} перечислений пакетов"
    )
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
