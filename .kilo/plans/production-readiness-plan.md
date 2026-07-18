# PhoneGuard — план доведения до production 100%

## Фаза 1. Стабилизация (1-2 недели)
### 1.1. Критические баги
- [ ] Убрать `GlobalScope` и `runBlocking` из всех Compose-экранов и `MainActivity`
- [ ] Исправить VaultViewModel: `every` → `coEvery` в тестах, убедиться что компилируется
- [ ] Добавить проверку `CAMERA` permission перед запуском `ShoulderSurferService`
- [ ] Исправить `LogExporter`: передавать реальные данные из ViewModel вместо `emptyList()`
- [ ] Добавить обработку `SecurityException` в `ShoulderSurferService` при недоступности камеры
- [ ] Исправить `LanguageSelector`: сохранять выбор в `PreferencesManager`, не только в локальный state

### 1.2. Безопасность
- [ ] Добавить SSL pinning для всех сетевых вызовов (если будут внешние API)
- [ ] Зашифровать `BlockedLog` и `FirewallLog` в Room (sqlcipher или AES)
- [ ] Добавить `android:exported="false"` для всех `BroadcastReceiver`, где не требуется экспорт
- [ ] Верифицировать, что `ProGuard` правила покрывают все новые классы
- [ ] Добавить `android:networkSecurityConfig` для production

## Фаза 2. Архитектура (1-2 недели)
### 2.1. DI и ответственность
- [ ] Вынести `LogExporter` из static util в `@Singleton` репозиторий
- [ ] Создать `SettingsViewModel` и убрать `DashboardViewModel` из `SettingsScreen`
- [ ] Добавить `ShoulderSurferUseCase` / `ShoulderSurferRepository` для инкапсуляции логики
- [ ] Перевести `PhoneGuardVpnService` на DI (сейчас `@AndroidEntryPoint`, но лучше вынести логику)
- [ ] Добавить `CoroutineScope` с `SupervisorJob()` в `CallScreeningServiceImpl` и отменять при `onDestroy`

### 2.2. State Management
- [ ] Заменить `GlobalScope` в `SettingsScreen` на `viewModelScope` / `LaunchedEffect`
- [ ] Добавить `Event` wrapper для one-off событий (snackbar, navigation) в всех ViewModel
- [ ] Унифицировать обработку loading/error/success состояний в UI

## Фаза 3. Полноценный функционал (2-3 недели)
### 3.1. CallBlocker
- [ ] Добавить SMS-блокировку через `SmsRetriever` / `CarrierMessagingService` (или документацию о limitation)
- [ ] Реализовать импорт CSV в черный/белый список
- [ ] Добавить базу спама (локальный JSON asset + обновления)
- [ ] Добавить правила по шаблонам номеров (regex/wildcard)
- [ ] Добавить уведомление при блокировке звонка/SMS

### 3.2. Firewall
- [ ] Реализовать реальный forwarding трафика через Tun2Socket/PacketForwarder (или купить либу)
- [ ] Добавить UI для управления доменами/IP в правилах
- [ ] Добавить Whitelist/Blacklist приложений с возможностью точечной блокировки
- [ ] Добавить детализацию логов (app name, traffic direction)
- [ ] Добавить статистику: сколько блокировок за день/неделю

### 3.3. FullScan
- [ ] Добавить детальный экран отчёта (tap на историю → полный report)
- [ ] Добавить экспорт отчёта в PDF
- [ ] Добавить планировщик сканирований (weekly/monthly) через WorkManager
- [ ] Добавить сравнение результатов с предыдущим сканом

### 3.4. Vault
- [ ] Добавить поддержку видео/документов (не только фото)
- [ ] Добавить превью файлов перед импортом
- [ ] Добавить ограничение размера файла (например, 50MB)
- [ ] Добавить защиту от скриншотов (`FLAG_SECURE`)

### 3.5. AntiTheft
- [ ] Добавитьremote wipe через Firebase Cloud Messaging (FCM)
- [ ] Добавить remote lock через Device Admin API
- [ ] Добавить siren/alarm с настраиваемой мелодией
- [ ] Добавить фото при неудачных попытках с фронтальной камеры

## Фаза 4. UI/UX полировка (1 неделя)
### 4.1. Диалоги и состояния
- [ ] Добавить `Snackbar` для всех ошибок и успешных операций
- [ ] Добавить `ProgressIndicator` для всех async операций
- [ ] Добавить empty states с иконками для всех списков
- [ ] Добавить pull-to-refresh для списков (CallBlocker, Firewall, Vault, History)
- [ ] Добавить confirm-диалоги для destructive actions (delete, wipe)

