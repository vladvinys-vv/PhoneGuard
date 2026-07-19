# PhoneGuard — план доведения до production 100%

## Фаза 1. Стабилизация (1-2 недели)
### 1.1. Критические баги
- [x] Убрать `GlobalScope` и `runBlocking` из всех Compose-экранов и `MainActivity`
- [x] Исправить VaultViewModel: `every` → `coEvery` в тестах, убедиться что компилируется
- [x] Добавить проверку `CAMERA` permission перед запуском `ShoulderSurferService`
- [x] Исправить `LogExporter`: передавать реальные данные из ViewModel вместо `emptyList()`
- [x] Добавить обработку `SecurityException` в `ShoulderSurferService` при недоступности камеры
- [x] Исправить `LanguageSelector`: сохранять выбор в `PreferencesManager`, не только в локальный state
- [x] Добавить `CoroutineScope` с `SupervisorJob()` в `CallScreeningServiceImpl` и отменять при `onDestroy`
- [x] Убрать `runBlocking` из `PhoneGuardVpnService.startVpn()`

### 1.2. Безопасность
- [x] Добавить SSL pinning структуру (`SslPinningUtil`) для будущих внешних API
- [x] Зашифровать Room базу через SQLCipher + Android Keystore (`DatabaseKeyManager`)
- [x] Добавить `android:exported="false"` для `SimSwapReceiver` и `PackageAddedReceiver`
- [x] Верифицировать, что `ProGuard` правила покрывают все новые классы
- [x] Добавить `android:networkSecurityConfig` для production
- [x] Добавить confirm-диалоги для destructive actions (delete, wipe)
- [x] Добавить `FLAG_SECURE` для Vault экрана
- [ ] Добавить `android:exported="false"` для остальных BroadcastReceiver (DeviceAdminReceiver требует exported=true)
- [ ] Заменить placeholder passphrase на user-derived ключ (сейчас placeholder + Keystore)

## Фаза 2. Архитектура (1-2 недели)
### 2.1. DI и ответственность
- [x] Создать `SettingsViewModel` и убрать `DashboardViewModel` из `SettingsScreen`
- [x] Добавить `ShoulderSurferUseCase` / `ShoulderSurferRepository` для инкапсуляции логики
- [x] Перевести `PhoneGuardVpnService` на DI (сейчас `@AndroidEntryPoint`)
- [x] Добавить `CoroutineScope` с `SupervisorJob()` в `CallScreeningServiceImpl` и отменять при `onDestroy`
- [x] Создать `DatabaseKeyManager` для управления ключами SQLCipher
- [x] Создать `DataRetentionManager` + `DataCleanupWorker` для автоочистки логов

### 2.2. State Management
- [x] Заменить `GlobalScope` в `SettingsScreen` на `viewModelScope` / `LaunchedEffect`
- [x] Добавить `Event` wrapper для one-off событий (snackbar, navigation) в всех ViewModel
- [x] Унифицировать обработку loading/error/success состояний в UI
- [x] Добавить `_exportResult` StateFlow в `SettingsViewModel` для результата экспорта

## Фаза 3. Полноценный функционал (2-3 недели)
### 3.1. CallBlocker
- [x] CallScreeningService интеграция (черный/белый список, правила, лог)
- [ ] Добавить SMS-блокировку через `SmsRetriever` / `CarrierMessagingService` (или документацию о limitation)
- [ ] Реализовать импорт CSV в черный/белый список
- [ ] Добавить базу спама (локальный JSON asset + обновления)
- [ ] Добавить правила по шаблонам номеров (regex/wildcard)
- [ ] Добавить уведомление при блокировке звонка/SMS

### 3.2. Firewall
- [x] FirewallScreen: правила приложений + VPN toggle + MVP-уведомление
- [x] PhoneGuardVpnService с rules cache и rate-limited логированием
- [ ] Реализовать реальный forwarding трафика через Tun2Socket/PacketForwarder (или купить либу)
- [ ] Добавить UI для управления доменами/IP в правилах
- [ ] Добавить Whitelist/Blacklist приложений с возможностью точечной блокировки
- [ ] Добавить детализацию логов (app name, traffic direction)
- [ ] Добавить статистику: сколько блокировок за день/неделю

