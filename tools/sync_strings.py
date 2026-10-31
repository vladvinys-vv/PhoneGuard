#!/usr/bin/env python3
"""Adds (or updates) string resources in every locale file.

Usage:  python3 tools/sync_strings.py

Keeping this in the repository makes it obvious *where* a resource came from and
keeps the four `strings.xml` files in sync (the Android build silently falls back
to `values/` otherwise, and `lintDebug` reports MissingTranslation).
"""
from __future__ import annotations

import sys
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"

# key: (default/ru?, en, ru, de).  `values/` historically holds Russian text.
STRINGS: dict[str, dict[str, str]] = {
    "sms_suspicious_title": {
        "default": "Подозрительное SMS",
        "en": "Suspicious SMS",
        "ru": "Подозрительное SMS",
        "de": "Verdächtige SMS",
    },
    "sms_suspicious_text": {
        "default": "Сообщение от %1$s похоже на фишинг: ссылка и давящие слова",
        "en": "Message from %1$s looks like phishing: a link plus pressure wording",
        "ru": "Сообщение от %1$s похоже на фишинг: ссылка и давящие слова",
        "de": "Nachricht von %1$s sieht nach Phishing aus: Link und Druckformulierungen",
    },
    "sms_blocked_title": {
        "default": "SMS заблокировано",
        "en": "SMS blocked",
        "ru": "SMS заблокировано",
        "de": "SMS blockiert",
    },
    "sms_blocked_text": {
        "default": "Сообщение от %1$s добавлено в журнал блокировок",
        "en": "Message from %1$s added to the block log",
        "ru": "Сообщение от %1$s добавлено в журнал блокировок",
        "de": "Nachricht von %1$s wurde ins Sperrprotokoll aufgenommen",
    },
    "sms_blocking_hint": {
        "default": "SMS из чёрного списка и спам-базы попадают в журнал. Чтобы они не доходили до шторки, назначьте PhoneGuard приложением SMS по умолчанию.",
        "en": "SMS from the blacklist and the spam database are logged. To keep them out of the notification shade, set PhoneGuard as your default SMS app.",
        "ru": "SMS из чёрного списка и спам-базы попадают в журнал. Чтобы они не доходили до шторки, назначьте PhoneGuard приложением SMS по умолчанию.",
        "de": "SMS von Nummern der Sperrliste und aus der Spam-Datenbank werden protokolliert. Damit sie nicht im Benachrichtigungsbereich erscheinen, legen Sie PhoneGuard als Standard-SMS-App fest.",
    },
    "apk_sig_checked_count": {
        "default": "Проверено подписей APK: %1$d",
        "en": "APK signatures checked: %1$d",
        "ru": "Проверено подписей APK: %1$d",
        "de": "Geprüfte APK-Signaturen: %1$d",
    },
    "apk_sig_none_found": {
        "default": "Подозрительных подписей не найдено",
        "en": "No suspicious signatures found",
        "ru": "Подозрительных подписей не найдено",
        "de": "Keine verdächtigen Signaturen gefunden",
    },
    "apk_sig_problems_found": {
        "default": "Найдено проблем с подписями: %1$d",
        "en": "Signature problems found: %1$d",
        "ru": "Найдено проблем с подписями: %1$d",
        "de": "Signaturprobleme gefunden: %1$d",
    },
    "file_scan_start_mediastore": {
        "default": "Сканирование хранилища через MediaStore…",
        "en": "Scanning storage via MediaStore…",
        "ru": "Сканирование хранилища через MediaStore…",
        "de": "Speicher wird über MediaStore durchsucht…",
    },
    "file_scan_start_dcim": {
        "default": "Сканирование DCIM…",
        "en": "Scanning DCIM…",
        "ru": "Сканирование DCIM…",
        "de": "DCIM wird durchsucht…",
    },
    "file_scan_limit_scoped_storage": {
        "default": "— Ограничение: Scoped Storage (Android 10+) ограничивает доступ.",
        "en": "— Limitation: scoped storage (Android 10+) restricts access.",
        "ru": "— Ограничение: Scoped Storage (Android 10+) ограничивает доступ.",
        "de": "— Einschränkung: Scoped Storage (Android 10+) beschränkt den Zugriff.",
    },
    "file_scan_limit_mediastore": {
        "default": "— Сканирование через MediaStore API, приватные директории недоступны.",
        "en": "— Scanning uses the MediaStore API; private directories are not accessible.",
        "ru": "— Сканирование через MediaStore API, приватные директории недоступны.",
        "de": "— Die Suche nutzt die MediaStore-API; private Verzeichnisse sind nicht zugänglich.",
    },
    "patch_detail_unknown_date": {
        "default": "Не удалось определить дату security-патча.",
        "en": "Could not determine the security patch date.",
        "ru": "Не удалось определить дату security-патча.",
        "de": "Das Datum des Sicherheits-Patches konnte nicht ermittelt werden.",
    },
    "patch_detail_unknown_hint": {
        "default": "Это может быть признаком кастомной прошивки или устаревшего устройства.",
        "en": "This may indicate a custom ROM or an outdated device.",
        "ru": "Это может быть признаком кастомной прошивки или устаревшего устройства.",
        "de": "Das kann auf eine Custom-ROM oder ein veraltetes Gerät hindeuten.",
    },
    "patch_detail_unparsable": {
        "default": "Формат даты патча не распознан: %1$s",
        "en": "Unrecognized patch date format: %1$s",
        "ru": "Формат даты патча не распознан: %1$s",
        "de": "Unbekanntes Format des Patch-Datums: %1$s",
    },
    "patch_detail_future": {
        "default": "Дата патча в будущем — проверьте системное время устройства.",
        "en": "The patch date is in the future — check the device clock.",
        "ru": "Дата патча в будущем — проверьте системное время устройства.",
        "de": "Das Patch-Datum liegt in der Zukunft — prüfen Sie die Systemzeit.",
    },
    "patch_detail_date": {
        "default": "Дата security-патча: %1$s",
        "en": "Security patch date: %1$s",
        "ru": "Дата security-патча: %1$s",
        "de": "Datum des Sicherheits-Patches: %1$s",
    },
    "patch_detail_age": {
        "default": "Патчу %1$d мес.",
        "en": "%1$d month(s) since the patch.",
        "ru": "Патчу %1$d мес.",
        "de": "Patch ist %1$d Monat(e) alt.",
    },
    "patch_detail_critical": {
        "default": "⚠ КРИТИЧНО: патч устарел более чем на %1$d мес.",
        "en": "⚠ CRITICAL: the patch is more than %1$d months old.",
        "ru": "⚠ КРИТИЧНО: патч устарел более чем на %1$d мес.",
        "de": "⚠ KRITISCH: Der Patch ist älter als %1$d Monate.",
    },
    "patch_detail_warning": {
        "default": "⚠ Внимание: патч устарел более чем на %1$d мес.",
        "en": "⚠ Warning: the patch is more than %1$d months old.",
        "ru": "⚠ Внимание: патч устарел более чем на %1$d мес.",
        "de": "⚠ Achtung: Der Patch ist älter als %1$d Monate.",
    },
    "patch_detail_fresh": {
        "default": "Патч свежий — хорошо.",
        "en": "The patch is recent — good.",
        "ru": "Патч свежий — хорошо.",
        "de": "Der Patch ist aktuell — gut.",
    },
    "patch_detail_recommendation": {
        "default": "— Рекомендация: установите последнее обновление безопасности Google.",
        "en": "— Recommendation: install the latest Google security update.",
        "ru": "— Рекомендация: установите последнее обновление безопасности Google.",
        "de": "— Empfehlung: Installieren Sie das neueste Google-Sicherheitsupdate.",
    },
    "patch_date_unknown": {
        "default": "неизвестно",
        "en": "unknown",
        "ru": "неизвестно",
        "de": "unbekannt",
    },
    "integrity_verified_boot": {
        "default": "Состояние Verified Boot: %1$s",
        "en": "Verified Boot state: %1$s",
        "ru": "Состояние Verified Boot: %1$s",
        "de": "Verified-Boot-Status: %1$s",
    },
    "integrity_bootloader_locked": {
        "default": "Загрузчик заблокирован",
        "en": "Bootloader is locked",
        "ru": "Загрузчик заблокирован",
        "de": "Bootloader ist gesperrt",
    },
    "integrity_bootloader_unknown": {
        "default": "Состояние загрузчика неизвестно",
        "en": "Bootloader state is unknown",
        "ru": "Состояние загрузчика неизвестно",
        "de": "Bootloader-Status unbekannt",
    },
    "integrity_bootloader_unlocked": {
        "default": "Загрузчик разблокирован (%1$s)",
        "en": "Bootloader is unlocked (%1$s)",
        "ru": "Загрузчик разблокирован (%1$s)",
        "de": "Bootloader ist entsperrt (%1$s)",
    },
    "integrity_verified_boot_unsafe": {
        "default": "⚠ Verified Boot в небезопасном состоянии (%1$s)",
        "en": "⚠ Verified Boot is in an unsafe state (%1$s)",
        "ru": "⚠ Verified Boot в небезопасном состоянии (%1$s)",
        "de": "⚠ Verified Boot in unsicherem Zustand (%1$s)",
    },
    "integrity_bootloader_unlocked_warn": {
        "default": "⚠ Загрузчик разблокирован",
        "en": "⚠ Bootloader is unlocked",
        "ru": "⚠ Загрузчик разблокирован",
        "de": "⚠ Bootloader ist entsperrt",
    },
    "integrity_build_old": {
        "default": "⚠ Прошивке более %1$d лет — возможны уязвимости",
        "en": "⚠ The ROM is more than %1$d years old — vulnerabilities are possible",
        "ru": "⚠ Прошивке более %1$d лет — возможны уязвимости",
        "de": "⚠ Die Firmware ist älter als %1$d Jahre — Schwachstellen möglich",
    },
    "integrity_build_fresh": {
        "default": "Прошивка относительно свежая",
        "en": "The ROM is relatively recent",
        "ru": "Прошивка относительно свежая",
        "de": "Die Firmware ist relativ aktuell",
    },
    "integrity_system_files_found": {
        "default": "⚠ Обнаружены подозрительные системные файлы: %1$s",
        "en": "⚠ Suspicious system files found: %1$s",
        "ru": "⚠ Обнаружены подозрительные системные файлы: %1$s",
        "de": "⚠ Verdächtige Systemdateien gefunden: %1$s",
    },
    "integrity_system_files_none": {
        "default": "Подозрительных системных файлов не найдено",
        "en": "No suspicious system files found",
        "ru": "Подозрительных системных файлов не найдено",
        "de": "Keine verdächtigen Systemdateien gefunden",
    },
    "integrity_debug_build": {
        "default": "⚠ Отладочная сборка (tags: %1$s)",
        "en": "⚠ Debug build (tags: %1$s)",
        "ru": "⚠ Отладочная сборка (tags: %1$s)",
        "de": "⚠ Debug-Build (Tags: %1$s)",
    },
    "integrity_custom_rom": {
        "default": "Обнаружена кастомная прошивка (fingerprint: …%1$s)",
        "en": "Custom ROM detected (fingerprint: …%1$s)",
        "ru": "Обнаружена кастомная прошивка (fingerprint: …%1$s)",
        "de": "Custom-ROM erkannt (Fingerprint: …%1$s)",
    },
    "integrity_standard_rom": {
        "default": "Стандартная прошивка",
        "en": "Standard firmware",
        "ru": "Стандартная прошивка",
        "de": "Standard-Firmware",
    },
    "integrity_limit_root": {
        "default": "— Ограничение: полная проверка целостности /system требует root-доступа.",
        "en": "— Limitation: a full /system integrity check requires root access.",
        "ru": "— Ограничение: полная проверка целостности /system требует root-доступа.",
        "de": "— Einschränkung: Eine vollständige /system-Integritätsprüfung erfordert Root-Zugriff.",
    },
    "integrity_limit_checksums": {
        "default": "— Без root невозможно проверить checksums системных файлов.",
        "en": "— Without root, system file checksums cannot be verified.",
        "ru": "— Без root невозможно проверить checksums системных файлов.",
        "de": "— Ohne Root lassen sich Prüfsummen der Systemdateien nicht verifizieren.",
    },
    "integrity_limit_heuristics": {
        "default": "— Результат основан на эвристиках и не является гарантией.",
        "en": "— The result is based on heuristics and is not a guarantee.",
        "ru": "— Результат основан на эвристиках и не является гарантией.",
        "de": "— Das Ergebnis basiert auf Heuristiken und ist keine Garantie.",
    },
    "root_detail_su_found": {
        "default": "Обнаружен su binary по стандартному пути",
        "en": "su binary found in a standard location",
        "ru": "Обнаружен su binary по стандартному пути",
        "de": "su-Binary an einem Standardpfad gefunden",
    },
    "root_detail_su_not_found": {
        "default": "su binary не найден по стандартным путям",
        "en": "No su binary in standard locations",
        "ru": "su binary не найден по стандартным путям",
        "de": "Kein su-Binary an Standardpfaden gefunden",
    },
    "root_detail_tags_suspicious": {
        "default": "Build tags содержат «%1$s» (test-keys/dev-keys)",
        "en": "Build tags contain '%1$s' (test-keys/dev-keys)",
        "ru": "Build tags содержат «%1$s» (test-keys/dev-keys)",
        "de": "Build-Tags enthalten „%1$s“ (test-keys/dev-keys)",
    },
    "root_detail_tags_ok": {
        "default": "Build tags в норме: %1$s",
        "en": "Build tags look normal: %1$s",
        "ru": "Build tags в норме: %1$s",
        "de": "Build-Tags sind unauffällig: %1$s",
    },
    "root_detail_apps_found": {
        "default": "Обнаружены root-приложения: %1$s",
        "en": "Root apps found: %1$s",
        "ru": "Обнаружены root-приложения: %1$s",
        "de": "Root-Apps gefunden: %1$s",
    },
    "root_detail_apps_none": {
        "default": "Root-приложения не обнаружены",
        "en": "No root apps found",
        "ru": "Root-приложения не обнаружены",
        "de": "Keine Root-Apps gefunden",
    },
    "root_detail_which_su": {
        "default": "Возможно выполнение «which su» — su доступен в PATH",
        "en": "'which su' succeeded — su is available in PATH",
        "ru": "Возможно выполнение «which su» — su доступен в PATH",
        "de": "'which su' erfolgreich — su ist im PATH verfügbar",
    },
    "root_detail_no_path_su": {
        "default": "su не найден в PATH через Runtime.exec",
        "en": "su not found in PATH via Runtime.exec",
        "ru": "su не найден в PATH через Runtime.exec",
        "de": "su nicht im PATH via Runtime.exec gefunden",
    },
    "root_detail_system_writable": {
        "default": "Системный раздел выглядит перезаписанным (обнаружены маркеры)",
        "en": "The system partition looks overwritten (marker files found)",
        "ru": "Системный раздел выглядит перезаписанным (обнаружены маркеры)",
        "de": "Die Systempartition wirkt überschrieben (Markerdateien gefunden)",
    },
    "root_detail_system_intact": {
        "default": "Маркеры перезаписи /system не обнаружены",
        "en": "No /system overwrite markers found",
        "ru": "Маркеры перезаписи /system не обнаружены",
        "de": "Keine Überschreibungsmarker für /system gefunden",
    },
    "root_detail_selinux_permissive": {
        "default": "SELinux не в режиме enforcing (возможен root)",
        "en": "SELinux is not enforcing (root is possible)",
        "ru": "SELinux не в режиме enforcing (возможен root)",
        "de": "SELinux ist nicht im Enforcing-Modus (Root möglich)",
    },
    "root_detail_selinux_enforcing": {
        "default": "SELinux в режиме enforcing",
        "en": "SELinux is enforcing",
        "ru": "SELinux в режиме enforcing",
        "de": "SELinux ist im Enforcing-Modus",
    },
    "dev_options_usb_on": {
        "default": "USB-отладка: ВКЛЮЧЕНА ⚠",
        "en": "USB debugging: ENABLED ⚠",
        "ru": "USB-отладка: ВКЛЮЧЕНА ⚠",
        "de": "USB-Debugging: AKTIVIERT ⚠",
    },
    "dev_options_usb_off": {
        "default": "USB-отладка: выключена",
        "en": "USB debugging: disabled",
        "ru": "USB-отладка: выключена",
        "de": "USB-Debugging: deaktiviert",
    },
    "dev_options_dev_on": {
        "default": "Developer Options: ВКЛЮЧЕНЫ ⚠",
        "en": "Developer options: ENABLED ⚠",
        "ru": "Developer Options: ВКЛЮЧЕНЫ ⚠",
        "de": "Entwickleroptionen: AKTIVIERT ⚠",
    },
    "dev_options_dev_off": {
        "default": "Developer Options: выключены",
        "en": "Developer options: disabled",
        "ru": "Developer Options: выключены",
        "de": "Entwickleroptionen: deaktiviert",
    },
    "dev_options_adb_hint": {
        "default": "USB-отладка позволяет подключаться к устройству через ADB.",
        "en": "USB debugging allows connecting to the device over ADB.",
        "ru": "USB-отладка позволяет подключаться к устройству через ADB.",
        "de": "USB-Debugging erlaubt den Zugriff auf das Gerät über ADB.",
    },
    "dev_options_adb_recommendation": {
        "default": "Рекомендация: отключить USB-отладку в Настройки > Для разработчиков.",
        "en": "Recommendation: disable USB debugging in Settings > Developer options.",
        "ru": "Рекомендация: отключить USB-отладку в Настройки > Для разработчиков.",
        "de": "Empfehlung: Deaktivieren Sie USB-Debugging unter Einstellungen > Entwickleroptionen.",
    },
    "dev_options_recommendation": {
        "default": "Рекомендация: отключить Developer Options, если вы не разработчик.",
        "en": "Recommendation: disable developer options if you are not a developer.",
        "ru": "Рекомендация: отключить Developer Options, если вы не разработчик.",
        "de": "Empfehlung: Deaktivieren Sie die Entwickleroptionen, wenn Sie kein Entwickler sind.",
    },
    "phishing_no_sms_access": {
        "default": "Не удалось проверить SMS: нет доступа или нет данных.",
        "en": "Could not check SMS: no access or no data.",
        "ru": "Не удалось проверить SMS: нет доступа или нет данных.",
        "de": "SMS konnten nicht geprüft werden: kein Zugriff oder keine Daten.",
    },
    "phishing_malicious_hosts": {
        "default": "Домены из сообщений найдены в threat-intel: %1$s",
        "en": "Hosts from messages found in threat intel: %1$s",
        "ru": "Домены из сообщений найдены в threat-intel: %1$s",
        "de": "Hosts aus Nachrichten im Threat-Intel gefunden: %1$s",
    },
    "phishing_intel_disabled": {
        "default": "Проверка доменов в threat-intel выключена в настройках (наружу ничего не отправлялось).",
        "en": "Threat-intel domain checks are disabled in settings (nothing was sent out).",
        "ru": "Проверка доменов в threat-intel выключена в настройках (наружу ничего не отправлялось).",
        "de": "Threat-Intel-Domainprüfung ist in den Einstellungen deaktiviert (es wurden keine Daten gesendet).",
    },
    "phishing_checked_count": {
        "default": "Проверено SMS: %1$d",
        "en": "SMS checked: %1$d",
        "ru": "Проверено SMS: %1$d",
        "de": "Geprüfte SMS: %1$d",
    },
    "phishing_limit_sms_permission": {
        "default": "— Ограничение: без READ_SMS доступ к SMS может быть закрыт.",
        "en": "— Limitation: without READ_SMS access to SMS may be blocked.",
        "ru": "— Ограничение: без READ_SMS доступ к SMS может быть закрыт.",
        "de": "— Einschränkung: Ohne READ_SMS kann der SMS-Zugriff gesperrt sein.",
    },
    "phishing_limit_heuristic": {
        "default": "— Данная проверка — эвристика, не заменяет антифишинговые сервисы.",
        "en": "— This check is heuristic and does not replace anti-phishing services.",
        "ru": "— Данная проверка — эвристика, не заменяет антифишинговые сервисы.",
        "de": "— Diese Prüfung ist heuristisch und ersetzt keine Anti-Phishing-Dienste.",
    },
    "apk_sig_reason_debug_store": {
        "default": "APK подписан debug-ключом, но установлен из магазина — вероятна пересборка",
        "en": "APK is signed with a debug key but installed from a store — repackaging is likely",
        "ru": "APK подписан debug-ключом, но установлен из магазина — вероятна пересборка",
        "de": "APK mit Debug-Schlüssel signiert, aber aus einem Store installiert — Neuverpackung wahrscheinlich",
    },
    "apk_sig_reason_debug": {
        "default": "APK подписан debug-ключом (возможен пересобранный APK)",
        "en": "APK is signed with a debug key (possibly repackaged)",
        "ru": "APK подписан debug-ключом (возможен пересобранный APK)",
        "de": "APK mit Debug-Schlüssel signiert (möglicherweise neu verpackt)",
    },
    "apk_sig_reason_weak": {
        "default": "Слабая подпись APK (MD5/SHA-1): %1$s",
        "en": "Weak APK signature (MD5/SHA-1): %1$s",
        "ru": "Слабая подпись APK (MD5/SHA-1): %1$s",
        "de": "Schwache APK-Signatur (MD5/SHA-1): %1$s",
    },
    "apk_sig_reason_expired": {
        "default": "Срок действия сертификата подписи истёк",
        "en": "The signing certificate has expired",
        "ru": "Срок действия сертификата подписи истёк",
        "de": "Das Signaturzertifikat ist abgelaufen",
    },
    "apk_sig_reason_modified": {
        "default": "Файл APK изменён после установки — возможна подмена кода",
        "en": "The APK file changed after installation — code may have been swapped",
        "ru": "Файл APK изменён после установки — возможна подмена кода",
        "de": "Die APK-Datei wurde nach der Installation geändert — möglicher Code-Austausch",
    },
    "file_scan_reason_apk_outside_downloads": {
        "default": "APK вне папки Download",
        "en": "APK outside the Downloads folder",
        "ru": "APK вне папки Download",
        "de": "APK außerhalb des Download-Ordners",
    },
    "file_scan_reason_hidden_nomedia": {
        "default": "Скрытая .nomedia директория с подозрительными файлами",
        "en": "Hidden .nomedia directory with suspicious files",
        "ru": "Скрытая .nomedia директория с подозрительными файлами",
        "de": "Verstecktes .nomedia-Verzeichnis mit verdächtigen Dateien",
    },
    "perm_group_camera": {
        "default": "Камера",
        "en": "Camera",
        "ru": "Камера",
        "de": "Kamera",
    },
    "perm_group_microphone": {
        "default": "Микрофон",
        "en": "Microphone",
        "ru": "Микрофон",
        "de": "Mikrofon",
    },
    "perm_group_location": {
        "default": "Местоположение",
        "en": "Location",
        "ru": "Местоположение",
        "de": "Standort",
    },
    "perm_group_contacts": {
        "default": "Контакты",
        "en": "Contacts",
        "ru": "Контакты",
        "de": "Kontakte",
    },
    "perm_group_sms": {
        "default": "СМС",
        "en": "SMS",
        "ru": "СМС",
        "de": "SMS",
    },
    "perm_group_phone": {
        "default": "Телефон",
        "en": "Phone",
        "ru": "Телефон",
        "de": "Telefon",
    },
    "perm_group_storage": {
        "default": "Хранилище",
        "en": "Storage",
        "ru": "Хранилище",
        "de": "Speicher",
    },
    "perm_risk_critical_combo": {
        "default": "Опасная комбинация разрешений: %1$s",
        "en": "Dangerous permission combination: %1$s",
        "ru": "Опасная комбинация разрешений: %1$s",
        "de": "Gefährliche Berechtigungskombination: %1$s",
    },
    "perm_risk_sms_extra": {
        "default": "СМС и дополнительные разрешения: %1$s",
        "en": "SMS plus additional permissions: %1$s",
        "ru": "СМС и дополнительные разрешения: %1$s",
        "de": "SMS und zusätzliche Berechtigungen: %1$s",
    },
    "perm_risk_background_location": {
        "default": "Доступ к местоположению",
        "en": "Location access",
        "ru": "Доступ к местоположению",
        "de": "Standortzugriff",
    },
    "perm_risk_many_sensitive": {
        "default": "Много чувствительных разрешений: %1$s",
        "en": "Many sensitive permissions: %1$s",
        "ru": "Много чувствительных разрешений: %1$s",
        "de": "Viele sensible Berechtigungen: %1$s",
    },
    "hidden_app_reason_suspicious_perms": {
        "default": "Подозрительное имя пакета, скрыт из лаунчера, просит: %1$s",
        "en": "Suspicious package name, hidden from the launcher, requests: %1$s",
        "ru": "Подозрительное имя пакета, скрыт из лаунчера, просит: %1$s",
        "de": "Verdächtiger Paketname, vor dem Launcher verborgen, fordert: %1$s",
    },
    "hidden_app_reason_suspicious": {
        "default": "Подозрительное имя пакета, скрыт из лаунчера",
        "en": "Suspicious package name, hidden from the launcher",
        "ru": "Подозрительное имя пакета, скрыт из лаунчера",
        "de": "Verdächtiger Paketname, vor dem Launcher verborgen",
    },
    "hidden_app_reason_perms": {
        "default": "Скрыт из лаунчера, просит: %1$s",
        "en": "Hidden from the launcher, requests: %1$s",
        "ru": "Скрыт из лаунчера, просит: %1$s",
        "de": "Vor dem Launcher verborgen, fordert: %1$s",
    },
    "scan_issue_root_title": {
        "default": "Обнаружен root-доступ (эвристика)",
        "en": "Root access detected (heuristic)",
        "ru": "Обнаружен root-доступ (эвристика)",
        "de": "Root-Zugriff erkannt (Heuristik)",
    },
    "scan_issue_root_desc": {
        "default": "Признаки root-доступа (уверенность %1$d%%). Проверка использует эвристики и не даёт 100%% гарантии.",
        "en": "Signs of root access (confidence %1$d%%). This check is heuristic and not 100%% reliable.",
        "ru": "Признаки root-доступа (уверенность %1$d%%). Проверка использует эвристики и не даёт 100%% гарантии.",
        "de": "Hinweise auf Root-Zugriff (Sicherheit %1$d%%). Diese Prüfung ist heuristisch und nicht zu 100%% zuverlässig.",
    },
    "scan_issue_root_rec": {
        "default": "Если root не установлен намеренно — сброс к заводским настройкам. Проверьте Magisk/SuperSU.",
        "en": "If root was not installed on purpose, perform a factory reset. Check for Magisk/SuperSU.",
        "ru": "Если root не установлен намеренно — сброс к заводским настройкам. Проверьте Magisk/SuperSU.",
        "de": "Wenn Root nicht absichtlich installiert wurde, setzen Sie das Gerät auf Werkseinstellungen zurück. Prüfen Sie Magisk/SuperSU.",
    },
    "scan_issue_integrity_title": {
        "default": "Возможна компрометация системы (эвристика)",
        "en": "System integrity may be compromised (heuristic)",
        "ru": "Возможна компрометация системы (эвристика)",
        "de": "Systemintegrität möglicherweise kompromittiert (Heuristik)",
    },
    "scan_issue_integrity_desc": {
        "default": "Без root-доступа проверка целостности /system частичная, часть проверок — эвристические.",
        "en": "Without root access the /system integrity check is partial; some checks are heuristic.",
        "ru": "Без root-доступа проверка целостности /system частичная, часть проверок — эвристические.",
        "de": "Ohne Root-Zugriff ist die Integritätsprüfung von /system nur teilweise möglich; einige Prüfungen sind heuristisch.",
    },
    "scan_issue_integrity_rec": {
        "default": "Проверьте bootloader и Verified Boot. Для полной проверки нужен root.",
        "en": "Check the bootloader and Verified Boot. A full check requires root.",
        "ru": "Проверьте bootloader и Verified Boot. Для полной проверки нужен root.",
        "de": "Prüfen Sie Bootloader und Verified Boot. Eine vollständige Prüfung erfordert Root.",
    },
    "scan_issue_file_title": {
        "default": "Подозрительный файл: %1$s (ограниченный доступ)",
        "en": "Suspicious file: %1$s (limited access)",
        "ru": "Подозрительный файл: %1$s (ограниченный доступ)",
        "de": "Verdächtige Datei: %1$s (eingeschränkter Zugriff)",
    },
    "scan_issue_file_rec": {
        "default": "Проверьте файл вручную. На Android 10+ сканирование ограничено Scoped Storage — часть файлов может быть недоступна.",
        "en": "Check the file manually. On Android 10+ scanning is limited by scoped storage, so some files may be inaccessible.",
        "ru": "Проверьте файл вручную. На Android 10+ сканирование ограничено Scoped Storage — часть файлов может быть недоступна.",
        "de": "Prüfen Sie die Datei manuell. Ab Android 10 ist der Scan durch Scoped Storage eingeschränkt, daher sind manche Dateien nicht erreichbar.",
    },
    "scan_issue_accessibility_title": {
        "default": "Подозрительные службы доступности (%1$d)",
        "en": "Suspicious accessibility services (%1$d)",
        "ru": "Подозрительные службы доступности (%1$d)",
        "de": "Verdächtige Bedienungshilfen (%1$d)",
    },
    "scan_issue_accessibility_desc": {
        "default": "Активные службы: %1$s. Вредоносное ПО использует доступность для чтения экрана, перехвата ввода и обхода банковских защит.",
        "en": "Active services: %1$s. Malware abuses accessibility to read the screen, intercept input and bypass banking protections.",
        "ru": "Активные службы: %1$s. Вредоносное ПО использует доступность для чтения экрана, перехвата ввода и обхода банковских защит.",
        "de": "Aktive Dienste: %1$s. Schadsoftware nutzt Bedienungshilfen, um den Bildschirm zu lesen, Eingaben abzufangen und Bankschutz zu umgehen.",
    },
    "scan_issue_accessibility_rec": {
        "default": "Отключите ненужные: Настройки > Специальные возможности. Проверьте каждое приложение.",
        "en": "Turn off anything you do not need: Settings > Accessibility. Review each app.",
        "ru": "Отключите ненужные: Настройки > Специальные возможности. Проверьте каждое приложение.",
        "de": "Deaktivieren Sie Unnötiges: Einstellungen > Bedienungshilfen. Prüfen Sie jede App.",
    },
    "scan_issue_overlay_title": {
        "default": "Обнаружены потенциальные overlay-атаки",
        "en": "Potential overlay attacks detected",
        "ru": "Обнаружены потенциальные overlay-атаки",
        "de": "Mögliche Overlay-Angriffe erkannt",
    },
    "scan_issue_overlay_desc": {
        "default": "Приложения, способные перехватывать нажатия: %1$s. Окна поверх других приложений могут перехватывать клики.",
        "en": "Apps that can intercept taps: %1$s. Overlay windows can capture your clicks.",
        "ru": "Приложения, способные перехватывать нажатия: %1$s. Окна поверх других приложений могут перехватывать клики.",
        "de": "Apps, die Tippen abfangen können: %1$s. Overlay-Fenster können Klicks abfangen.",
    },
    "scan_issue_overlay_rec": {
        "default": "Отзовите разрешение «Поверх других приложений» у подозрительных приложений.",
        "en": "Revoke the \"Display over other apps\" permission for suspicious apps.",
        "ru": "Отзовите разрешение «Поверх других приложений» у подозрительных приложений.",
        "de": "Entziehen Sie verdächtigen Apps die Berechtigung „Über andere Apps anzeigen“.",
    },
    "scan_issue_permissions_title": {
        "default": "Приложения с опасными разрешениями (%1$d, критичных: %2$d, высоких: %3$d)",
        "en": "Apps with dangerous permissions (%1$d, critical: %2$d, high: %3$d)",
        "ru": "Приложения с опасными разрешениями (%1$d, критичных: %2$d, высоких: %3$d)",
        "de": "Apps mit gefährlichen Berechtigungen (%1$d, kritisch: %2$d, hoch: %3$d)",
    },
    "scan_issue_permissions_desc": {
        "default": "Найдены рискованные комбинации разрешений: %1$s",
        "en": "Risky permission combinations found: %1$s",
        "ru": "Найдены рискованные комбинации разрешений: %1$s",
        "de": "Riskante Berechtigungskombinationen gefunden: %1$s",
    },
    "scan_issue_privacy_rec": {
        "default": "Откройте проверку приватности для детального анализа и отзыва разрешений.",
        "en": "Open the privacy check for a detailed analysis and to revoke permissions.",
        "ru": "Откройте проверку приватности для детального анализа и отзыва разрешений.",
        "de": "Öffnen Sie die Datenschutzprüfung für eine Detailanalyse und zum Entziehen von Berechtigungen.",
    },
    "scan_issue_hidden_suspicious_title": {
        "default": "Скрытые приложения с подозрительными именами (%1$d)",
        "en": "Hidden apps with suspicious names (%1$d)",
        "ru": "Скрытые приложения с подозрительными именами (%1$d)",
        "de": "Versteckte Apps mit verdächtigen Namen (%1$d)",
    },
    "scan_issue_hidden_suspicious_desc": {
        "default": "Скрыты из лаунчера: %1$s. Вредоносное ПО часто прячет иконки.",
        "en": "Hidden from the launcher: %1$s. Malware often hides its icon.",
        "ru": "Скрыты из лаунчера: %1$s. Вредоносное ПО часто прячет иконки.",
        "de": "Vor dem Launcher verborgen: %1$s. Schadsoftware versteckt häufig ihr Symbol.",
    },
    "scan_issue_hidden_rec": {
        "default": "Удалите подозрительные приложения. Список установленных приложений — в настройках системы.",
        "en": "Uninstall suspicious apps. The list of installed apps is in the system settings.",
        "ru": "Удалите подозрительные приложения. Список установленных приложений — в настройках системы.",
        "de": "Deinstallieren Sie verdächtige Apps. Die Liste installierter Apps finden Sie in den Systemeinstellungen.",
    },
    "scan_issue_hidden_title": {
        "default": "Скрытые приложения (%1$d)",
        "en": "Hidden apps (%1$d)",
        "ru": "Скрытые приложения (%1$d)",
        "de": "Versteckte Apps (%1$d)",
    },
    "scan_issue_hidden_desc": {
        "default": "Приложения без иконки в лаунчере: %1$s. Это могут быть легитимные сервисы.",
        "en": "Apps without a launcher icon: %1$s. These may be legitimate services.",
        "ru": "Приложения без иконки в лаунчере: %1$s. Это могут быть легитимные сервисы.",
        "de": "Apps ohne Symbol im Launcher: %1$s. Dabei kann es sich um legitime Dienste handeln.",
    },
    "scan_issue_hidden_check_rec": {
        "default": "Проверьте каждое приложение: если не понимаете, зачем оно нужно, удалите его.",
        "en": "Review each app: if you do not know why it is there, uninstall it.",
        "ru": "Проверьте каждое приложение: если не понимаете, зачем оно нужно, удалите его.",
        "de": "Prüfen Sie jede App: Wenn Sie nicht wissen, wozu sie dient, deinstallieren Sie sie.",
    },
    "scan_issue_admins_title": {
        "default": "Подозрительные администраторы устройства (%1$d)",
        "en": "Suspicious device administrators (%1$d)",
        "ru": "Подозрительные администраторы устройства (%1$d)",
        "de": "Verdächtige Geräteadministratoren (%1$d)",
    },
    "scan_issue_admins_desc": {
        "default": "Активные администраторы: %1$s. Права администратора мешают удалить приложение.",
        "en": "Active administrators: %1$s. Device admin rights prevent app removal.",
        "ru": "Активные администраторы: %1$s. Права администратора мешают удалить приложение.",
        "de": "Aktive Administratoren: %1$s. Administratorrechte verhindern das Deinstallieren.",
    },
    "scan_issue_admins_rec": {
        "default": "Отзовите права: Настройки > Безопасность > Администраторы устройства.",
        "en": "Revoke the rights: Settings > Security > Device admin apps.",
        "ru": "Отзовите права: Настройки > Безопасность > Администраторы устройства.",
        "de": "Entziehen Sie die Rechte: Einstellungen > Sicherheit > Geräteadministratoren.",
    },
    "scan_issue_excessive_title": {
        "default": "Приложения с избыточными разрешениями (%1$d)",
        "en": "Apps with excessive permissions (%1$d)",
        "ru": "Приложения с избыточными разрешениями (%1$d)",
        "de": "Apps mit übermäßigen Berechtigungen (%1$d)",
    },
    "scan_issue_excessive_desc": {
        "default": "Приложения запрашивают чувствительные разрешения без явной необходимости.",
        "en": "Apps request sensitive permissions without an obvious need.",
        "ru": "Приложения запрашивают чувствительные разрешения без явной необходимости.",
        "de": "Apps fordern sensible Berechtigungen ohne erkennbaren Bedarf an.",
    },
    "scan_issue_spyware_title": {
        "default": "Подозрительные приложения по активности (%1$d)",
        "en": "Apps with suspicious activity (%1$d)",
        "ru": "Подозрительные приложения по активности (%1$d)",
        "de": "Apps mit verdächtiger Aktivität (%1$d)",
    },
    "scan_issue_spyware_desc": {
        "default": "Признаки скрытого мониторинга: администратор устройства, служба доступности, установка не из магазина.",
        "en": "Signs of covert monitoring: device admin, accessibility service, sideloaded installation.",
        "ru": "Признаки скрытого мониторинга: администратор устройства, служба доступности, установка не из магазина.",
        "de": "Hinweise auf verdeckte Überwachung: Geräteadministrator, Bedienungshilfe, Installation außerhalb des Stores.",
    },
    "scan_issue_spyware_rec": {
        "default": "Откройте проверку на шпионское ПО для детального анализа. Проверка эвристическая.",
        "en": "Open the spyware check for a detailed analysis. The check is heuristic.",
        "ru": "Откройте проверку на шпионское ПО для детального анализа. Проверка эвристическая.",
        "de": "Öffnen Sie die Spyware-Prüfung für eine Detailanalyse. Die Prüfung ist heuristisch.",
    },
    "scan_issue_firewall_blocks_title": {
        "default": "Недавние блокировки сети (%1$d)",
        "en": "Recent network blocks (%1$d)",
        "ru": "Недавние блокировки сети (%1$d)",
        "de": "Letzte Netzwerkblockierungen (%1$d)",
    },
    "scan_issue_firewall_blocks_desc": {
        "default": "В журнале брандмауэра есть недавние попытки подключений.",
        "en": "The firewall log contains recent connection attempts.",
        "ru": "В журнале брандмауэра есть недавние попытки подключений.",
        "de": "Im Firewall-Protokoll finden sich aktuelle Verbindungsversuche.",
    },
    "scan_issue_firewall_blocks_rec": {
        "default": "Проверьте правила брандмауэра. Журнал — не доказательство угрозы.",
        "en": "Review the firewall rules. The log alone does not prove a threat.",
        "ru": "Проверьте правила брандмауэра. Журнал — не доказательство угрозы.",
        "de": "Prüfen Sie die Firewall-Regeln. Das Protokoll allein belegt keine Bedrohung.",
    },
    "scan_issue_firewall_empty_title": {
        "default": "Брандмауэр: нет активных правил",
        "en": "Firewall: no active rules",
        "ru": "Брандмауэр: нет активных правил",
        "de": "Firewall: keine aktiven Regeln",
    },
    "scan_issue_firewall_empty_desc": {
        "default": "Для дополнительной защиты стоит настроить правила брандмауэра.",
        "en": "Configuring firewall rules improves protection.",
        "ru": "Для дополнительной защиты стоит настроить правила брандмауэра.",
        "de": "Regeln für die Firewall erhöhen den Schutz.",
    },
    "scan_issue_firewall_empty_rec": {
        "default": "Откройте раздел «Брандмауэр», чтобы настроить правила.",
        "en": "Open the Firewall section to configure rules.",
        "ru": "Откройте раздел «Брандмауэр», чтобы настроить правила.",
        "de": "Öffnen Sie den Bereich „Firewall“, um Regeln festzulegen.",
    },
    "scan_issue_signature_debug_title": {
        "default": "Пересобранные APK с debug-подписью (%1$d)",
        "en": "Repackaged APKs with a debug signature (%1$d)",
        "ru": "Пересобранные APK с debug-подписью (%1$d)",
        "de": "Neu gepackte APKs mit Debug-Signatur (%1$d)",
    },
    "scan_issue_signature_debug_desc": {
        "default": "Установлены из магазина, но подписаны debug-ключом: %1$s. Это значит, что APK пересобрали вне оригинального разработчика.",
        "en": "Installed from a store but signed with a debug key: %1$s. The APK was repackaged outside the original developer.",
        "ru": "Установлены из магазина, но подписаны debug-ключом: %1$s. Это значит, что APK пересобрали вне оригинального разработчика.",
        "de": "Aus einem Store installiert, aber mit Debug-Schlüssel signiert: %1$s. Das APK wurde außerhalb des ursprünglichen Entwicklers neu gepackt.",
    },
    "scan_issue_signature_remove_rec": {
        "default": "Удалите приложение и установите его из официального магазина.",
        "en": "Uninstall the app and install it from the official store.",
        "ru": "Удалите приложение и установите его из официального магазина.",
        "de": "Deinstallieren Sie die App und installieren Sie sie aus dem offiziellen Store.",
    },
    "scan_issue_signature_warning_title": {
        "default": "Проблемы с подписью APK (%1$d)",
        "en": "APK signature problems (%1$d)",
        "ru": "Проблемы с подписью APK (%1$d)",
        "de": "Probleme mit der APK-Signatur (%1$d)",
    },
    "scan_issue_signature_warning_desc": {
        "default": "%1$s",
        "en": "%1$s",
        "ru": "%1$s",
        "de": "%1$s",
    },
    "scan_issue_signature_verify_rec": {
        "default": "Проверьте приложения вручную. Если приложение ставили не вы — удалите его.",
        "en": "Review the apps manually. If you did not install one of them, uninstall it.",
        "ru": "Проверьте приложения вручную. Если приложение ставили не вы — удалите его.",
        "de": "Prüfen Sie die Apps manuell. Wenn Sie eine davon nicht installiert haben, deinstallieren Sie sie.",
    },
    "scan_issue_signature_expired_title": {
        "default": "Истёкшие сертификаты подписи (%1$d)",
        "en": "Expired signing certificates (%1$d)",
        "ru": "Истёкшие сертификаты подписи (%1$d)",
        "de": "Abgelaufene Signaturzertifikate (%1$d)",
    },
    "scan_issue_signature_expired_desc": {
        "default": "%1$s. Обычно это старые приложения.",
        "en": "%1$s. These are usually old apps.",
        "ru": "%1$s. Обычно это старые приложения.",
        "de": "%1$s. Meist handelt es sich um alte Apps.",
    },
    "scan_issue_signature_update_rec": {
        "default": "Обновите приложения, чтобы получить актуальную подпись.",
        "en": "Update the apps to get a current signature.",
        "ru": "Обновите приложения, чтобы получить актуальную подпись.",
        "de": "Aktualisieren Sie die Apps, um eine aktuelle Signatur zu erhalten.",
    },
    "scan_issue_phishing_title": {
        "default": "Подозрительные ссылки в SMS (%1$d)",
        "en": "Suspicious links in SMS (%1$d)",
        "ru": "Подозрительные ссылки в SMS (%1$d)",
        "de": "Verdächtige Links in SMS (%1$d)",
    },
    "scan_issue_phishing_desc": {
        "default": "В недавних сообщениях найдены ссылки вместе с фишинговыми формулировками.",
        "en": "Recent messages contain links combined with phishing wording.",
        "ru": "В недавних сообщениях найдены ссылки вместе с фишинговыми формулировками.",
        "de": "Aktuelle Nachrichten enthalten Links zusammen mit Phishing-Formulierungen.",
    },
    "scan_issue_phishing_rec": {
        "default": "Проверьте отправителей вручную. Доступ к SMS может быть ограничен.",
        "en": "Check the senders manually. SMS access may be limited.",
        "ru": "Проверьте отправителей вручную. Доступ к SMS может быть ограничен.",
        "de": "Prüfen Sie die Absender manuell. Der SMS-Zugriff kann eingeschränkt sein.",
    },
    "scan_issue_usb_debug_title": {
        "default": "Включена USB-отладка (эвристика)",
        "en": "USB debugging is enabled (heuristic)",
        "ru": "Включена USB-отладка (эвристика)",
        "de": "USB-Debugging ist aktiviert (Heuristik)",
    },
    "scan_issue_usb_debug_desc": {
        "default": "Подключение по ADB — риск при физическом доступе к устройству.",
        "en": "ADB access is a risk when someone has physical access to the device.",
        "ru": "Подключение по ADB — риск при физическом доступе к устройству.",
        "de": "ADB-Zugriff ist ein Risiko, wenn jemand physischen Zugang zum Gerät hat.",
    },
    "scan_issue_usb_debug_rec": {
        "default": "Отключите: Настройки > Для разработчиков > Отладка по USB.",
        "en": "Turn it off: Settings > Developer options > USB debugging.",
        "ru": "Отключите: Настройки > Для разработчиков > Отладка по USB.",
        "de": "Deaktivieren: Einstellungen > Entwickleroptionen > USB-Debugging.",
    },
    "scan_issue_dev_options_title": {
        "default": "Включены параметры разработчика (эвристика)",
        "en": "Developer options are enabled (heuristic)",
        "ru": "Включены параметры разработчика (эвристика)",
        "de": "Entwickleroptionen sind aktiviert (Heuristik)",
    },
    "scan_issue_dev_options_desc": {
        "default": "Режим разработчика повышает риск при физическом доступе к устройству.",
        "en": "Developer mode increases the risk when someone has physical access.",
        "ru": "Режим разработчика повышает риск при физическом доступе к устройству.",
        "de": "Der Entwicklermodus erhöht das Risiko bei physischem Zugang zum Gerät.",
    },
    "scan_issue_dev_options_rec": {
        "default": "Отключите, если вы не разработчик.",
        "en": "Turn it off if you are not a developer.",
        "ru": "Отключите, если вы не разработчик.",
        "de": "Deaktivieren Sie ihn, wenn Sie kein Entwickler sind.",
    },
    "scan_issue_patch_title": {
        "default": "Патч безопасности устарел (%1$d мес.)",
        "en": "Security patch is outdated (%1$d months)",
        "ru": "Патч безопасности устарел (%1$d мес.)",
        "de": "Sicherheitspatch ist veraltet (%1$d Monate)",
    },
    "scan_issue_patch_desc": {
        "default": "Дата патча: %1$s. Устройство уязвимо.",
        "en": "Patch date: %1$s. The device is vulnerable.",
        "ru": "Дата патча: %1$s. Устройство уязвимо.",
        "de": "Patch-Datum: %1$s. Das Gerät ist angreifbar.",
    },
    "scan_issue_patch_rec": {
        "default": "Проверьте обновления: Настройки > О телефоне > Обновление ПО.",
        "en": "Check for updates: Settings > About phone > System update.",
        "ru": "Проверьте обновления: Настройки > О телефоне > Обновление ПО.",
        "de": "Prüfen Sie Updates: Einstellungen > Über das Telefon > Systemupdate.",
    },
    "accessibility_reason_suspicious_name": {
        "default": "Подозрительное имя пакета или приложения: %1$s",
        "en": "Suspicious package or app name: %1$s",
        "ru": "Подозрительное имя пакета или приложения: %1$s",
        "de": "Verdächtiger Paket- oder App-Name: %1$s",
    },
    "accessibility_reason_removed_app": {
        "default": "Служба удалённого приложения всё ещё активна",
        "en": "A service of a removed app is still active",
        "ru": "Служба удалённого приложения всё ещё активна",
        "de": "Ein Dienst einer entfernten App ist noch aktiv",
    },
    "accessibility_reason_third_party": {
        "default": "Сторонняя служба доступности с полным доступом к экрану",
        "en": "Third-party accessibility service with full screen access",
        "ru": "Сторонняя служба доступности с полным доступом к экрану",
        "de": "Bedienungshilfe eines Drittanbieters mit vollem Bildschirmzugriff",
    },
    "accessibility_unknown_app_name": {
        "default": "Удалённое приложение",
        "en": "Removed app",
        "ru": "Удалённое приложение",
        "de": "Entfernte App",
    },
    "device_admin_unknown_app": {
        "default": "Неизвестное приложение (%1$s)",
        "en": "Unknown app (%1$s)",
        "ru": "Неизвестное приложение (%1$s)",
        "de": "Unbekannte App (%1$s)",
    },
    "device_admin_app_removed": {
        "default": "Приложение удалено, но права администратора остались",
        "en": "The app was removed but admin rights remain",
        "ru": "Приложение удалено, но права администратора остались",
        "de": "Die App wurde entfernt, die Administratorrechte bleiben",
    },
    "device_admin_suspicious_name": {
        "default": "Подозрительное имя и права администратора",
        "en": "Suspicious name and device admin rights",
        "ru": "Подозрительное имя и права администратора",
        "de": "Verdächtiger Name und Administratorrechte",
    },
    "device_admin_third_party": {
        "default": "Сторонний администратор устройства (не из известных MDM)",
        "en": "Third-party device administrator (not a known MDM)",
        "ru": "Сторонний администратор устройства (не из известных MDM)",
        "de": "Geräteadministrator eines Drittanbieters (kein bekanntes MDM)",
    },
    "overlay_reason": {
        "default": "Может рисовать поверх других приложений; имя похоже на «%1$s»",
        "en": "Can draw over other apps; the name resembles \"%1$s\"",
        "ru": "Может рисовать поверх других приложений; имя похоже на «%1$s»",
        "de": "Kann über anderen Apps zeichnen; der Name ähnelt „%1$s“",
    },
    "sms_reply_device_locked": {
        "default": "PhoneGuard: устройство заблокировано.",
        "en": "PhoneGuard: the device has been locked.",
        "ru": "PhoneGuard: устройство заблокировано.",
        "de": "PhoneGuard: Das Gerät wurde gesperrt.",
    },
    "sms_reply_lock_unavailable": {
        "default": "PhoneGuard: права администратора устройства не активны, блокировка невозможна.",
        "en": "PhoneGuard: device admin is not active, locking is not possible.",
        "ru": "PhoneGuard: права администратора устройства не активны, блокировка невозможна.",
        "de": "PhoneGuard: Der Geräteadministrator ist nicht aktiv, Sperren ist nicht möglich.",
    },
    "sms_reply_wipe_pending": {
        "default": "PhoneGuard: получена команда WIPE, данные будут удалены.",
        "en": "PhoneGuard: WIPE command received, data will be erased.",
        "ru": "PhoneGuard: получена команда WIPE, данные будут удалены.",
        "de": "PhoneGuard: WIPE-Befehl erhalten, die Daten werden gelöscht.",
    },
    "sms_reply_photo_failed": {
        "default": "PhoneGuard: не удалось сделать фото (ограничение системы).",
        "en": "PhoneGuard: could not take a photo (system restriction).",
        "ru": "PhoneGuard: не удалось сделать фото (ограничение системы).",
        "de": "PhoneGuard: Foto konnte nicht aufgenommen werden (Systemeinschränkung).",
    },
    "sms_status_line": {
        "default": "PhoneGuard: %1$s. ID устройства: %2$s.",
        "en": "PhoneGuard: %1$s. Device ID: %2$s.",
        "ru": "PhoneGuard: %1$s. ID устройства: %2$s.",
        "de": "PhoneGuard: %1$s. Geräte-ID: %2$s.",
    },
    "sms_status_battery": {
        "default": " Батарея: %1$d%%.",
        "en": " Battery: %1$d%%.",
        "ru": " Батарея: %1$d%%.",
        "de": " Akku: %1$d%%.",
    },
    "sim_state_ready": {
        "default": "SIM активна",
        "en": "SIM is active",
        "ru": "SIM активна",
        "de": "SIM ist aktiv",
    },
    "sim_state_absent": {
        "default": "SIM отсутствует",
        "en": "SIM is missing",
        "ru": "SIM отсутствует",
        "de": "SIM fehlt",
    },
    "sim_state_pin_required": {
        "default": "требуется PIN",
        "en": "PIN required",
        "ru": "требуется PIN",
        "de": "PIN erforderlich",
    },
    "sim_state_puk_required": {
        "default": "требуется PUK",
        "en": "PUK required",
        "ru": "требуется PUK",
        "de": "PUK erforderlich",
    },
    "sim_state_network_locked": {
        "default": "сеть заблокирована",
        "en": "network locked",
        "ru": "сеть заблокирована",
        "de": "Netz gesperrt",
    },
    "sim_state_unknown": {
        "default": "состояние SIM неизвестно",
        "en": "SIM state unknown",
        "ru": "состояние SIM неизвестно",
        "de": "SIM-Status unbekannt",
    },
    "sim_swap_channel_name": {
        "default": "Смена SIM-карты",
        "en": "SIM card change",
        "ru": "Смена SIM-карты",
        "de": "SIM-Kartenwechsel",
    },
    "sim_swap_channel_description": {
        "default": "Вопросы о замене SIM и предупреждения о подмене",
        "en": "Questions about a new SIM and SIM-swap warnings",
        "ru": "Вопросы о замене SIM и предупреждения о подмене",
        "de": "Rückfragen zum SIM-Wechsel und Warnungen vor SIM-Tausch",
    },
    "sim_swap_imsi_change": {
        "default": "Старый IMSI: %1$s → новый: %2$s",
        "en": "Old IMSI: %1$s → new: %2$s",
        "ru": "Старый IMSI: %1$s → новый: %2$s",
        "de": "Alte IMSI: %1$s → neue: %2$s",
    },
    "scan_failed": {
        "default": "Сканирование прервано из-за ошибки. Попробуйте ещё раз.",
        "en": "The scan stopped because of an error. Please try again.",
        "ru": "Сканирование прервано из-за ошибки. Попробуйте ещё раз.",
        "de": "Der Scan wurde wegen eines Fehlers abgebrochen. Bitte versuchen Sie es erneut.",
    },
    "scan_no_issues_title": {
        "default": "Устройство выглядит безопасным",
        "en": "The device looks safe",
        "ru": "Устройство выглядит безопасным",
        "de": "Das Gerät wirkt sicher",
    },
    "scan_no_issues_description": {
        "default": "Критических проблем не обнаружено.",
        "en": "No critical issues were found.",
        "ru": "Критических проблем не обнаружено.",
        "de": "Es wurden keine kritischen Probleme gefunden.",
    },
    "scan_no_issues_recommendation": {
        "default": "Регулярно запускайте сканирование.",
        "en": "Run a scan regularly.",
        "ru": "Регулярно запускайте сканирование.",
        "de": "Führen Sie regelmäßig einen Scan aus.",
    },
    "pdf_report_title": {
        "default": "PhoneGuard: отчёт о проверке",
        "en": "PhoneGuard scan report",
        "ru": "PhoneGuard: отчёт о проверке",
        "de": "PhoneGuard: Scanbericht",
    },
    "pdf_report_date": {
        "default": "Дата: %1$s",
        "en": "Date: %1$s",
        "ru": "Дата: %1$s",
        "de": "Datum: %1$s",
    },
    "pdf_risk_score": {
        "default": "Оценка риска: %1$d/100",
        "en": "Risk score: %1$d/100",
        "ru": "Оценка риска: %1$d/100",
        "de": "Risikobewertung: %1$d/100",
    },
    "pdf_issues_found": {
        "default": "Найдено проблем: %1$d",
        "en": "Issues found: %1$d",
        "ru": "Найдено проблем: %1$d",
        "de": "Gefundene Probleme: %1$d",
    },
    "pdf_summary_title": {
        "default": "Сводка",
        "en": "Summary",
        "ru": "Сводка",
        "de": "Zusammenfassung",
    },
    "pdf_summary_body": {
        "default": "Отчёт сформирован автоматически приложением PhoneGuard. Проверьте найденные проблемы и выполните рекомендации, чтобы повысить защищённость устройства. Подробности доступны в истории проверок в приложении.",
        "en": "This report was generated automatically by PhoneGuard. Review the issues below and follow the recommendations to improve your device security. Details are available in the scan history in the app.",
        "ru": "Отчёт сформирован автоматически приложением PhoneGuard. Проверьте найденные проблемы и выполните рекомендации, чтобы повысить защищённость устройства. Подробности доступны в истории проверок в приложении.",
        "de": "Dieser Bericht wurde automatisch von PhoneGuard erstellt. Prüfen Sie die gefundenen Probleme und befolgen Sie die Empfehlungen, um die Sicherheit Ihres Geräts zu verbessern. Details finden Sie im Scanverlauf der App.",
    },
    "pdf_details_title": {
        "default": "Детали отчёта",
        "en": "Report details",
        "ru": "Детали отчёта",
        "de": "Berichtdetails",
    },
    "pdf_scan_id": {
        "default": "ID проверки: %1$d",
        "en": "Scan ID: %1$d",
        "ru": "ID проверки: %1$d",
        "de": "Scan-ID: %1$d",
    },
    "pdf_scan_timestamp": {
        "default": "Метка времени: %1$s",
        "en": "Timestamp: %1$s",
        "ru": "Метка времени: %1$s",
        "de": "Zeitstempel: %1$s",
    },
    "intrusion_sms_prefix": {
        "default": "PhoneGuard: %1$s",
        "en": "PhoneGuard: %1$s",
        "ru": "PhoneGuard: %1$s",
        "de": "PhoneGuard: %1$s",
    },
    "firewall_recent_blocks": {
        "default": "Недавние блокировки",
        "en": "Recent blocks",
        "ru": "Недавние блокировки",
        "de": "Letzte Blockierungen",
    },
    "traffic_direction_unknown": {
        "default": "Направление неизвестно",
        "en": "Direction unknown",
        "ru": "Направление неизвестно",
        "de": "Richtung unbekannt",
    },
    "audit_encryption_unknown": {
        "default": "Не удалось проверить состояние шифрования",
        "en": "Could not verify the encryption status",
        "ru": "Не удалось проверить состояние шифрования",
        "de": "Verschlüsselungsstatus konnte nicht geprüft werden",
    },
    "audit_unknown_sources_apps": {
        "default": "Приложениям с правом установки APK: %1$s",
        "en": "Apps allowed to install APKs: %1$s",
        "ru": "Приложениям с правом установки APK: %1$s",
        "de": "Apps mit Installationsrecht für APKs: %1$s",
    },
    "device_admin_enabled": {
        "default": "Device Admin включён",
        "en": "Device Admin enabled",
        "ru": "Device Admin включён",
        "de": "Device Admin aktiviert",
    },
    "device_admin_disabled": {
        "default": "Device Admin выключен",
        "en": "Device Admin disabled",
        "ru": "Device Admin выключен",
        "de": "Device Admin deaktiviert",
    },
    "wipe_failed_sms": {
        "default": "PhoneGuard: удалённое стирание не выполнено — у приложения нет прав device owner.",
        "en": "PhoneGuard: remote wipe failed — the app does not have device owner rights.",
        "ru": "PhoneGuard: удалённое стирание не выполнено — у приложения нет прав device owner.",
        "de": "PhoneGuard: Fernlöschung fehlgeschlagen — die App hat keine Device-Owner-Rechte.",
    },
    "location_share_sms": {
        "default": "PhoneGuard: местоположение — %1$s, %2$s. %3$s",
        "en": "PhoneGuard: location — %1$s, %2$s. %3$s",
        "ru": "PhoneGuard: местоположение — %1$s, %2$s. %3$s",
        "de": "PhoneGuard: Standort — %1$s, %2$s. %3$s",
    },
    "location_share_unavailable": {
        "default": "PhoneGuard: местоположение определить не удалось.",
        "en": "PhoneGuard: location could not be determined.",
        "ru": "PhoneGuard: местоположение определить не удалось.",
        "de": "PhoneGuard: Standort konnte nicht ermittelt werden.",
    },
    "stealth_photo_sent": {
        "default": "PhoneGuard: Фото сделано. Файл: %1$s",
        "en": "PhoneGuard: Photo taken. File: %1$s",
        "ru": "PhoneGuard: Фото сделано. Файл: %1$s",
        "de": "PhoneGuard: Foto aufgenommen. Datei: %1$s",
    },
    "sms_command_channel_name": {
        "default": "Защита PhoneGuard",
        "en": "PhoneGuard protection",
        "ru": "Защита PhoneGuard",
        "de": "PhoneGuard-Schutz",
    },
    "sms_command_rejected_title": {
        "default": "Попытка управлять телефоном по SMS",
        "en": "Someone tried to control your phone via SMS",
        "ru": "Попытка управлять телефоном по SMS",
        "de": "Versuch, das Telefon per SMS zu steuern",
    },
    "sms_command_rejected_text": {
        "default": "Неверный PIN-код. После 5 попыток команды блокируются на 15 минут",
        "en": "Wrong PIN. After 5 attempts commands are blocked for 15 minutes",
        "ru": "Неверный PIN-код. После 5 попыток команды блокируются на 15 минут",
        "de": "Falsche PIN. Nach 5 Versuchen werden Befehle für 15 Minuten gesperrt",
    },
    "vault_preview_failed": {
        "default": "Не удалось открыть файл из сейфа",
        "en": "Could not open the file from the vault",
        "ru": "Не удалось открыть файл из сейфа",
        "de": "Datei aus dem Tresor konnte nicht geöffnet werden",
    },
    "shoulder_surfer_start_failed": {
        "default": "Не удалось запустить детектор: система запретила запуск. Попробуйте ещё раз, не сворачивая приложение",
        "en": "Could not start the detector: the system blocked the launch. Try again with the app in the foreground",
        "ru": "Не удалось запустить детектор: система запретила запуск. Попробуйте ещё раз, не сворачивая приложение",
        "de": "Detektor konnte nicht gestartet werden: Das System hat den Start blockiert. Bitte erneut bei geöffneter App versuchen",
    },
    "settings_unavailable": {
        "default": "Этот экран настроек недоступен на устройстве",
        "en": "This settings screen is not available on this device",
        "ru": "Этот экран настроек недоступен на устройстве",
        "de": "Dieser Einstellungsbildschirm ist auf diesem Gerät nicht verfügbar",
    },
    # --- Этапы полного сканирования ---
    "scan_stage_root": {
        "default": "Проверка root-доступа…",
        "en": "Checking for root access…",
        "ru": "Проверка root-доступа…",
        "de": "Prüfung auf Root-Zugriff…",
    },
    "scan_stage_integrity": {
        "default": "Проверка целостности системы…",
        "en": "Checking system integrity…",
        "ru": "Проверка целостности системы…",
        "de": "Prüfung der Systemintegrität…",
    },
    "scan_stage_files": {
        "default": "Сканирование файловой системы…",
        "en": "Scanning the file system…",
        "ru": "Сканирование файловой системы…",
        "de": "Dateisystem wird geprüft…",
    },
    "scan_stage_dev_options": {
        "default": "Проверка опций разработчика…",
        "en": "Checking developer options…",
        "ru": "Проверка опций разработчика…",
        "de": "Entwickleroptionen werden geprüft…",
    },
    "scan_stage_patch": {
        "default": "Проверка обновлений безопасности…",
        "en": "Checking security updates…",
        "ru": "Проверка обновлений безопасности…",
        "de": "Sicherheitsupdates werden geprüft…",
    },
    "scan_stage_accessibility": {
        "default": "Проверка Accessibility-сервисов…",
        "en": "Checking accessibility services…",
        "ru": "Проверка Accessibility-сервисов…",
        "de": "Bedienungshilfen werden geprüft…",
    },
    "scan_stage_overlay": {
        "default": "Проверка overlay-атак…",
        "en": "Checking for overlay attacks…",
        "ru": "Проверка overlay-атак…",
        "de": "Overlay-Angriffe werden geprüft…",
    },
    "scan_stage_permissions": {
        "default": "Анализ злоупотребления разрешениями…",
        "en": "Analysing permission abuse…",
        "ru": "Анализ злоупотребления разрешениями…",
        "de": "Missbrauch von Berechtigungen wird analysiert…",
    },
    "scan_stage_hidden_apps": {
        "default": "Поиск скрытых приложений…",
        "en": "Looking for hidden apps…",
        "ru": "Поиск скрытых приложений…",
        "de": "Versteckte Apps werden gesucht…",
    },
    "scan_stage_device_admin": {
        "default": "Проверка Device Admin…",
        "en": "Checking device administrators…",
        "ru": "Проверка Device Admin…",
        "de": "Geräteadministratoren werden geprüft…",
    },
    "scan_stage_privacy_apps": {
        "default": "Анализ приложений и разрешений…",
        "en": "Analysing apps and permissions…",
        "ru": "Анализ приложений и разрешений…",
        "de": "Apps und Berechtigungen werden analysiert…",
    },
    "scan_stage_spyware": {
        "default": "Поиск подозрительной активности…",
        "en": "Looking for suspicious activity…",
        "ru": "Поиск подозрительной активности…",
        "de": "Verdächtige Aktivitäten werden gesucht…",
    },
    "scan_stage_firewall": {
        "default": "Анализ сети и правил фаервола…",
        "en": "Analysing network and firewall rules…",
        "ru": "Анализ сети и правил фаервола…",
        "de": "Netzwerk und Firewall-Regeln werden analysiert…",
    },
    "scan_stage_apk_signatures": {
        "default": "Проверка подписей APK…",
        "en": "Checking APK signatures…",
        "ru": "Проверка подписей APK…",
        "de": "APK-Signaturen werden geprüft…",
    },
    "scan_stage_phishing": {
        "default": "Проверка подозрительных SMS-ссылок…",
        "en": "Checking suspicious SMS links…",
        "ru": "Проверка подозрительных SMS-ссылок…",
        "de": "Verdächtige SMS-Links werden geprüft…",
    },
    "scan_stage_phone_check": {
        "default": "Проверка состояния телефона…",
        "en": "Checking the phone state…",
        "ru": "Проверка состояния телефона…",
        "de": "Telefonzustand wird geprüft…",
    },
    "scan_stage_done": {
        "default": "Сканирование завершено",
        "en": "Scan complete",
        "ru": "Сканирование завершено",
        "de": "Scan abgeschlossen",
    },
    "scan_stage_already_running": {
        "default": "Сканирование уже выполняется…",
        "en": "A scan is already running…",
        "ru": "Сканирование уже выполняется…",
        "de": "Ein Scan läuft bereits…",
    },
    # --- Чек всего телефона (deviceaudit) ---
    "phone_check": {
        "default": "Проверка телефона",
        "en": "Phone check",
        "ru": "Проверка телефона",
        "de": "Telefonprüfung",
    },
    "phone_check_run": {
        "default": "Проверить весь телефон",
        "en": "Check the whole phone",
        "ru": "Проверить весь телефон",
        "de": "Ganzes Telefon prüfen",
    },
    "phone_check_running": {
        "default": "Проверяем телефон…",
        "en": "Checking the phone…",
        "ru": "Проверяем телефон…",
        "de": "Telefon wird geprüft…",
    },
    "phone_check_card_desc": {
        "default": "Система, приложения, сеть, память и разрешения",
        "en": "System, apps, network, storage and permissions",
        "ru": "Система, приложения, сеть, память и разрешения",
        "de": "System, Apps, Netzwerk, Speicher und Berechtigungen",
    },
    "phone_check_score": {
        "default": "Состояние: %1$d/100",
        "en": "State: %1$d/100",
        "ru": "Состояние: %1$d/100",
        "de": "Zustand: %1$d/100",
    },
    "phone_check_summary": {
        "default": "Проверок: %1$d • проблем: %2$d",
        "en": "Checks: %1$d • problems: %2$d",
        "ru": "Проверок: %1$d • проблем: %2$d",
        "de": "Prüfungen: %1$d • Probleme: %2$d",
    },
    "phone_check_last_run": {
        "default": "Последняя проверка: %1$s",
        "en": "Last check: %1$s",
        "ru": "Последняя проверка: %1$s",
        "de": "Letzte Prüfung: %1$s",
    },
    "phone_check_never_run": {
        "default": "Проверка ещё не выполнялась",
        "en": "No check has been run yet",
        "ru": "Проверка ещё не выполнялась",
        "de": "Es wurde noch keine Prüfung durchgeführt",
    },
    "phone_check_score_short": {
        "default": "%1$d/100 • проблем: %2$d",
        "en": "%1$d/100 • problems: %2$d",
        "ru": "%1$d/100 • проблем: %2$d",
        "de": "%1$d/100 • Probleme: %2$d",
    },
    "audit_status_ok": {
        "default": "В порядке",
        "en": "OK",
        "ru": "В порядке",
        "de": "In Ordnung",
    },
    "audit_status_info": {
        "default": "Информация",
        "en": "Info",
        "ru": "Информация",
        "de": "Information",
    },
    "audit_status_warning": {
        "default": "Внимание",
        "en": "Warning",
        "ru": "Внимание",
        "de": "Achtung",
    },
    "audit_status_critical": {
        "default": "Критично",
        "en": "Critical",
        "ru": "Критично",
        "de": "Kritisch",
    },
    "audit_category_security": {
        "default": "Безопасность",
        "en": "Security",
        "ru": "Безопасность",
        "de": "Sicherheit",
    },
    "audit_category_network": {
        "default": "Сеть",
        "en": "Network",
        "ru": "Сеть",
        "de": "Netzwerk",
    },
    "audit_category_storage": {
        "default": "Память",
        "en": "Storage",
        "ru": "Память",
        "de": "Speicher",
    },
    "audit_category_apps": {
        "default": "Приложения",
        "en": "Apps",
        "ru": "Приложения",
        "de": "Apps",
    },
    "audit_category_system": {
        "default": "Система",
        "en": "System",
        "ru": "Система",
        "de": "System",
    },
    "audit_screen_lock_title": {
        "default": "Блокировка экрана",
        "en": "Screen lock",
        "ru": "Блокировка экрана",
        "de": "Bildschirmsperre",
    },
    "audit_screen_lock_ok": {
        "default": "PIN, пароль или биометрия настроены",
        "en": "PIN, password or biometrics are set up",
        "ru": "PIN, пароль или биометрия настроены",
        "de": "PIN, Passwort oder Biometrie ist eingerichtet",
    },
    "audit_screen_lock_missing": {
        "default": "Экран не защищён паролем",
        "en": "The screen is not protected by a password",
        "ru": "Экран не защищён паролем",
        "de": "Der Bildschirm ist nicht durch ein Passwort geschützt",
    },
    "audit_screen_lock_rec": {
        "default": "Настройки > Безопасность > Блокировка экрана",
        "en": "Settings > Security > Screen lock",
        "ru": "Настройки > Безопасность > Блокировка экрана",
        "de": "Einstellungen > Sicherheit > Bildschirmsperre",
    },
    "audit_encryption_title": {
        "default": "Шифрование памяти",
        "en": "Storage encryption",
        "ru": "Шифрование памяти",
        "de": "Speicherverschlüsselung",
    },
    "audit_encryption_ok": {
        "default": "Данные устройства зашифрованы",
        "en": "Device data is encrypted",
        "ru": "Данные устройства зашифрованы",
        "de": "Gerätedaten sind verschlüsselt",
    },
    "audit_encryption_missing": {
        "default": "Шифрование памяти не активно",
        "en": "Storage encryption is not active",
        "ru": "Шифрование памяти не активно",
        "de": "Speicherverschlüsselung ist nicht aktiv",
    },
    "audit_encryption_rec": {
        "default": "Сделайте резервную копию и включите шифрование в настройках безопасности",
        "en": "Back up your data and enable encryption in security settings",
        "ru": "Сделайте резервную копию и включите шифрование в настройках безопасности",
        "de": "Sichern Sie Ihre Daten und aktivieren Sie die Verschlüsselung in den Sicherheitseinstellungen",
    },
    "audit_selinux_title": {
        "default": "SELinux",
        "en": "SELinux",
        "ru": "SELinux",
        "de": "SELinux",
    },
    "audit_selinux_enforcing": {
        "default": "Режим enforcing",
        "en": "Enforcing mode",
        "ru": "Режим enforcing",
        "de": "Enforcing-Modus",
    },
    "audit_selinux_permissive": {
        "default": "Режим не enforcing — возможен root",
        "en": "Not in enforcing mode — root access may be present",
        "ru": "Режим не enforcing — возможен root",
        "de": "Nicht im Enforcing-Modus — Root-Zugriff möglich",
    },
    "audit_selinux_rec": {
        "default": "Проверьте устройство: изменение режима SELinux обычно означает root/кастомную прошивку",
        "en": "Check the device: changing the SELinux mode usually means root/custom firmware",
        "ru": "Проверьте устройство: изменение режима SELinux обычно означает root/кастомную прошивку",
        "de": "Prüfen Sie das Gerät: ein geänderter SELinux-Modus deutet auf Root/Custom-ROM hin",
    },
    "audit_unknown_sources_title": {
        "default": "Установка из неизвестных источников",
        "en": "Install from unknown sources",
        "ru": "Установка из неизвестных источников",
        "de": "Installation aus unbekannten Quellen",
    },
    "audit_unknown_sources_on": {
        "default": "Разрешена (в старых версиях Android)",
        "en": "Allowed (on older Android versions)",
        "ru": "Разрешена (в старых версиях Android)",
        "de": "Erlaubt (auf älteren Android-Versionen)",
    },
    "audit_unknown_sources_off": {
        "default": "Запрещена",
        "en": "Blocked",
        "ru": "Запрещена",
        "de": "Blockiert",
    },
    "audit_unknown_sources_rec": {
        "default": "Запретите установку APK из неизвестных источников",
        "en": "Disallow installing APKs from unknown sources",
        "ru": "Запретите установку APK из неизвестных источников",
        "de": "Deaktivieren Sie die Installation von APKs aus unbekannten Quellen",
    },
    "audit_adb_title": {
        "default": "Отладка по ADB",
        "en": "ADB debugging",
        "ru": "Отладка по ADB",
        "de": "ADB-Debugging",
    },
    "audit_adb_on": {
        "default": "USB-отладка или ADB по Wi-Fi включены",
        "en": "USB debugging or ADB over Wi-Fi is enabled",
        "ru": "USB-отладка или ADB по Wi-Fi включены",
        "de": "USB-Debugging oder ADB über WLAN ist aktiviert",
    },
    "audit_adb_off": {
        "default": "Выключена",
        "en": "Disabled",
        "ru": "Выключена",
        "de": "Deaktiviert",
    },
    "audit_adb_rec": {
        "default": "Отключите: Настройки > Для разработчиков > Отладка по USB",
        "en": "Disable it: Settings > Developer options > USB debugging",
        "ru": "Отключите: Настройки > Для разработчиков > Отладка по USB",
        "de": "Deaktivieren: Einstellungen > Entwickleroptionen > USB-Debugging",
    },
    "audit_dev_options_title": {
        "default": "Для разработчиков",
        "en": "Developer options",
        "ru": "Для разработчиков",
        "de": "Entwickleroptionen",
    },
    "audit_dev_options_on": {
        "default": "Включены",
        "en": "Enabled",
        "ru": "Включены",
        "de": "Aktiviert",
    },
    "audit_dev_options_off": {
        "default": "Выключены",
        "en": "Disabled",
        "ru": "Выключены",
        "de": "Deaktiviert",
    },
    "audit_patch_title": {
        "default": "Патч безопасности",
        "en": "Security patch",
        "ru": "Патч безопасности",
        "de": "Sicherheitspatch",
    },
    "audit_patch_detail": {
        "default": "Уровень: %1$s, месяцев с выпуска: %2$s",
        "en": "Level: %1$s, months since release: %2$s",
        "ru": "Уровень: %1$s, месяцев с выпуска: %2$s",
        "de": "Stand: %1$s, Monate seit Veröffentlichung: %2$s",
    },
    "audit_patch_unknown": {
        "default": "Уровень патча неизвестен",
        "en": "Patch level is unknown",
        "ru": "Уровень патча неизвестен",
        "de": "Patch-Stand unbekannt",
    },
    "audit_patch_rec": {
        "default": "Установите обновление: Настройки > О телефоне > Обновление ПО",
        "en": "Install the update: Settings > About phone > System update",
        "ru": "Установите обновление: Настройки > О телефоне > Обновление ПО",
        "de": "Update installieren: Einstellungen > Über das Telefon > Systemupdate",
    },
    "audit_verifier_title": {
        "default": "Проверка приложений (Play Protect)",
        "en": "App verification (Play Protect)",
        "ru": "Проверка приложений (Play Protect)",
        "de": "App-Überprüfung (Play Protect)",
    },
    "audit_verifier_on": {
        "default": "Включена",
        "en": "Enabled",
        "ru": "Включена",
        "de": "Aktiviert",
    },
    "audit_verifier_off": {
        "default": "Выключена",
        "en": "Disabled",
        "ru": "Выключена",
        "de": "Deaktiviert",
    },
    "audit_verifier_rec": {
        "default": "Включите проверку приложений в настройках Play Маркета",
        "en": "Enable app verification in Google Play settings",
        "ru": "Включите проверку приложений в настройках Play Маркета",
        "de": "Aktivieren Sie die App-Überprüfung in den Play-Store-Einstellungen",
    },
    "audit_vpn_title": {
        "default": "VPN",
        "en": "VPN",
        "ru": "VPN",
        "de": "VPN",
    },
    "audit_vpn_on": {
        "default": "Активное VPN-подключение",
        "en": "Active VPN connection",
        "ru": "Активное VPN-подключение",
        "de": "Aktive VPN-Verbindung",
    },
    "audit_vpn_off": {
        "default": "VPN не используется",
        "en": "No VPN in use",
        "ru": "VPN не используется",
        "de": "Kein VPN in Verwendung",
    },
    "audit_storage_title": {
        "default": "Свободное место",
        "en": "Free storage",
        "ru": "Свободное место",
        "de": "Freier Speicher",
    },
    "audit_storage_detail": {
        "default": "Свободно: %1$s ГБ",
        "en": "Free: %1$s GB",
        "ru": "Свободно: %1$s ГБ",
        "de": "Frei: %1$s GB",
    },
    "audit_storage_rec": {
        "default": "Освободите место: без него не устанавливаются обновления безопасности",
        "en": "Free up space — security updates cannot install without it",
        "ru": "Освободите место: без него не устанавливаются обновления безопасности",
        "de": "Speicher freigeben — sonst lassen sich Sicherheitsupdates nicht installieren",
    },
    "audit_apps_count_title": {
        "default": "Установленные приложения",
        "en": "Installed apps",
        "ru": "Установленные приложения",
        "de": "Installierte Apps",
    },
    "audit_apps_count_detail": {
        "default": "Всего: %1$s",
        "en": "Total: %1$s",
        "ru": "Всего: %1$s",
        "de": "Gesamt: %1$s",
    },
    "audit_outdated_title": {
        "default": "Устаревшие приложения",
        "en": "Outdated apps",
        "ru": "Устаревшие приложения",
        "de": "Veraltete Apps",
    },
    "audit_outdated_detail": {
        "default": "Со старой targetSdk: %1$s",
        "en": "With old targetSdk: %1$s",
        "ru": "Со старой targetSdk: %1$s",
        "de": "Mit alter targetSdk: %1$s",
    },
    "audit_outdated_rec": {
        "default": "Обновите приложения: старые версии не получают исправления безопасности",
        "en": "Update the apps — old versions do not receive security fixes",
        "ru": "Обновите приложения: старые версии не получают исправления безопасности",
        "de": "Aktualisieren Sie die Apps — alte Versionen erhalten keine Sicherheitsfixes",
    },
    "audit_debuggable_title": {
        "default": "Отлаживаемые приложения",
        "en": "Debuggable apps",
        "ru": "Отлаживаемые приложения",
        "de": "Debug-fähige Apps",
    },
    "audit_debuggable_detail": {
        "default": "Найдено: %1$s",
        "en": "Found: %1$s",
        "ru": "Найдено: %1$s",
        "de": "Gefunden: %1$s",
    },
    "audit_debuggable_rec": {
        "default": "Удалите такие приложения: их код и данные доступны через отладку",
        "en": "Remove such apps — their code and data are exposed to debugging",
        "ru": "Удалите такие приложения: их код и данные доступны через отладку",
        "de": "Entfernen Sie solche Apps — Code und Daten sind über Debugging angreifbar",
    },
    "audit_accessibility_title": {
        "default": "Службы доступности",
        "en": "Accessibility services",
        "ru": "Службы доступности",
        "de": "Bedienungshilfen",
    },
    "audit_accessibility_detail": {
        "default": "Включено: %1$s",
        "en": "Enabled: %1$s",
        "ru": "Включено: %1$s",
        "de": "Aktiviert: %1$s",
    },
    "audit_accessibility_rec": {
        "default": "Проверьте список: через доступность вредоносные приложения читают экран и ввод",
        "en": "Review the list — malware reads the screen and input through accessibility",
        "ru": "Проверьте список: через доступность вредоносные приложения читают экран и ввод",
        "de": "Prüfen Sie die Liste — Malware liest über Bedienungshilfen Bildschirm und Eingaben",
    },
    "audit_notifications_title": {
        "default": "Уведомления",
        "en": "Notifications",
        "ru": "Уведомления",
        "de": "Benachrichtigungen",
    },
    "audit_notifications_on": {
        "default": "Разрешены",
        "en": "Allowed",
        "ru": "Разрешены",
        "de": "Erlaubt",
    },
    "audit_notifications_off": {
        "default": "Запрещены — оповещения о блокировках не придут",
        "en": "Blocked — block alerts will not arrive",
        "ru": "Запрещены — оповещения о блокировках не придут",
        "de": "Blockiert — Sperrwarnungen kommen nicht an",
    },
    "audit_notifications_rec": {
        "default": "Разрешите уведомления в настройках приложения",
        "en": "Allow notifications in the app settings",
        "ru": "Разрешите уведомления в настройках приложения",
        "de": "Erlauben Sie Benachrichtigungen in den App-Einstellungen",
    },
    "audit_battery_title": {
        "default": "Энергосбережение",
        "en": "Battery optimisation",
        "ru": "Энергосбережение",
        "de": "Akku-Optimierung",
    },
    "audit_battery_ok": {
        "default": "Приложение исключено из оптимизации",
        "en": "The app is exempt from optimisation",
        "ru": "Приложение исключено из оптимизации",
        "de": "Die App ist von der Optimierung ausgenommen",
    },
    "audit_battery_warning": {
        "default": "Система может останавливать защиту в фоне",
        "en": "The system may stop protection in the background",
        "ru": "Система может останавливать защиту в фоне",
        "de": "Das System kann den Schutz im Hintergrund beenden",
    },
    "audit_battery_rec": {
        "default": "Отключите оптимизацию батареи для PhoneGuard",
        "en": "Disable battery optimisation for PhoneGuard",
        "ru": "Отключите оптимизацию батареи для PhoneGuard",
        "de": "Deaktivieren Sie die Akku-Optimierung für PhoneGuard",
    },
    "audit_core_permissions_title": {
        "default": "Ключевые разрешения",
        "en": "Core permissions",
        "ru": "Ключевые разрешения",
        "de": "Wichtige Berechtigungen",
    },
    "audit_core_permissions_ok": {
        "default": "Все необходимые разрешения выданы",
        "en": "All required permissions are granted",
        "ru": "Все необходимые разрешения выданы",
        "de": "Alle erforderlichen Berechtigungen sind erteilt",
    },
    "audit_core_permissions_missing": {
        "default": "Не выдано: %1$s из %2$s",
        "en": "Not granted: %1$s of %2$s",
        "ru": "Не выдано: %1$s из %2$s",
        "de": "Nicht erteilt: %1$s von %2$s",
    },
    "audit_core_permissions_rec": {
        "default": "Выдайте разрешения: без них анти-вор и блокировщик звонков не работают",
        "en": "Grant the permissions — anti-theft and the call blocker do not work without them",
        "ru": "Выдайте разрешения: без них анти-вор и блокировщик звонков не работают",
        "de": "Erteilen Sie die Berechtigungen — ohne sie funktionieren Diebstahlschutz und Anrufblocker nicht",
    },
    "audit_device_admins_title": {
        "default": "Администраторы устройства",
        "en": "Device administrators",
        "ru": "Администраторы устройства",
        "de": "Geräteadministratoren",
    },
    "audit_device_admins_detail": {
        "default": "Активно: %1$s",
        "en": "Active: %1$s",
        "ru": "Активно: %1$s",
        "de": "Aktiv: %1$s",
    },
    "audit_device_admins_rec": {
        "default": "Проверьте список: лишние администраторы мешают удалить вредоносное приложение",
        "en": "Review the list — extra administrators prevent removing malware",
        "ru": "Проверьте список: лишние администраторы мешают удалить вредоносное приложение",
        "de": "Prüfen Sie die Liste — zusätzliche Administratoren verhindern das Entfernen von Malware",
    },
    "audit_os_version_title": {
        "default": "Версия Android",
        "en": "Android version",
        "ru": "Версия Android",
        "de": "Android-Version",
    },
    "audit_os_version_detail": {
        "default": "Android %1$s (API %2$s)",
        "en": "Android %1$s (API %2$s)",
        "ru": "Android %1$s (API %2$s)",
        "de": "Android %1$s (API %2$s)",
    },
    "audit_device_model_title": {
        "default": "Модель устройства",
        "en": "Device model",
        "ru": "Модель устройства",
        "de": "Gerätemodell",
    },
    "audit_device_model_detail": {
        "default": "%1$s",
        "en": "%1$s",
        "ru": "%1$s",
        "de": "%1$s",
    },
    "shoulder_surfer_channel_name": {
        "default": "Защита от подглядывания",
        "en": "Shoulder surfing protection",
        "ru": "Защита от подглядывания",
        "de": "Schutz vor Blickdiebstahl",
    },
    "shoulder_surfer_channel_desc": {
        "default": "Мониторинг окружения для обнаружения подглядывания",
        "en": "Monitors the surroundings to detect someone looking at the screen",
        "ru": "Мониторинг окружения для обнаружения подглядывания",
        "de": "Überwacht die Umgebung, um Mitlesen zu erkennen",
    },
    "shoulder_surfer_alert_channel_name": {
        "default": "Оповещения о подглядывании",
        "en": "Shoulder surfing alerts",
        "ru": "Оповещения о подглядывании",
        "de": "Warnungen vor Mitlesen",
    },
    "shoulder_surfer_alert_channel_desc": {
        "default": "Уведомления при обнаружении посторонних за экраном",
        "en": "Notifications when someone else is detected looking at the screen",
        "ru": "Уведомления при обнаружении посторонних за экраном",
        "de": "Benachrichtigungen, wenn jemand anderes auf den Bildschirm sieht",
    },
    "shoulder_surfer_active_title": {
        "default": "Защита от подглядывания активна",
        "en": "Shoulder surfing protection is active",
        "ru": "Защита от подглядывания активна",
        "de": "Schutz vor Blickdiebstahl ist aktiv",
    },
    "shoulder_surfer_active_text": {
        "default": "Камера анализирует окружение…",
        "en": "The camera is analysing the surroundings…",
        "ru": "Камера анализирует окружение…",
        "de": "Die Kamera analysiert die Umgebung…",
    },
    "intrusion_notification_text": {
        "default": "Обработка угрозы",
        "en": "Handling threat",
        "ru": "Обработка угрозы",
        "de": "Bedrohung wird behandelt",
    },
    "unknown_location": {
        "default": "неизвестная локация",
        "en": "unknown location",
        "ru": "неизвестная локация",
        "de": "unbekannter Ort",
    },
    "sim_swap_sms_message": {
        "default": "SIM вашего телефона поменялась %1$s в локации %2$s. Подтвердите это срочной ссылкой/кодом, иначе приложение заблокируется через 5 минут.",
        "en": "The SIM card of your phone was changed on %1$s at %2$s. Confirm it with the emergency link/code, otherwise the app will lock in 5 minutes.",
        "ru": "SIM вашего телефона поменялась %1$s в локации %2$s. Подтвердите это срочной ссылкой/кодом, иначе приложение заблокируется через 5 минут.",
        "de": "Die SIM-Karte Ihres Telefons wurde am %1$s am Ort %2$s gewechselt. Bestätigen Sie dies mit dem Notfall-Link/Code, sonst wird die App in 5 Minuten gesperrt.",
    },
    "intrusion_sms_suspicious_activity": {
        "default": "Обнаружена попытка взлома",
        "en": "An intrusion attempt was detected",
        "ru": "Обнаружена попытка взлома",
        "de": "Ein Eindringversuch wurde erkannt",
    },
    "intrusion_sms_critical": {
        "default": "Критическая попытка взлома",
        "en": "Critical intrusion attempt",
        "ru": "Критическая попытка взлома",
        "de": "Kritischer Eindringversuch",
    },
    "intrusion_warning_suspicious": {
        "default": "Подозрительная активность обнаружена",
        "en": "Suspicious activity detected",
        "ru": "Подозрительная активность обнаружена",
        "de": "Verdächtige Aktivität erkannt",
    },
    "intrusion_warning_blocked": {
        "default": "Устройство защищено. Попытка взлома заблокирована",
        "en": "The device is protected. The intrusion attempt was blocked",
        "ru": "Устройство защищено. Попытка взлома заблокирована",
        "de": "Das Gerät ist geschützt. Der Eindringversuch wurde blockiert",
    },
    "backup_number_saved": {
        "default": "Резервный номер сохранён",
        "en": "Backup number saved",
        "ru": "Резервный номер сохранён",
        "de": "Sicherungsnummer gespeichert",
    },
    "shoulder_surfer_enabled": {
        "default": "Защита от подглядывания включена",
        "en": "Shoulder surfing protection enabled",
        "ru": "Защита от подглядывания включена",
        "de": "Schutz vor Blickdiebstahl aktiviert",
    },
    "shoulder_surfer_disabled": {
        "default": "Защита от подглядывания выключена",
        "en": "Shoulder surfing protection disabled",
        "ru": "Защита от подглядывания выключена",
        "de": "Schutz vor Blickdiebstahl deaktiviert",
    },
    "alarm_start_failed": {
        "default": "Не удалось запустить сигнализацию",
        "en": "Failed to start the alarm",
        "ru": "Не удалось запустить сигнализацию",
        "de": "Alarm konnte nicht gestartet werden",
    },
    "remote_wipe_hint": {
        "default": "Удалённое стирание выполняется SMS-командой #PG:PIN:WIPE (требуется активный Device Admin)",
        "en": "Remote wipe is performed with the SMS command #PG:PIN:WIPE (requires active Device Admin)",
        "ru": "Удалённое стирание выполняется SMS-командой #PG:PIN:WIPE (требуется активный Device Admin)",
        "de": "Ferngelöschtes Formatieren erfolgt per SMS-Befehl #PG:PIN:WIPE (aktiver Device Admin erforderlich)",
    },
    "remote_lock_hint": {
        "default": "Удалённая блокировка выполняется SMS-командой #PG:PIN:LOCK",
        "en": "Remote lock is performed with the SMS command #PG:PIN:LOCK",
        "ru": "Удалённая блокировка выполняется SMS-командой #PG:PIN:LOCK",
        "de": "Ferngesteuerte Sperre erfolgt per SMS-Befehl #PG:PIN:LOCK",
    },
    "export_logs_saved": {
        "default": "Экспорт сохранён: %1$s",
        "en": "Export saved: %1$s",
        "ru": "Экспорт сохранён: %1$s",
        "de": "Export gespeichert: %1$s",
    },
    "export_logs_failed": {
        "default": "Не удалось экспортировать логи",
        "en": "Failed to export logs",
        "ru": "Не удалось экспортировать логи",
        "de": "Protokolle konnten nicht exportiert werden",
    },
    "vault_unlock_subtitle": {
        "default": "Используйте отпечаток пальца или Face ID",
        "en": "Use your fingerprint or face unlock",
        "ru": "Используйте отпечаток пальца или Face ID",
        "de": "Fingerabdruck oder Gesichtserkennung verwenden",
    },
    "vault_auth_failed": {
        "default": "Аутентификация не удалась",
        "en": "Authentication failed",
        "ru": "Аутентификация не удалась",
        "de": "Authentifizierung fehlgeschlagen",
    },
    "vault_biometric_unavailable": {
        "default": "Биометрия недоступна. Настройте отпечаток пальца или Face ID в системных настройках.",
        "en": "Biometrics unavailable. Set up a fingerprint or face unlock in system settings.",
        "ru": "Биометрия недоступна. Настройте отпечаток пальца или Face ID в системных настройках.",
        "de": "Biometrie nicht verfügbar. Richten Sie Fingerabdruck oder Gesichtserkennung in den Systemeinstellungen ein.",
    },
    "vault_auth_not_possible": {
        "default": "Невозможно выполнить биометрическую аутентификацию",
        "en": "Biometric authentication cannot be performed",
        "ru": "Невозможно выполнить биометрическую аутентификацию",
        "de": "Biometrische Authentifizierung nicht möglich",
    },
    "vault_file_too_large": {
        "default": "Файл слишком большой. Максимальный размер: %1$d МБ",
        "en": "File is too large. Maximum size: %1$d MB",
        "ru": "Файл слишком большой. Максимальный размер: %1$d МБ",
        "de": "Datei ist zu groß. Maximale Größe: %1$d MB",
    },
    "vault_import_failed": {
        "default": "Ошибка импорта: %1$s",
        "en": "Import failed: %1$s",
        "ru": "Ошибка импорта: %1$s",
        "de": "Import fehlgeschlagen: %1$s",
    },
    "severity_warning": {
        "default": "Предупреждение",
        "en": "Warning",
        "ru": "Предупреждение",
        "de": "Warnung",
    },
    "severity_info": {
        "default": "Информация",
        "en": "Info",
        "ru": "Информация",
        "de": "Information",
    },
    "app_not_found": {
        "default": "Приложение не найдено",
        "en": "App not found",
        "ru": "Приложение не найдено",
        "de": "App nicht gefunden",
    },
    "package_name": {
        "default": "Пакет: %1$s",
        "en": "Package: %1$s",
        "ru": "Пакет: %1$s",
        "de": "Paket: %1$s",
    },
    "call_blocked_hidden_number": {
        "default": "Скрытый номер",
        "en": "Hidden number",
        "ru": "Скрытый номер",
        "de": "Unbekannte Nummer",
    },
    "block_domains_ips_title": {
        "default": "Блокировка доменов и IP",
        "en": "Block domains and IPs",
        "ru": "Блокировка доменов и IP",
        "de": "Domains und IPs blockieren",
    },
    "block_domains_hint": {
        "default": "Домены через запятую",
        "en": "Domains (comma separated)",
        "ru": "Домены через запятую",
        "de": "Domains (kommagetrennt)",
    },
    "block_ips_hint": {
        "default": "IP-адреса через запятую",
        "en": "IPs (comma separated)",
        "ru": "IP-адреса через запятую",
        "de": "IPs (kommagetrennt)",
    },
    "block_all": {
        "default": "Блокировать всё",
        "en": "Block all",
        "ru": "Блокировать всё",
        "de": "Alles blockieren",
    },
    "wifi": {
        "default": "Wi-Fi",
        "en": "Wi-Fi",
        "ru": "Wi-Fi",
        "de": "WLAN",
    },
    "mobile_data": {
        "default": "Мобильные данные",
        "en": "Mobile data",
        "ru": "Мобильные данные",
        "de": "Mobile Daten",
    },
    "threat_intel_title": {
        "default": "Проверка доменов (threat-intel)",
        "en": "Domain reputation check (threat intel)",
        "ru": "Проверка доменов (threat-intel)",
        "de": "Domain-Reputation prüfen (Threat Intelligence)",
    },
    "threat_intel_description": {
        "default": "Проверять домены из подозрительных SMS в публичных базах URLhaus и OpenPhish. Наружу отправляется только имя домена — без текста сообщений и номеров. По умолчанию выключено.",
        "en": "Check domains from suspicious SMS against the public URLhaus and OpenPhish databases. Only the host name leaves the device - never message text or phone numbers. Off by default.",
        "ru": "Проверять домены из подозрительных SMS в публичных базах URLhaus и OpenPhish. Наружу отправляется только имя домена — без текста сообщений и номеров. По умолчанию выключено.",
        "de": "Domains aus verdächtigen SMS gegen die öffentlichen Datenbanken URLhaus und OpenPhish prüfen. Das Gerät verlässt nur der Hostname - kein Nachrichtentext, keine Telefonnummern. Standardmäßig aus.",
    },
    "threat_intel_opt_in_note": {
        "default": "Включение делает сетевые запросы к urlhaus-api.abuse.ch и openphish.com.",
        "en": "Enabling this makes network requests to urlhaus-api.abuse.ch and openphish.com.",
        "ru": "Включение делает сетевые запросы к urlhaus-api.abuse.ch и openphish.com.",
        "de": "Beim Aktivieren werden Netzwerkanfragen an urlhaus-api.abuse.ch und openphish.com gesendet.",
    },
    "phone_number_invalid": {
        "default": "Некорректный номер телефона",
        "en": "Invalid phone number",
        "ru": "Некорректный номер телефона",
        "de": "Ungültige Telefonnummer",
    },
    "apk_signature_check": {
        "default": "Проверка подписей APK",
        "en": "APK signature check",
        "ru": "Проверка подписей APK",
        "de": "APK-Signaturprüfung",
    },
    "indicator_debug_signature": {
        "default": "Подпись debug-ключом",
        "en": "Signed with a debug key",
        "ru": "Подпись debug-ключом",
        "de": "Mit Debug-Schlüssel signiert",
    },
    "indicator_weak_signature": {
        "default": "Слабый алгоритм подписи",
        "en": "Weak signature algorithm",
        "ru": "Слабый алгоритм подписи",
        "de": "Schwacher Signaturalgorithmus",
    },
    "indicator_expired_certificate": {
        "default": "Сертификат подписи истёк",
        "en": "Signing certificate expired",
        "ru": "Сертификат подписи истёк",
        "de": "Signaturzertifikat abgelaufen",
    },
    "indicator_modified_after_install": {
        "default": "APK изменён после установки",
        "en": "APK modified after install",
        "ru": "APK изменён после установки",
        "de": "APK nach der Installation verändert",
    },
    "new_app_channel_name": {
        "default": "Новые приложения",
        "en": "New apps",
        "ru": "Новые приложения",
        "de": "Neue Apps",
    },
    "new_app_installed_title": {
        "default": "Установлено новое приложение",
        "en": "New app installed",
        "ru": "Установлено новое приложение",
        "de": "Neue App installiert",
    },
    "new_app_suspicious_title": {
        "default": "Подозрительная подпись приложения",
        "en": "Suspicious app signature",
        "ru": "Подозрительная подпись приложения",
        "de": "Verdächtige App-Signatur",
    },
    "new_app_suspicious_text": {
        "default": "%1$s: подпись debug-ключом или слабым алгоритмом",
        "en": "%1$s: signed with a debug key or a weak algorithm",
        "ru": "%1$s: подпись debug-ключом или слабым алгоритмом",
        "de": "%1$s: mit Debug-Schlüssel oder schwachem Algorithmus signiert",
    },
    "scan_report_title": {
        "default": "Отчёт о сканировании",
        "en": "Scan report",
        "ru": "Отчёт о сканировании",
        "de": "Scan-Bericht",
    },
    "risk_label": {
        "default": "Риск: %1$d/100",
        "en": "Risk: %1$d/100",
        "ru": "Риск: %1$d/100",
        "de": "Risiko: %1$d/100",
    },
    "issues_found_label": {
        "default": "Найдено проблем: %1$d",
        "en": "Issues found: %1$d",
        "ru": "Найдено проблем: %1$d",
        "de": "Gefundene Probleme: %1$d",
    },
    "issues_short": {
        "default": "Проблем: %1$d",
        "en": "Issues: %1$d",
        "ru": "Проблем: %1$d",
        "de": "Probleme: %1$d",
    },
    "report_details": {
        "default": "Детали отчёта",
        "en": "Report details",
        "ru": "Детали отчёта",
        "de": "Berichtsdetails",
    },
    "scan_in_progress": {
        "default": "Сканирование…",
        "en": "Scanning…",
        "ru": "Сканирование…",
        "de": "Wird gescannt…",
    },
    "scan_too_soon": {
        "default": "Слишком рано: прошлое сканирование выполнено недавно",
        "en": "Too soon: the last scan ran recently",
        "ru": "Слишком рано: прошлое сканирование выполнено недавно",
        "de": "Zu früh: Der letzte Scan lief erst vor Kurzem",
    },
    "scan_already_running": {
        "default": "Сканирование уже выполняется",
        "en": "A scan is already running",
        "ru": "Сканирование уже выполняется",
        "de": "Ein Scan läuft bereits",
    },
    "scan_date_label": {
        "default": "Дата: %1$s",
        "en": "Date: %1$s",
        "ru": "Дата: %1$s",
        "de": "Datum: %1$s",
    },
    "firewall_channel_description": {
        "default": "Мониторинг сетевого трафика (VPN)",
        "en": "Network traffic monitoring (VPN)",
        "ru": "Мониторинг сетевого трафика (VPN)",
        "de": "Netzwerkverkehr-Überwachung (VPN)",
    },
    "firewall_no_blocked_apps": {
        "default": "Нет приложений для блокировки",
        "en": "No apps to block",
        "ru": "Нет приложений для блокировки",
        "de": "Keine Apps zu blockieren",
    },
    "firewall_limited_mode_notice": {
        "default": "Фаерволл работает в ограниченном режиме. Блокировка на уровне приложений не обеспечивает 100% защиту без root-доступа.",
        "en": "The firewall runs in limited mode. App-level blocking cannot guarantee 100% protection without root.",
        "ru": "Фаерволл работает в ограниченном режиме. Блокировка на уровне приложений не обеспечивает 100% защиту без root-доступа.",
        "de": "Die Firewall läuft im eingeschränkten Modus. App-Blockierung bietet ohne Root keinen 100%-Schutz.",
    },
    "honeypot_accessed_title": {
        "default": "Файл-приманка открыт",
        "en": "Honeypot file opened",
        "ru": "Файл-приманка открыт",
        "de": "Honeypot-Datei geöffnet",
    },
    "honeypot_accessed_text": {
        "default": "%1$s: другое приложение получило доступ к файлу-приманке. Возможна вредоносная активность.",
        "en": "%1$s: another app accessed a honeypot file. Possible malicious activity.",
        "ru": "%1$s: другое приложение получило доступ к файлу-приманке. Возможна вредоносная активность.",
        "de": "%1$s: Eine andere App hat eine Honeypot-Datei geöffnet. Mögliche schädliche Aktivität.",
    },
    "firewall_limited_mode_badge": {
        "default": "MVP режим",
        "en": "MVP mode",
        "ru": "MVP режим",
        "de": "MVP-Modus",
    },
    "delete_number_title": {
        "default": "Удалить номер?",
        "en": "Delete number?",
        "ru": "Удалить номер?",
        "de": "Nummer löschen?",
    },
    "delete_file_title": {
        "default": "Удалить файл?",
        "en": "Delete file?",
        "ru": "Удалить файл?",
        "de": "Datei löschen?",
    },
    "delete_irreversible": {
        "default": "Это действие нельзя отменить.",
        "en": "This action cannot be undone.",
        "ru": "Это действие нельзя отменить.",
        "de": "Diese Aktion kann nicht rückgängig gemacht werden.",
    },
    "vault_empty": {
        "default": "Хранилище пусто",
        "en": "Vault is empty",
        "ru": "Хранилище пусто",
        "de": "Tresor ist leer",
    },
    "scan_not_performed_yet": {
        "default": "Сканирование ещё не выполнялось",
        "en": "No scan has been run yet",
        "ru": "Сканирование ещё не выполнялось",
        "de": "Es wurde noch kein Scan durchgeführt",
    },
    "intrusion_auto_response_short": {
        "default": "Автоответ",
        "en": "Auto-response",
        "ru": "Автоответ",
        "de": "Auto-Reaktion",
    },
    "firewall_lists_not_enforced_note": {
        "default": "Списки доменов и IP сохраняются, но без upstream-форвардинга VPN они не блокируются. Полная блокировка приложения работает всегда.",
        "en": "Domain and IP lists are stored, but without an upstream VPN forward they are not enforced. Full app blocking always works.",
        "ru": "Списки доменов и IP сохраняются, но без upstream-форвардинга VPN они не блокируются. Полная блокировка приложения работает всегда.",
        "de": "Domain- und IP-Listen werden gespeichert, ohne VPN-Weiterleitung aber nicht durchgesetzt. Die vollständige App-Blockierung funktioniert immer.",
    },
}