### 4.2. Навигация
- [ ] Добавить deep linking для экранов
- [ ] Добавить навигацию из уведомлений (Shoulder Surfer, Full Scan)
- [ ] Добавить bottom navigation вместо drawer (опционально, обсуждать с дизайнером)

### 4.3. Доступность
- [ ] Добавить contentDescription для всех иконок
- [ ] Добавить TalkBack поддержку
- [ ] Проверить контраст цветов для accessibility

## Фаза 5. Тестирование (1 неделя)
### 5.1. Unit-тесты
- [ ] Добавить тесты для всех ViewModel (остались: AntiTheft, Firewall, FullScan)
- [ ] Добавить тесты для UseCases/Repositories
- [ ] Добавить тесты для утилит (LogExporter, SecurityUtils edge cases)
- [ ] Цель: покрытие 80%+ business logic

### 5.2. Instrumented тесты
- [ ] DAO тесты для всех сущностей (in-memory Room)
- [ ] UI тесты через ComposeTestRule для ключевых сценариев
- [ ] Тесты навигации

### 5.3. Мануальное тестирование
- [ ] Тестирование на Android 8, 10, 12, 13, 14, 15
- [ ] Тестирование на разных производителях (Samsung, Xiaomi, Huawei, Pixel)
- [ ] Тестирование батареи (battery historian)
- [ ] Тестирование памяти (LeakCanary)

## Фаза 6. Производительность (3-5 дней)
### 6.1. Оптимизации
- [ ] Добавить pagination для больших списков (CallBlocker history, Firewall logs)
- [ ] Оптимизировать `getInstalledApps()` — кэшировать результат на 24 часа
- [ ] Добавить Room индексы для часто queried полей
- [ ] Оптимизировать `ShoulderSurferService`: уменьшить resolution камеры для анализа
- [ ] Добавить `StrictMode` в debug-сборке для детекта медленных операций на главном потоке

### 6.2. Батарея
- [ ] Ограничить частоту сканирований: не чаще 1 раза в 6 часов
- [ ] Добавить `WorkManager` с `Constraints` (только при charging + wifi для больших задач)
- [ ] Остановить `ShoulderSurferService` при низком батарее < 15% (auto-pause)
- [ ] Добавить battery optimization prompt для foreground service

## Фаза 7. Release-подготовка (3-5 дней)
### 7.1. Конфигурация
- [ ] Создать `google-services.json` для Firebase
- [ ] Настроить signing config через `local.properties` / env variables
- [ ] Создать separate `app-{flavor}` если нужны staging/production окружения
- [ ] Настроить `gradle.properties` для production: `org.gradle.jvmargs=-Xmx4g`

### 7.2. Сторинг
- [ ] Сделать скриншоты для Play Store (phone, tablet, foldable)
- [ ] Написать description, keywords, changelog
- [ ] Подготовить privacy policy URL
- [ ] Подготовить support email

### 7.3. Аналитика
- [ ] Добавить Firebase Analytics events для всех экранов
- [ ] Добавить Firebase Crashlytics (уже подключен, нужно добавить `setCrashlyticsCollectionEnabled`)
- [ ] Добавить Performance Monitoring
- [ ] Добавить в on-boarding consent для analytics

## Фаза 8. Compliance и юридическое (2-3 дня)
### 8.1. Privacy
- [ ] Добавить consent screen для camera, SMS, phone permissions с объяснением
- [ ] Добавить возможность удалить все данные (GDPR/CCPA)
- [ ] Добавить data retention policy (автоочистка логов старше 90 дней)
- [ ] Подготовить Privacy Policy PDF

### 8.2. Permissions
- [ ] Добавить `android:usesPermissionFlags` для foreground service
- [ ] Добавить `android:permissionGroup` в манифест
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
- [ ] Все P0 баги из Фазы 1 исправлены
- [ ] Покрытие тестами 60%+ бизнес-логики
- [ ] Настроен CI/CD
- [ ] Firebase Crashlytics подключен и работает
- [ ] Signing config готов
- [ ] Privacy Policy и Terms готовы
- [ ] Протестировано на Android 8-15

### Should Have (P1)
- [ ] VPN имеет реальное ограничение или четко помечен как MVP
- [ ] Все основные сценарии покрыты UI-тестами
- [ ] Производительность оптимизирована
- [ ] Батарея: фоновые задачи не сажат заряд

### Nice to Have (P2)
- [ ] Темная тема
- [ ] Онбординг
- [ ] Export в PDF
- [ ] Сравнение сканов
