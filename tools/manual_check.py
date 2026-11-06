#!/usr/bin/env python3
"""Выполняет проверяемую часть ручного чек-листа (`docs/MANUAL_TESTING.md`, раздел 13).

Зачем: раздел 13 состоит из команд `adb`, которые человек выполняет руками — и делает это
по-разному. Один смотрит на `zipalign` без `-P 16`, второй забывает `--print-certs`, третий
проверяет `mapping.txt` в каталоге, которого у проекта с флейворами нет. Инструкция
превращается в «вроде проверял». Скрипт делает механическую часть сам и **печатает ровно ту
команду**, которую выполнил, чтобы результат можно было повторить.

Проверки делятся на два вида:

* **артефакты сборки** (без устройства): 16 КБ страницы памяти (`zipalign -c -P 16`), подпись
  (`apksigner verify`), `mapping.txt` нужного флейвора и правила R8 по нему;
* **устройство** (`--device`): подключено ли оно, установлено ли приложение, какая версия
  Android, какие разрешения выданы и какие каналы уведомлений существуют.

Ничего не меняется без явного флага: `--revoke ПРАВО` — единственная операция, которая трогает
состояние телефона. Отсутствие `zipalign`/`apksigner`/`adb` — это **пропуск**, а не ошибка:
скрипт должен работать и на машине без Android SDK.

    python3 tools/manual_check.py                    # только артефакты
    python3 tools/manual_check.py --device emulator-5554
    python3 tools/manual_check.py --device --revoke android.permission.CAMERA
    python3 tools/manual_check.py --self-test
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APK_GLOBS = (
    "app/build/outputs/apk/production/release/*.apk",
    "app/build/outputs/apk/production/debug/*.apk",
)
MAPPING_GLOB = "app/build/outputs/mapping/*Release/mapping.txt"
DEFAULT_PACKAGE = "com.phoneguard"

PERMISSION_LINE = re.compile(r"^\s+(android\.permission\.[\w.]+):\s+granted=(true|false)")
CHANNEL_LINE = re.compile(r"NotificationChannel\{[^}]*mId='([^']+)'")

# Теги, по которым видно работу жалобы в BNetzA и блокировки SMS (раздел 13.3).
LOG_TAGS = ("Bnetza:V", "PhoneGuardEvents:V", "SmsSpamReceiver:V")

# Канал «опасного соседа» (раздел 13.5). Значение из `NotificationChannels.Spec.NEIGHBOUR`;
# менять его нельзя: вместе с id теряются пользовательские настройки канала.
NEIGHBOUR_CHANNEL = "protect_neighbour_channel"

# Полный номер в формате E.164. Приложение маскирует его как `+49170123••••`, поэтому цифровой
# хвост длиной 10+ символов в логе означает, что маска не применилась.
FULL_NUMBER = re.compile(r"\+\d{10,15}")

OK, SKIP, FAIL = "ok", "skip", "fail"


@dataclass
class Result:
    name: str
    status: str
    detail: str
    command: str = ""


def newest(paths: list[Path]) -> Path | None:
    existing = [path for path in paths if path.is_file()]
    return max(existing, key=lambda path: path.stat().st_mtime) if existing else None


def find_apk(root: Path) -> Path | None:
    candidates: list[Path] = []
    for pattern in APK_GLOBS:
        candidates += list(root.glob(pattern))
    return newest(candidates)


def find_mapping(root: Path) -> Path | None:
    return newest(list(root.glob(MAPPING_GLOB)))


def find_sdk_tool(name: str, env_var: str) -> str | None:
    """`zipalign`/`apksigner` из build-tools: сначала $VAR, потом PATH, потом самый новый SDK."""
    explicit = os.environ.get(env_var)
    if explicit and Path(explicit).is_file():
        return explicit
    on_path = shutil.which(name)
    if on_path:
        return on_path
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(var)
        if not root:
            continue
        build_tools = Path(root) / "build-tools"
        if not build_tools.is_dir():
            continue
        # Числовая сортировка: `35.0.0` не должен проигрывать `9.0.0` из-за строкового порядка.
        versions = []
        for candidate in build_tools.iterdir():
            try:
                key = tuple(int(part) for part in candidate.name.split(".") if part.isdigit())
            except ValueError:
                continue
            if key:
                versions.append((key, candidate))
        for _, directory in sorted(versions, reverse=True):
            tool = directory / name
            if tool.is_file():
                return str(tool)
    return None


def parse_permissions(dumpsys: str) -> dict[str, bool]:
    """`android.permission.X: granted=true` из `dumpsys package <pkg>`."""
    found: dict[str, bool] = {}
    for line in dumpsys.splitlines():
        match = PERMISSION_LINE.match(line)
        if match:
            found[match.group(1)] = match.group(2) == "true"
    return found


def parse_api_level(prop: str) -> int | None:
    match = re.search(r"\d+", prop)
    return int(match.group(0)) if match else None


def parse_devices(output: str) -> list[str]:
    """Только строки `<serial>\\tdevice`: `offline` и `unauthorized` — не рабочие устройства."""
    devices = []
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device" and not line.startswith("List of"):
            devices.append(parts[0])
    return devices


def parse_channels(dumpsys_notification: str, package: str) -> list[str]:
    """Идентификаторы каналов уведомлений приложения из `dumpsys notification --noredact`."""
    channels: list[str] = []
    for line in dumpsys_notification.splitlines():
        if package not in line:
            continue
        for match in CHANNEL_LINE.finditer(line):
            if match.group(1) not in channels:
                channels.append(match.group(1))
    return channels


def unmasked_numbers(log_text: str, limit: int = 3) -> list[str]:
    """Строки лога, где номер не замаскирован (полный E.164 вместо `+4917…••••`)."""
    leaks = []
    for line in log_text.splitlines():
        match = FULL_NUMBER.search(line)
        if match:
            leaks.append(f"{match.group(0)} в строке: {line.strip()[:120]}")
            if len(leaks) >= limit:
                break
    return leaks


def neighbour_notifications(dumpsys_notification: str, package: str) -> list[str]:
    """Уведомления приложения в канале «опасного соседа» (их текст).

    `dumpsys notification --noredact` печатает записи блоками; канал указан в строке
    `channel=NotificationChannel{…mId='protect_neighbour_channel'}`. Строка без нужного
    канала к «соседу» не относится, даже если пакет в ней есть.
    """
    found: list[str] = []
    for line in dumpsys_notification.splitlines():
        if package not in line or NEIGHBOUR_CHANNEL not in line:
            continue
        text = re.sub(r"\s+", " ", line).strip()
        found.append(text[:200])
    return found


def render_report(results: list[Result]) -> str:
    """Markdown-отчёт: что проверено, чем и с каким итогом."""
    code, passed, skipped, failed = summarize(results)
    lines = [
        "# Отчёт ручной проверки",
        "",
        f"Итог: {passed} ok, {skipped} skipped, {failed} failed.",
        "",
        "| Проверка | Итог | Подробности | Команда |",
        "| --- | --- | --- | --- |",
    ]
    for result in results:
        detail = result.detail.replace("|", "\\|").replace("\n", " ")[:200]
        command = result.command.replace("|", "\\|")
        lines.append(f"| {result.name} | {result.status} | {detail} | `{command}` |")
    lines.append("")
    lines.append(
        "Проверки со статусом `skip` не выполнялись (нет APK, SDK, `adb` или нужен "
        "интерфейс приложения) — это не провал, но и не подтверждение."
    )
    del code
    return "\n".join(lines) + "\n"


def verdict_of_zipalign(returncode: int, output: str) -> str:
    """`-c` не меняет APK: 0 и слово `Verification succesful` (так печатает zipalign)."""
    if returncode == 0 and "Verification succesful" in output:
        return OK
    return FAIL


def run_command(command: list[str], timeout: int = 120) -> tuple[int, str]:
    try:
        completed = subprocess.run(
            command, capture_output=True, text=True, check=False, timeout=timeout
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return 127, str(error)
    return completed.returncode, completed.stdout + completed.stderr


def check_artifacts(root: Path) -> list[Result]:
    results: list[Result] = []
    apk = find_apk(root)
    if apk is None:
        results.append(Result(
            "APK собран",
            SKIP,
            "нет app/build/outputs/apk/production/* — соберите ./build_release.sh или "
            "./gradlew assembleProductionDebug",
        ))
    else:
        zipalign = find_sdk_tool("zipalign", "ZIPALIGN")
        if zipalign is None:
            results.append(Result("16 КБ страницы памяти", SKIP, "zipalign не найден"))
        else:
            command = [zipalign, "-c", "-P", "16", "-v", "4", str(apk)]
            code, output = run_command(command)
            status = verdict_of_zipalign(code, output)
            detail = "все несжатые .so выровнены по 16 КБ" if status == OK else output.strip().splitlines()[-1:]
            results.append(Result("16 КБ страницы памяти", status, str(detail), " ".join(command)))

        apksigner = find_sdk_tool("apksigner", "APKSIGNER")
        if apksigner is None:
            results.append(Result("Подпись APK", SKIP, "apksigner не найден"))
        else:
            command = [apksigner, "verify", "--print-certs", str(apk)]
            code, output = run_command(command)
            detail = ""
            match = re.search(r"SHA-256 digest: (\w+)", output)
            if match:
                detail = f"SHA-256 {match.group(1)[:16]}…"
            if code != 0 and "does not verify" in output:
                detail = "APK не подписан или подпись не сходится"
            results.append(Result("Подпись APK", OK if code == 0 else FAIL, detail, " ".join(command)))

    mapping = find_mapping(root)
    if mapping is None:
        results.append(Result(
            "mapping.txt релиза",
            SKIP,
            f"нет {MAPPING_GLOB} — mapping появляется только у релизной сборки",
        ))
    else:
        command = [sys.executable, "tools/proguard_check.py", "--mapping", str(mapping)]
        code, output = run_command(command, timeout=300)
        results.append(Result(
            "mapping.txt релиза",
            OK if code == 0 else FAIL,
            f"{mapping.relative_to(root)}: " + output.strip().splitlines()[-1] if output.strip() else "",
            " ".join(command),
        ))
    return results


def adb_command(device: str | None, *args: str) -> list[str]:
    return ["adb"] + (["-s", device] if device else []) + list(args)


def check_device(
    device: str | None,
    package: str,
    revoke: str | None,
    logcat: Path | None = None,
    neighbour: bool = False,
) -> list[Result]:
    results: list[Result] = []
    if shutil.which("adb") is None:
        results.append(Result("Устройство", SKIP, "adb не найден (нужен Android platform-tools)"))
        return results

    code, output = run_command(["adb", "devices"])
    devices = parse_devices(output)
    if not devices:
        results.append(Result("Устройство", SKIP, "нет подключённых устройств (adb devices пуст)"))
        return results
    chosen = device or devices[0]
    results.append(Result("Устройство", OK, f"{chosen} (доступно: {', '.join(devices)})", "adb devices"))

    code, output = run_command(adb_command(chosen, "shell", "getprop", "ro.build.version.sdk"))
    api = parse_api_level(output)
    if api is None:
        results.append(Result("Версия Android", SKIP, "getprop вернул пусто"))
    elif api < 26:
        results.append(Result("Версия Android", FAIL, f"API {api} ниже minSdk 26", "adb shell getprop ro.build.version.sdk"))
    else:
        results.append(Result("Версия Android", OK, f"API {api}", "adb shell getprop ro.build.version.sdk"))

    code, output = run_command(adb_command(chosen, "shell", "pm", "list", "packages", package))
    installed = f"package:{package}" in output
    results.append(Result(
        "Приложение установлено",
        OK if installed else SKIP,
        f"{package} найден" if installed else f"{package} не установлен — сценарии 13.3–13.5 неприменимы",
        f"adb shell pm list packages {package}",
    ))
    if not installed:
        return results

    code, output = run_command(adb_command(chosen, "shell", "dumpsys", "package", package))
    permissions = parse_permissions(output)
    if not permissions:
        results.append(Result("Разрешения", SKIP, "dumpsys не вернул runtime-разрешений"))
    else:
        granted = sorted(name.rsplit(".", 1)[-1] for name, value in permissions.items() if value)
        denied = sorted(name.rsplit(".", 1)[-1] for name, value in permissions.items() if not value)
        # Отказ — не ошибка сама по себе: приложение обязано это показать (сценарий 13.4).
        results.append(Result(
            "Разрешения",
            OK,
            f"выданы: {', '.join(granted) or '—'}; отозваны: {', '.join(denied) or '—'}",
            f"adb shell dumpsys package {package}",
        ))

    code, output = run_command(adb_command(chosen, "shell", "dumpsys", "notification", "--noredact"))
    channels = parse_channels(output, package)
    results.append(Result(
        "Каналы уведомлений",
        OK if channels else SKIP,
        f"{len(channels)}: {', '.join(channels)}" if channels else "приложение ещё не создавало каналов",
        f"adb shell dumpsys notification --noredact | grep {package}",
    ))

    if revoke:
        command = adb_command(chosen, "shell", "pm", "revoke", package, revoke)
        code, output = run_command(command)
        results.append(Result(
            f"Отзыв {revoke.rsplit('.', 1)[-1]}",
            OK if code == 0 else FAIL,
            "открыт дашборд не должен молчать: ожидается карточка с кнопкой «Открыть настройки»",
            " ".join(command),
        ))
    else:
        results.append(Result(
            "Сценарий 13.4 (отзыв разрешения)",
            SKIP,
            f"выполняется явно: python3 tools/manual_check.py --device {chosen} --revoke "
            f"android.permission.CAMERA",
        ))
    if logcat is not None:
        command = adb_command(chosen, "shell", "logcat", "-d", "-s", *LOG_TAGS)
        code, output = run_command(command, timeout=180)
        try:
            logcat.write_text(output, encoding="utf-8")
            note = f"лог записан в {logcat}"
        except OSError as error:
            results.append(Result("Жалоба BNetzA (лог)", FAIL, f"не удалось записать {logcat}: {error}"))
            return results
        leaks = unmasked_numbers(output)
        if code != 0 and not output.strip():
            results.append(Result("Жалоба BNetzA (лог)", SKIP, f"{note}, но logcat вернул пусто"))
        elif leaks:
            results.append(Result(
                "Жалоба BNetzA (лог)",
                FAIL,
                f"{note}; номер не замаскирован: " + leaks[0],
                " ".join(command),
            ))
        else:
            results.append(Result(
                "Жалоба BNetzA (лог)",
                OK,
                f"{note}; полных номеров в логе нет (только маска)",
                " ".join(command),
            ))
    else:
        results.append(Result(
            "Жалоба BNetzA (лог)",
            SKIP,
            f"сбор лога не запрошен: --logcat /tmp/phoneguard.log "
            f"(adb logcat -s {' '.join(LOG_TAGS)})",
        ))

    if neighbour:
        code, output = run_command(
            adb_command(chosen, "shell", "dumpsys", "notification", "--noredact")
        )
        # Свежий dumpsys уже есть выше, но канал «соседа» мог появиться после установки
        # тестового пакета — читаем заново, чтобы результат не зависел от порядка проверок.
        found = neighbour_notifications(output, package)
        if found:
            results.append(Result(
                "Опасный сосед (уведомление)",
                OK,
                f"{len(found)}: {found[0]}",
                f"adb shell dumpsys notification --noredact | grep {NEIGHBOUR_CHANNEL}",
            ))
        else:
            results.append(Result(
                "Опасный сосед (уведомление)",
                SKIP,
                f"в канале {NEIGHBOUR_CHANNEL} уведомлений нет — установите тестовый пакет "
                "с правом оверлея и повторите",
            ))
    else:
        results.append(Result(
            "Опасный сосед (уведомление)",
            SKIP,
            "не запрошено: --neighbour (нужно установить тестовый пакет с оверлеем)",
        ))
    return results


def summarize(results: list[Result]) -> tuple[int, int, int, int]:
    """(код возврата, ok, skip, fail). Пропуск не делает прогон красным."""
    failed = sum(1 for result in results if result.status == FAIL)
    passed = sum(1 for result in results if result.status == OK)
    skipped = sum(1 for result in results if result.status == SKIP)
    return (1 if failed else 0), passed, skipped, failed


def print_results(results: list[Result], verbose: bool) -> None:
    marks = {OK: "  ok  ", SKIP: " skip ", FAIL: " fail "}
    for result in results:
        print(f"[{marks[result.status]}] {result.name}: {result.detail}")
        if verbose and result.command:
            print(f"           $ {result.command}")


def self_test() -> int:
    problems: list[str] = []

    def check(label: str, condition: bool) -> None:
        if not condition:
            problems.append(label)

    dumpsys = """
    requested permissions:
      android.permission.CAMERA
    runtime permissions:
      android.permission.CAMERA: granted=true, flags=[ USER_SET ]
      android.permission.SEND_SMS: granted=false, flags=[ USER_SET ]
      android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SET ]
