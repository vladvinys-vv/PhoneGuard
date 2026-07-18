# PhoneGuard — план доработок

## Обнаруженные проблемы

### 1. Критические баги (наиболее приоритетные)

| # | Проблема | Файл | Риск |
|---|----------|------|------|
| 1.1 | **Nested `collect` в `DashboardViewModel.calculateSecurityScore()`** — внутренний `collect` никогда не завершается, внешний получает только первое значение. Балл считает один раз и больше не обновляется. | `DashboardViewModel.kt:42-61` | Неверный UX |
| 1.2 | **`runBlocking` в `CallScreeningServiceImpl.onScreenCall()`** — блокирует поток Telecom, риск ANR и сброса вызова. | `CallScreeningServiceImpl.kt:32` | ANR / сбой вызовов |
| 1.3 | **Обход биометрии в `VaultViewModel.authenticateUser()`** — при `canAuthenticate != BIOMETRIC_SUCCESS` vault разблокируется без аутентификации. | `VaultViewModel.kt:56-58` | Утечка данных |
| 1.4 | **Несанкционированные имена файлов в `EncryptionManager.getFileName()`** — имя из URI не sanitize, возможен path traversal. | `EncryptionManager.kt:96-105` | Безопасность |
| 1.5 | **`SecurityUtils.hashPin()` — unsalted SHA-256** — уязвим к rainbow-table атакам. | `SecurityUtils.kt:7-15` | Безопасность |

### 2. Безопасность

| # | Проблема | Файл | Риск |
|---|----------|------|------|
| 2.1 | Временные файлы в `decryptFileFromVault()` не очищаются. | `EncryptionManager.kt:70-81` | Утечка данных |
| 2.2 | VPN-сервис имеет hardcoded DNS и не форвардит трафик — фаерволл не работает как задумано. | `PhoneGuardVpnService.kt:166-173` | Нефункциональность |
| 2.3 | `SimSwapDetector.sendBackupSms()` молча возвращает `true` при ошибке. | `SimSwapDetector.kt:102-118` | Неверное состояние |
| 2.4 | `SpywareCheckRepository.getSideloadedApps()` считает sideloaded всё, что не из `com.android.vending` (пропускает Galaxy Store и др.). | `SpywareCheckRepository.kt:115-124` | Ложноположительные |

### 3. Архитектура и качество кода

| # | Проблема | Файл | Риск |
|---|----------|------|------|
| 3.1 | Дублирование `@Provides AppDatabase` в `AppModule` и `DatabaseModule`. | `AppModule.kt`, `DatabaseModule.kt` | Дублирование |
| 3.2 | `FullScanOrchestrator` — `@Singleton` с `Flow`, при повторном сборе запускает новый scan. | `FullScanOrchestrator.kt` | Многократные сканирования |
| 3.3 | `SimSwapDetector` содержит extension-функцию `FusedLocationProviderClient.await()` внизу файла. | `SimSwapDetector.kt:165-169` | Организация кода |
| 3.4 | `PrivacyScannerRepository.getInstalledApps()` сначала emits пустой список, потом данные — лишний recomposition. | `PrivacyScannerRepository.kt:45-76` | Производительность |
| 3.5 | `VaultRepository` — неконсистентные return types: `decryptFileFromVault` бросает исключение, `decryptItem` возвращает nullable. | `VaultRepository.kt:36-42` | API inconsistency |

### 4. Android / Manifest

| # | Проблема | Файл | Риск |
|---|----------|------|------|
| 4.1 | `requestLegacyExternalStorage="true"` не работает на Android 13+. | `AndroidManifest.xml:86` | Сбой на новых OS |
| 4.2 | Дублирование `SIM_STATE_CHANGED` у `SimChangeReceiver` и `SimSwapReceiver`. | `AndroidManifest.xml:114-194` | Дублирование событий |
| 4.3 | `SmsCommandReceiver` использует устаревший `SMS_RECEIVED` — работает только как default SMS app на Android 8+. | `AndroidManifest.xml:125-132` | Не работает на новых OS |
| 4.4 | `CallScreeningService` имеет `foregroundServiceType="shortService"` — не требуется. | `AndroidManifest.xml:135-143` | Лишнее |

### 5. База данных

| # | Проблема | Файл | Риск |
|---|----------|------|------|
| 5.1 | `fallbackToDestructiveMigration()` — при изменении версии все данные удаляются. | `AppDatabase.kt:49` | Потеря данных |
| 5.2 | `exportSchema = false` — нет истории миграций. | `AppDatabase.kt:29` | Сложность поддержки |

### 6. Тесты

| # | Проблема | Риск |
|---|----------|------|
| 6.1 | **Нет unit-тестов и instrumented-тестов вообще.** | Невозможность регрессионного контроля |

### 7. UI / UX недоделки

| # | Проблема | Файл | Риск |
|---|----------|------|------|
| 7.1 | 7 из 10 экранов — заглушки "Coming Soon!". | `PlaceholderScreens.kt` | Незавершённый продукт |
| 7.2 | Жёстко зашитые баллы на Dashboard (CallBlocker=75, Privacy=60, Spyware=50). | `DashboardScreen.kt:220-237` | Неверный UX |
| 7.3 | `SimSwapStatusCard` имеет пустой `onClick`. | `DashboardScreen.kt:69` | Нефункциональность |
| 7.4 | `FullScanScreen` ожидает `List<ScanHistory>` в `scanHistory`, но ViewModel отдаёт StateFlow. | `FullScanScreen.kt:55` | Несоответствие типов |

