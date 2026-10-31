# Project Summary: PhoneGuard (Защитник)

> Обновлено 2026-10-31. Файл — краткая шпаргалка, а не источник истины:
> актуальные детали в `README.md`, `docs/DEVELOPMENT.md` и `docs/CHANGELOG.md`.

## Overall Goal
Android-приложение для защиты устройства: полное сканирование, чек всего телефона,
локальный VPN-брандмауэр без root, анти-воровские SMS-команды, блокировка звонков/SMS
и шифрованный сейф с биометрией.

## Key Knowledge
- **Tech stack:** Kotlin 2.1.0, Jetpack Compose (Material 3), Hilt 2.55, Room 2.7.2 + SQLCipher for Android,
  WorkManager, Coroutines; Gradle 8.9, AGP 8.5.2, JDK 17, `minSdk 26`, `compileSdk`/`targetSdk` 35,
  flavor'ы `staging` / `production`.
- **Architecture:** `model` → `data/repository` (Room/DataStore) → `ui/screens` (Compose + ViewModel),
  Hilt-модули в `di/`.
- **Полное сканирование:** 16 шагов (`fullscan/ScanStage`) — root, целостность, файловая система,
  режим разработчика, патч-безопасности, accessibility, overlay, разрешения, скрытые приложения,
  device admin, приватность, шпионское ПО, брандмауэр, подписи APK, фишинг, чек телефона.
- **Чек телефона:** 20 проверок (`deviceaudit/DeviceAuditChecker`), оценка 0..100.
- **Брандмауэр:** `VpnService` + IPv4/TCP/UDP-парсинг, блокировка по UID/приложению,
  журнал с ограничением частоты записей. Списки домен/IP сохраняются, но без upstream-форвардинга
  не применяются — это документированное ограничение.
- **Анти-вор:** SMS-команды `#PG:PIN:COMMAND` (LOCK/WIPE/ALARM/LOCATE/PHOTO), PIN с ограничением
  попыток, детектор подмены SIM (уведомление → экран подтверждения), `StealthCameraService`.
- **Сейф:** AES-256-GCM через Android Keystore, биометрия, `FLAG_SECURE`, лимит файла 50 МБ.

## Verification
Без Android SDK доступны `./tools/check_all.sh` (проверка Gradle-скриптов, аудит, синхронизация
строк, компилятор Kotlin, aapt2, опционально tree-sitter) и
`python3 tools/kotlin_frontend_check.py --android-jar <jar>`.
`./tools/fetch_android_jar.sh` достаёт `android.jar` из GitHub-зеркала, если `dl.google.com`
недоступен. Сборка и тесты — `./gradlew assembleProductionDebug testDebugUnitTest lintDebug`
(в CI: `.github/workflows/android-ci.yml`).

## Status
- Все доступные проверки зелёные (0 ошибок).
- Прежние заметки про «сборка стабильно падает на manifest merger» были следствием локального
  окружения с не-ASCII путём; в `gradle.properties` больше нет `android.overridePathCheck`.