"""
    permissions = parse_permissions(dumpsys)
    check("granted=true разобран", permissions.get("android.permission.CAMERA") is True)
    check("granted=false разобран", permissions.get("android.permission.SEND_SMS") is False)
    check("всего три разрешения", len(permissions) == 3)

    check("уровень API из getprop", parse_api_level("35\n") == 35)
    check("пустой getprop — None", parse_api_level("\n") is None)

    check("zipalign 0 + Verification succesful — ok",
          verdict_of_zipalign(0, "Verification succesful (0.001s)") == OK)
    check("zipalign с BAD — fail", verdict_of_zipalign(1, "BAD - 4-byte alignment") == FAIL)
    check("zipalign без слова об успехе — fail", verdict_of_zipalign(0, "") == FAIL)

    devices = parse_devices(
        "List of devices attached\n"
        "emulator-5554\tdevice\n"
        "R58M1234\tunauthorized\n"
        "deadbeef\toffline\n"
    )
    check("только подключённые устройства", devices == ["emulator-5554"])
    check("пустой список", parse_devices("List of devices attached\n\n") == [])

    notification = (
        "  NotificationRecord{1 a=com.phoneguard} channel=NotificationChannel{abc mId='pg_alerts'}\n"
        "  NotificationRecord{2 a=com.other} channel=NotificationChannel{def mId='other_channel'}\n"
    )
    check("канал приложения найден", parse_channels(notification, "com.phoneguard") == ["pg_alerts"])
    check("чужой канал не приписан", "other_channel" not in parse_channels(notification, "com.phoneguard"))

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        apk_dir = root / "app/build/outputs/apk/production/debug"
        apk_dir.mkdir(parents=True)
        old_apk = apk_dir / "old.apk"
        new_apk = apk_dir / "new.apk"
        old_apk.write_bytes(b"a")
        new_apk.write_bytes(b"b")
        os.utime(old_apk, (1_000_000, 1_000_000))
        os.utime(new_apk, (2_000_000, 2_000_000))
        check("выбирается свежий APK", find_apk(root) == new_apk)
        check("mapping нет — None", find_mapping(root) is None)

        mapping_dir = root / "app/build/outputs/mapping/productionRelease"
        mapping_dir.mkdir(parents=True)
        (mapping_dir / "mapping.txt").write_text("com.phoneguard.Main -> a:\n", encoding="utf-8")
        check("mapping флейвора найден", find_mapping(root) is not None)

    masked_log = (
        "D/BnetzaReport: жалоба для +49170123•••• отправлена\n"
        "D/PhoneGuardEvents: blocked call from +49170123••••\n"
    )
    check("маскированный номер не считается утечкой", unmasked_numbers(masked_log) == [])
    leaked_log = "D/BnetzaReport: complaint for +491701234567 prepared\n"
    check("полный номер в логе найден", len(unmasked_numbers(leaked_log)) == 1)
    check("в описании утечки видно сам номер",
          any("+491701234567" in line for line in unmasked_numbers(leaked_log)))
    check("несколько утечек ограничены лимитом",
          len(unmasked_numbers("\n".join(["+49170123456"] * 10), limit=3)) == 3)

    notification_dump = (
        "  NotificationRecord{1 a=com.phoneguard} channel=NotificationChannel{a "
        "mId='protect_neighbour_channel'}\n"
        "  NotificationRecord{2 a=com.phoneguard} channel=NotificationChannel{b "
        "mId='call_blocker_channel'}\n"
        "  NotificationRecord{3 a=com.other} channel=NotificationChannel{c "
        "mId='protect_neighbour_channel'}\n"
    )
    found = neighbour_notifications(notification_dump, "com.phoneguard")
    check("уведомление соседа найдено", len(found) == 1)
    check("чужой пакет не приписан", all("com.other" not in line for line in found))
    check("другой канал не считается соседом",
          len(neighbour_notifications("a=com.phoneguard mId='call_blocker_channel'", "com.phoneguard")) == 0)

    report = render_report([Result("Проверка", OK, "всё хорошо", "adb devices"),
                            Result("Другая", SKIP, "нет устройства")])
    check("в отчёте есть заголовок", report.startswith("# Отчёт ручной проверки"))
    check("в отчёте есть строки проверок", "| Проверка | ok |" in report and "| Другая | skip |" in report)
    check("в отчёте есть итог", "1 ok, 1 skipped, 0 failed" in report)

    results = [
        Result("a", OK, ""),
        Result("b", SKIP, ""),
        Result("c", FAIL, ""),
        Result("d", OK, ""),
    ]
    code, passed, skipped, failed = summarize(results)
    check("итог: код 1 при провале", code == 1)
    check("итог: 2 ok, 1 skip, 1 fail", (passed, skipped, failed) == (2, 1, 1))
    check("только пропуски — зелёно",
          summarize([Result("a", SKIP, "")])[0] == 0)

    if problems:
        for problem in problems:
            print(f"error: manual_check --self-test: {problem}")
        return 1
    print(
        "manual_check --self-test: ok (разрешения, версия API, zipalign, adb devices, "
        "каналы уведомлений, выбор свежего APK и mapping, утечка номера в логе, "
        "уведомление соседа, markdown-отчёт, итоговая сводка)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", nargs="?", const="", default=None,
                        help="серийный номер устройства или пусто — первое подключённое")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help="applicationId приложения")
    parser.add_argument("--revoke", default=None,
                        help="отозвать разрешение через adb (единственная изменяющая операция)")
    parser.add_argument("--logcat", type=Path, default=None,
                        help="снять logcat по тегам Bnetza/PhoneGuardEvents/SmsSpamReceiver в файл")
    parser.add_argument("--neighbour", action="store_true",
                        help="проверить уведомление об опасном соседе в dumpsys notification")
    parser.add_argument("--report", type=Path, default=None,
                        help="записать markdown-отчёт о проверках")
    parser.add_argument("--verbose", action="store_true", help="показать выполненные команды")
    parser.add_argument("--self-test", action="store_true", help="проверить правила на синтетике")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    results = check_artifacts(ROOT)
    if args.device is not None:
        results += check_device(
            args.device or None, args.package, args.revoke, args.logcat, args.neighbour
        )
    else:
        results.append(Result(
            "Устройство",
            SKIP,
            "проверки устройства не запрошены: добавьте --device [серийный номер]",
        ))

    print_results(results, args.verbose)
    if args.report is not None:
        try:
            args.report.write_text(render_report(results), encoding="utf-8")
            print(f"отчёт: {args.report}")
        except OSError as error:
            print(f"error: не удалось записать отчёт {args.report}: {error}")
            return 1
    code, passed, skipped, failed = summarize(results)
    print(
        f"\n{passed} ok, {skipped} skipped, {failed} failed: артефакты сборки"
        + ("" if args.device is None else " и устройство")
        + ("; подробные команды — с --verbose" if not args.verbose else "")
    )
    return code


if __name__ == "__main__":
    sys.exit(main())