### 3.3. FullScan
- [x] FullScanOrchestrator с 13 проверками и Room-историей
- [x] FullScanScreen с прогрессом, отчётом и историей
- [ ] Добавить детальный экран отчёта (tap на историю → полный report)
- [ ] Добавить экспорт отчёта в PDF
- [ ] Добавить планировщик сканирований (weekly/monthly) через WorkManager
- [ ] Добавить сравнение результатов с предыдущим сканом

### 3.4. Vault
- [x] VaultScreen с биометрией, импортом, списком, удалением
- [ ] Добавить поддержку видео/документов (не только фото)
- [ ] Добавить превью файлов перед импортом
- [ ] Добавить ограничение размера файла (например, 50MB)
- [x] Добавить защиту от скриншотов (`FLAG_SECURE`)

### 3.5. AntiTheft
- [x] AntiTheftScreen: PIN, backup number, SIM lock, фото, remote alarm, shoulder surfer
- [x] Device Admin интеграция
- [ ] Добавить remote wipe через Firebase Cloud Messaging (FCM)
- [ ] Добавить remote lock через Device Admin API
- [ ] Добавить siren/alarm с настраиваемой мелодией
- [ ] Добавить фото при неудачных попытках с фронтальной камеры

## Фаза 4. UI/UX полировка (1 неделя)
### 4.1. Диалоги и состояния
- [x] Добавить `Snackbar` через `EventViewModel` для всех ошибок и успешных операций
- [x] Добавить `ProgressIndicator` для async операций (Firewall apps loading, scan progress)
- [x] Добавить empty states с иконками для всех списков
- [x] Добавить confirm-диалоги для destructive actions (delete, wipe)
- [x] Добавить refresh actions для списков (CallBlocker, Firewall, Vault, PrivacyScanner)

### 4.2. Навигация
- [x] Добавить deep linking для основных экранов (`phoneguard://antitheft`, `phoneguard://callblocker` и т.д.)
- [x] Добавить навигацию из уведомлений (Shoulder Surfer alert, foreground service)
- [ ] Добавить bottom navigation вместо drawer (опционально, обсуждать с дизайнером)

### 4.3. Доступность
- [x] Добавить contentDescription для основных иконок (FeatureCard, Settings, AntiTheft)
- [x] Добавить TalkBack поддержку (contentDescription для всех интерактивных элементов)
- [ ] Проверить контраст цветов для accessibility

## Фаза 5. Тестирование (1 неделя)
### 5.1. Unit-тесты
- [x] Добавить тесты для ViewModel: Dashboard, CallBlocker, SimSwap, FullScan, Vault, PrivacyScanner, SpywareCheck, Settings, AntiTheft, Firewall, Onboarding
- [x] Добавить тесты для утилит (SecurityUtils, LogExporter, AnalyticsHelper)
- [ ] Добавить тесты для UseCases/Repositories
- [ ] Цель: покрытие 80%+ business logic

### 5.2. Instrumented тесты
- [x] DAO тесты: BlockedNumberDao, BlockedLogDao, FirewallDao, ScanHistoryDao, VaultDao, SimSwapEventDao
- [x] UI тесты через ComposeTestRule для ключевых сценариев (SettingsScreen, CallBlockerScreen, FirewallScreen)
- [ ] Тесты навигации

### 5.3. Мануальное тестирование
- [ ] Тестирование на Android 8, 10, 12, 13, 14, 15
- [ ] Тестирование на разных производителях (Samsung, Xiaomi, Huawei, Pixel)
- [ ] Тестирование батареи (battery historian)
- [ ] Тестирование памяти (LeakCanary)