LOCALES = {
    "default": RES / "values/strings.xml",
    "en": RES / "values-en/strings.xml",
    "ru": RES / "values-ru/strings.xml",
    "de": RES / "values-de/strings.xml",
}


def android_escape(value: str) -> str:
    """XML-экранирование + апостроф, как требует aapt2.

    `aapt2 compile` падает с «unescaped apostrophe in string», если в `<string>`
    оставить голый `'` (типичная ошибка в английских/немецких строках вида
    «'which su' succeeded»). Поэтому апостроф всегда пишется как `\'`.
    """
    escaped = escape(value)
    return escaped.replace("\\'", "'").replace("'", "\\'")


def pending_keys(path: Path, values: dict[str, str]) -> list[str]:
    """Ключи из словаря, которых ещё нет в файле (без записи на диск)."""
    if not path.exists():
        return []
    text = path.read_text(encoding="utf-8")
    return [key for key in values if f'name="{key}"' not in text]


def add_strings(path: Path, values: dict[str, str]) -> list[str]:
    added = pending_keys(path, values)
    if not added:
        return added
    text = path.read_text(encoding="utf-8")
    block = [f'    <string name="{key}">{android_escape(values[key])}</string>' for key in added]
    text = text.replace("</resources>", "\n".join(block) + "\n</resources>")
    path.write_text(text, encoding="utf-8")
    return added


def main(argv: list[str] | None = None) -> int:
    """Без аргументов — дописывает строки; `--check` — только сообщает, чего не хватает.

    `--check` нужен CI и `tools/check_all.sh`: сравнение `git diff` ломается, если у
    разработчика есть незакоммиченные правки строк, а проверка «ничего не добавилось»
    работает всегда.
    """
    args = sys.argv[1:] if argv is None else argv
    check_only = "--check" in args
    if args and not check_only:
        print(f"usage: {Path(sys.argv[0]).name} [-h|--check]", file=sys.stderr)
        return 2

    missing_total = 0
    for locale, path in LOCALES.items():
        values = {key: translations[locale] for key, translations in STRINGS.items()}
        if check_only:
            missing = pending_keys(path, values)
            missing_total += len(missing)
            status = "ok" if not missing else f"не хватает {len(missing)}"
            print(f"{path.relative_to(ROOT)}: {status}")
            for key in missing:
                print(f"    {key}")
            continue
        added = add_strings(path, values)
        print(f"{path.relative_to(ROOT)}: +{len(added)}")
        for key in added:
            print(f"    {key}")

    if check_only and missing_total:
        print(
            f"error: {missing_total} строк отсутствует — запустите python3 tools/sync_strings.py",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
