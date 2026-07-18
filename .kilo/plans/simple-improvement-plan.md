# PhoneGuard — простой план улучшения

## 1. Стабильность и безопасность
- [x] Исправить критические баги (nested collect, runBlocking, обход биометрии, unsafe имена файлов)
- [x] Заменить unsalted SHA-256 на PBKDF2 с солью
- [x] Добавить очистку временных файлов vault
- [x] Убрать destructive migration, добавить явные миграции Room
- [x] Удалить устаревшие/дублирующие ресиверы и权限 из манифеста
- [ ] Добавить graceful fallback для VPN на Android 10+ через NetworkCapabilities
- [ ] Реализовать полноценный forwarding трафика (Tun2Socket / PacketForwarder) или официально отметить как MVP

## 2. Архитектура и код
- [x] Убрать дублирование DI (AppDatabase)
- [x] Вынести extension-функции в util-пакет
- [x] Унифицировать API VaultRepository
- [ ] Добавить флаг isScanning в FullScanOrchestrator / UI-защиту от параллельных запусков
- [ ] Добавить покрытие тестами: SecurityUtils, PermissionAbuseCheck, RootDetectionCheck, PhishingUrlChecker, DAO

## 3. Функционал
- [x] Заглушки → реальные UI: AntiTheft, CallBlocker, PrivacyScanner, SpywareCheck, FullScan, Vault, Settings, Firewall
- [x] Shoulder Surfer Detection (камера + ML Kit) с уведомлением
- [x] Динамический балл безопасности на Dashboard
- [x] Навигация SimSwapStatusCard → история
- [ ] Завершить CallBlockerScreen: бэкенд-интеграция с CallScreeningService, поддержка SMS-блокировки
- [ ] Завершить Firewall: правила на основе реальных доменов/IP, Whitelist/Blacklist приложений

## 4. UI/UX
- [x] Убрать все "Coming Soon"
- [x] Добавить MVP-уведомление в Firewall
- [x] Добавить выбор языка в Settings
- [ ] Добавить темную тему / настройку темы
- [ ] Добавить экспорт логов / reports в PDF или JSON
- [ ] Онбординг при первом запуске

## 5. Тесты и CI
- [x] Unit-тесты: SecurityUtils, PermissionHeuristics, FullScanViewModel, DashboardViewModel, SimSwapViewModel
- [ ] Unit-тесты: CallBlockerViewModel, VaultViewModel, PrivacyScannerViewModel, SpywareCheckViewModel
- [ ] Instrumented тесты: все DAO, основные сценарии UI
- [ ] Настроить GitHub Actions / CI: lint, test, build

## 6. Прочее
- [x] Обновить ProGuard для новых модулей
- [ ] Подготовить release-версию: signing config, app bundle, Play Store листинг
- [ ] Добавить аналитика и краш-репортинг (Firebase Crashlytics / Sentry)
- [ ] Оптимизировать батарею: ограничить частоту сканирований, адаптивный интервал для Shoulder Surfer