## Фаза 6. Производительность (3-5 дней)
### 6.1. Оптимизации
- [x] Добавить pagination для больших списков (CallBlocker, Firewall, ScanHistory DAO paged queries)
- [x] Оптимизировать `getInstalledApps()` — кэшировать результат на 24 часа (`InstalledAppsCache`)
- [x] Добавить Room индексы для часто queried полей
- [ ] Оптимизировать `ShoulderSurferService`: уменьшить resolution камеры для анализа
- [x] Добавить `StrictMode` в debug-сборке для детекта медленных операций на главном потоке

### 6.2. Батарея
- [x] Ограничить частоту сканирований: не чаще 1 раза в 6 часов (`FullScanViewModel` throttle)
- [x] Добавить `WorkManager` с `Constraints` для фоновых задач (DataCleanupWorker, ScheduledFullScanWorker)
- [x] Остановить `ShoulderSurferService` при низком батарее < 15% (auto-pause)
- [x] Добавить battery optimization prompt для foreground service

## Фаза 7. Release-подготовка (3-5 дней)
### 7.1. Конфигурация
- [ ] Создать `google-services.json` для Firebase
- [x] Настроить signing config через `local.properties` / env variables / keystore.properties
- [ ] Создать separate `app-{flavor}` если нужны staging/production окружения
- [ ] Настроить `gradle.properties` для production: `org.gradle.jvmargs=-Xmx4g`

### 7.2. Сторинг
- [ ] Сделать скриншоты для Play Store (phone, tablet, foldable)
- [ ] Написать description, keywords, changelog
- [ ] Подготовить privacy policy URL
- [ ] Подготовить support email

### 7.3. Аналитика
- [x] Добавить Firebase Analytics events для основных экранов и действий
- [x] Firebase Crashlytics подключен
- [ ] Добавить Performance Monitoring
- [x] Добавить consent для analytics в onboarding

## Фаза 8. Compliance и юридическое (2-3 дня)
### 8.1. Privacy
- [x] Добавить consent screen для camera, SMS, phone permissions с объяснением
- [x] Добавить возможность удалить все данные (GDPR/CCPA)
- [x] Добавить data retention policy (автоочистка логов старше 90 дней через DataCleanupWorker)
- [ ] Подготовить Privacy Policy PDF

### 8.2. Permissions
- [x] Добавить `android:usesPermissionFlags` для foreground service
- [x] Добавить `android:permissionGroup` в манифест для CALL_SCREENING
- [ ] Протестировать permission flow на Android 6-15

## Оценка сроков и ресурсов

| Фаза | Длительность | Приоритет |
|------|-------------|-----------|
| Фаза 1. Стабилизация | 1-2 недели | P0 — блокер релиза |
| Фаза 2. Архитектура | 1-2 недели | P1 — важно для поддержки |
| Фаза 3. Функционал | 2-3 недели | P1 — differentiation |
| Фаза 4. UI/UX | 1 неделя | P2 — polish |
| Фаза 5. Тестирование | 1 неделя | P0 — блокер релиза |
| Фаза 6. Производительность | 3-5 дней | P1 — важно для retention |
| Фаза 7. Release | 3-5 дней | P0 — блокер релиза |
| Фаза 8. Compliance | 2-3 дня | P0 — блокер релиза |

**Итого:** 6-10 недель до production-ready релиза.

## Критерии готовности к релизу

### Must Have (P0)
- [x] Все P0 баги из Фазы 1 исправлены
- [x] Покрытие тестами 60%+ бизнес-логики (ViewModels + утилиты)
- [x] Настроен CI/CD (GitHub Actions: lint, test, build)
- [x] Firebase Crashlytics подключен
- [x] Signing config готов
- [ ] Privacy Policy и Terms готовы
- [ ] Протестировано на Android 8-15

### Should Have (P1)
- [x] VPN имеет четкую MVP-маркировку и fallback
- [ ] Все основные сценарии покрыты UI-тестами
- [x] Производительность оптимизирована (индексы, cleanup worker)
- [ ] Батарея: фоновые задачи не сажат заряд

### Nice to Have (P2)
- [x] Темная тема
- [x] Онбординг
- [ ] Export в PDF
- [ ] Сравнение сканов
