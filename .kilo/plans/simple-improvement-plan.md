# PhoneGuard — простой план улучшения

## 1. Стабильность и безопасность
- [x] Исправить критические баги (nested collect, runBlocking, обход биометрии, unsafe имена файлов)
- [x] Заменить unsalted SHA-256 на PBKDF2 с солью
- [x] Добавить очистку временных файлов vault
- [x] Убрать destructive migration, добавить явные миграции Room
- [x] Удалить устаревшие/дублирующие ресиверы и权限 из манифеста
- [x] Добавить graceful fallback для VPN на Android 10+ через NetworkCapabilities
- [x] VPN оставлен в MVP-режиме с fallback (полноценный forwarding требует root/Tun2Socket)

## 2. Архитектура и код
- [x] Убрать дублирование DI (AppDatabase)
- [x] Вынести extension-функции в util-пакет
- [x] Унифицировать API VaultRepository
- [x] Добавить флаг isScanning в FullScanOrchestrator / UI-защиту от параллельных запусков
- [x] Добавить покрытие тестами: SecurityUtils, PermissionAbuseCheck, RootDetectionCheck, PhishingUrlChecker, DAO

## 3. Функционал
- [x] Заглушки → реальные UI: AntiTheft, CallBlocker, PrivacyScanner, SpywareCheck, FullScan, Vault, Settings, Firewall
- [x] Shoulder Surfer Detection (камера + ML Kit) с уведомлением
- [x] Динамический балл безопасности на Dashboard
- [x] Навигация SimSwapStatusCard → история
- [x] CallBlockerScreen: UI + интеграция с CallScreeningService (черный/белый список, правила, лог)
- [x] FirewallScreen: правила приложений + VPN toggle + MVP-уведомление
- [x] LogExporter: экспорт логов в JSON

## 4. UI/UX
- [x] Убрать все "Coming Soon"
- [x] Добавить MVP-уведомление в Firewall
- [x] Добавить выбор языка в Settings
- [x] Добавить темную тему / настройку темы
- [x] Добавить экспорт логов / reports в JSON
- [x] Онбординг при первом запуске

## 5. Тесты и CI
- [x] Unit-тесты: SecurityUtils, PermissionHeuristics, FullScanViewModel, DashboardViewModel, SimSwapViewModel
- [x] Unit-тесты: CallBlockerViewModel, PermissionAbuseCheck, RootDetectionCheck, PhishingUrlChecker
- [x] Unit-тесты: PrivacyScannerViewModel, SpywareCheckViewModel, VaultViewModel, AntiTheftViewModel, FirewallViewModel, SettingsViewModel, OnboardingViewModel
- [x] Instrumented тесты: BlockedNumberDao, BlockedLogDao, FirewallDao, ScanHistoryDao, VaultDao, SimSwapEventDao
- [x] UI тесты: SettingsScreenTest, CallBlockerScreenTest, FirewallScreenTest
- [x] Настроить GitHub Actions / CI: lint, test, build

## 6. Прочее
- [x] Обновить ProGuard для новых модулей
- [x] Подготовить release-версию: signing config, app bundle, Play Store листинг
- [x] Добавить аналитику и краш-репортинг (Firebase Crashlytics)
- [x] Оптимизировать батарею: ограничить частоту сканирований, адаптивный интервал для Shoulder Surfer
- [x] Добавить StrictMode в debug сборке
- [x] Добавить pagination для больших списков
- [x] Кэшировать getInstalledApps() на 24 часа
- [x] Добавить deep linking для основных экранов
- [x] Добавить consent screen для permissions
- [x] Добавить GDPR удаление всех данных
- [x] Добавить WorkManager для scheduled scans и data cleanup
- [x] Добавить battery optimization prompt
- [x] Добавить usesPermissionFlags и permissionGroup в манифест
- [x] Добавить bottom navigation
- [x] Интегрировать Performance Monitoring
- [x] Оптимизировать ShoulderSurferService camera resolution (320x240)
- [x] Добавить navigation tests
- [x] Добавить UseCase/Repository tests
- [x] Документировать SMS blocker limitation
- [x] Добавить scan comparison
- [x] Добавить firewall traffic statistics
- [x] Добавить CSV импорт для CallBlocker
- [x] Добавить уведомления о блокировке звонков
- [x] Добавить LeakCanary для детекта утечек памяти
- [x] Добавить gradle.properties для production сборки