## План работ

### Этап 1: Исправить критические баги и уязвимости
1.1. Переписать `DashboardViewModel.calculateSecurityScore()` — заменить nested `collect` на `combine` или `flatMapLatest`.
1.2. Заменить `runBlocking` в `CallScreeningServiceImpl.onScreenCall()` на корутину с `suspendCancellableCoroutine` или делегировать в repository.
1.3. Исправить `VaultViewModel.authenticateUser()` — убрать автопроброс, вернуть ошибку при невозможности аутентификации.
1.4. Добавить sanitization имён файлов в `EncryptionManager.getFileName()` (убрать path separators).
1.5. Перевести `SecurityUtils.hashPin()` на salted hash (PBKDF2 или BCrypt) или добавить соль.

### Этап 2: Безопасность
2.1. Добавить cleanup temp-файлов в `EncryptionManager.decryptFileFromVault()` (deleteOnExit или ручное удаление).
2.2. Для VPN: либо реализовать реальный forwarding через Tun2Socket/PacketForwarder, либо переключиться на `NetworkCapabilities`-блокировку (Android 10+), либо пометить как MVP с оговорёнными ограничениями.
2.3. `SimSwapDetector.sendBackupSms()` — возвращать реальный результат, логировать ошибку.
2.4. Уточнить список sideloaded installer-ов (добавить Samsung, Huawei, Amazon и др.).

### Этап 3: Архитектура и DI
3.1. Убрать дублирование `AppDatabase` — оставить один `@Provides` в `DatabaseModule`, удалить из `AppModule`.
3.2. Сделать `FullScanOrchestrator` не-синглтоном или добавить флаг `isScanning` для предотвращения параллельных запусков.
3.3. Вынести `FusedLocationProviderClient.await()` в `util`-пакет.
3.4. Убрать первичный `emit(emptyList())` из `PrivacyScannerRepository.getInstalledApps()`.
3.5. Унифицировать API `VaultRepository.decrypt*` методы.

### Этап 4: Android / Manifest
4.1. Удалить `requestLegacyExternalStorage="true"`, добавить `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO` для Android 13+.
4.2. Удалить дублирующий `SimChangeReceiver`, оставить только `SimSwapReceiver`.
4.3. Для SMS: либо реализовать полноценный `CarrierMessagingService` / `SmsRetriever`, либо пометить как требующий default-SMS-app.
4.4. Убрать `foregroundServiceType="shortService"` у `CallScreeningService`.

### Этап 5: База данных
5.1. Добавить явные миграции Room (Migration1→2→3) и убрать `fallbackToDestructiveMigration()`.
5.2. Включить `exportSchema = true`, вынести schema в `assets/schemas/`.

### Этап 6: Тесты
6.1. Добавить unit-тесты для `SecurityUtils`, `PermissionAbuseCheck`, `RootDetectionCheck`, `PhishingUrlChecker`.
6.2. Добавить тесты для ViewModel (`DashboardViewModel`, `FullScanViewModel`, `SimSwapViewModel`) с `MainDispatcherRule`.
6.3. Добавить instrumented тесты для DAO (Room in-memory).

### Этап 7: UI / UX
7.1. Реализовать заглушки: AntiTheft (✅), CallBlocker (✅ существующая реализация), PrivacyScanner (✅), SpywareCheck (✅), Settings (✅), Firewall (✅ существующая реализация), Vault (✅ существующая реализация), FullScan (✅).
7.2. Сделать баллы Dashboard динамическими (подсчитывать из реальных настроек и данных).
7.3. Добавить навигацию по `SimSwapStatusCard` на экран истории.
7.4. Исправить `FullScanScreen` — отображать список истории, а не timestamp объекта.

### Этап 8: Защита от подглядывания (Shoulder Surfer)
8.1. Добавить зависимость ML Kit Face Detection в build.gradle.
8.2. Создать `ShoulderSurferService` с CameraX ImageAnalysis + ML Kit Face Detection.
8.3. Добавить префикс `KEY_SHOULDER_SURFER_ENABLED` в `PreferencesManager`.
8.4. Добавить переключатель в `AntiTheftScreen` с запуском/остановкой сервиса.
8.5. Обновить `AndroidManifest`: permission `FOREGROUND_SERVICE_CAMERA`, сервис с `foregroundServiceType="camera"`.
8.6. Обновить ProGuard для ML Kit и нового сервиса.
8.7. Добавить уведомление с вибрацией при обнаружении подглядывающего.

### Этап 9: Завершение
9.1. Заменить SettingsScreen на реальный UI с языком, политикой конфиденциальности, условиями использования, о приложении.
9.2. Добавить уведомление MVP-ограничений в FirewallScreen.
9.3. Добавить unit-тесты для DashboardViewModel и SimSwapViewModel.
9.4. Настроить exportSchema для Room в `app/schemas/`.
9.5. Очистить неиспользуемые импорты и placeholder-заглушки.
