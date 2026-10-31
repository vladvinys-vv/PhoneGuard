# PhoneGuard

Android-приложение для защиты устройства: проверка телефона и полное сканирование, VPN-брандмауэр без root,
анти-воровские команды по SMS, блокировка звонков и SMS, шифрованное хранилище.
Всё, что можно, работает офлайн: база, логи и диагностика остаются на устройстве.

- **Платформа:** Android 8.0+ (`minSdk 26`), `compileSdk`/`targetSdk 35`
- **Стек:** Kotlin 2.1.0, Jetpack Compose + Material 3, Hilt, Room 2.7.2 + SQLCipher for Android, WorkManager, Coroutines
- **Сборка:** Gradle 8 (Groovy DSL), AGP 8.5.2, JDK 17

## Возможности

| Раздел | Что делает |
|---|---|
| **Полное сканирование** | 16 шагов: root, целостность системы, файловая система, режим разработчика, патч-безопасности, службы доступности, overlay-атаки, разрешения, скрытые приложения, администраторы устройства, приватность, шпионское ПО, брандмауэр, подписи APK, фишинговые SMS и чек состояния телефона. Прогресс переводится (`ScanStage` с `@StringRes`), отчёт сохраняется в историю, экспортируется в PDF |
| **Чек всего телефона** | 20 проверок устройства и оценка 0..100: блокировка экрана, шифрование, SELinux, ADB, режим разработчика, администраторы, неизвестные источники, Play Protect, устаревшие и debuggable-приложения, службы доступности, разрешения, уведомления, энергосбережение, VPN, свободное место, версия ОС и модель |
| **VPN-брандмауэр** | Правила по приложениям (Wi-Fi/мобильные/полная блокировка) через `VpnService`, без root. В туннель попадают только заблокированные сейчас приложения, IPv4 и IPv6, пакеты отбрасываются; журнал блокировок с ограничением частоты |
| **Блокировка звонков** | `CallScreeningService`: белый список → чёрный список → локальная спам-база → скрытые номера → международные; нормализация в E.164 (libphonenumber) |
| **Фильтр SMS** | `SmsSpamReceiver`: чёрный/белый список, спам-база и локальная эвристика фишинга. Доставку отменяет только если PhoneGuard — приложение SMS по умолчанию |
| **Анти-вор** | SMS-команды (тревога, блокировка, вайп, координаты, фото), Device Admin, детектор подмены SIM, сигнализация с вибрацией, отправка координат доверенному номеру |
| **Сейф** | Файлы до 50 МБ, AES-256-GCM, ключи в Android Keystore, разблокировка по биометрии, `FLAG_SECURE`, предпросмотр через `FileProvider` |
| **Сканер приватности** | Анализ разрешений установленных приложений и избыточных доступов |
| **Проверка на шпионское ПО** | Скрытые приложения, службы доступности, администраторы, подозрительные подписи |
| **Защита от подглядывания** | CameraX + ML Kit face detection: предупреждение, когда в экран смотрит кто-то ещё |
| **Реагирование на вторжение** | Honeypot-события и автоматические реакции (блокировка пакета, тревога, уведомление доверенного номера) |

## Интеграции

| Компонент | Библиотека / API | Зачем | Лицензия |
|---|---|---|---|
| Номера | [libphonenumber](https://github.com/google/libphonenumber) `9.0.42` | E.164-нормализация для списков, скрининга звонков, SMS-фильтра и CSV-импорта. Офлайн, только локальная metadata | Apache-2.0 |
| Подписи APK | [apk-parser](https://github.com/hsiafan/apk-parser) `2.6.10` | Цепочка сертификатов, алгоритм подписи, срок действия — `PackageManager` этого не отдаёт | Apache-2.0 |
| Threat intelligence | [URLhaus](https://urlhaus.abuse.ch/) + [OpenPhish](https://openphish.com/) через OkHttp | Проверка доменов из подозрительных SMS. Наружу уходит **только имя хоста**; выключено по умолчанию | free for non-commercial use |
| Сеть | [OkHttp](https://square.github.io/okhttp/) `4.12.0` | HTTP-клиент для threat intel (+ `logging-interceptor` в debug) | Apache-2.0 |
| БД | Room `2.7.2` + [SQLCipher for Android](https://github.com/sqlcipher/sqlcipher-android) `4.16.0` | Локальная зашифрованная база (версия схемы 8, миграции 1→8). Native-библиотеки собраны с поддержкой 16 КБ страниц (требование Play для `targetSdk 35`) | Apache-2.0 / BSD |
| Файлы сейфа | AndroidX Security Crypto | AES-256-GCM для файлов | Apache-2.0 |
| Геолокация | Google Play Services Location | Последняя известная точка для анти-вора | proprietary SDK |
| Детектор подглядывания | ML Kit face detection | Определение второго лица в кадре | proprietary SDK |
| Камера | CameraX `1.4.1` | Фото при неудачных попытках разблокировки | Apache-2.0 |

> Аналитики, метрик и crash-репортов наружу нет: `AnalyticsHelper` считает события в памяти сессии,
> `PerformanceMonitor` пишет замеры в лог, `CrashHandler` складывает отчёты в `files/crash-reports/`.
> Firebase в проекте нет — см. [`docs/PRIVACY_POLICY.md`](docs/PRIVACY_POLICY.md).

## Архитектура

```
PhoneGuard/
├── app/
│   ├── build.gradle                 # flavors: staging / production, Room schema export
│   ├── schemas/                     # экспорт схем Room (появляется после первой сборки)
│   └── src/
│       ├── main/java/com/phoneguard/
│       │   ├── antitheft/       # SMS-команды, Device Admin, сигнализация, камера, геолокация
│       │   ├── callblocker/     # CallScreeningService, фильтр SMS, уведомления блокировщика
│       │   ├── data/            # Room (SQLCipher), DataStore, репозитории, миграции
│       │   ├── deviceaudit/     # чек всего телефона (20 проверок, оценка 0..100)
│       │   ├── di/              # Hilt-модули
│       │   ├── domain/          # use-case'ы (детектор подглядывания)
│       │   ├── firewall/        # VpnService, правила, парсеры пакетов
│       │   ├── fullscan/        # оркестратор, ScanStage, проверки в checks/ (+ анализ фишинга)
│       │   ├── intrusion/       # honeypot и автоматические реакции
│       │   ├── malware/         # анализ подписи APK (apk-parser)
│       │   ├── model/           # доменные модели и Room-сущности
│       │   ├── shouldersurfer/  # камера + ML Kit
│       │   ├── simswap/         # детектор подмены SIM
│       │   ├── spywarecheck/    # проверка шпионских приложений
│       │   ├── threatintel/     # opt-in проверка доменов (URLhaus/OpenPhish) с локальным кешем
│       │   ├── ui/              # Compose-экраны (12), тема, навигация
│       │   ├── util/            # номера, PDF/CSV, экспорт логов, аналитика, PIN
│       │   ├── vault/           # шифрованное хранилище
│       │   └── worker/          # DataCleanupWorker, ScheduledFullScanWorker
│       ├── main/res/            # strings: values / values-en / values-ru / values-de, values-night
│       ├── test/                # JVM-тесты (JUnit4, MockK, Turbine, Robolectric)
│       └── androidTest/         # инструментальные тесты (Compose UI, Room DAO, device audit)
├── docs/                        # документация (см. ниже)
├── tools/                       # проверки без Gradle
├── build.gradle                 # версии зависимостей (ext) и AGP
└── build_release.sh             # сборка подписанного APK/AAB
```

### Чек всего телефона

`deviceaudit/DeviceAuditChecker.performAudit()` выполняет 20 проверок и возвращает
`DeviceAuditReport` (оценка 0..100 + список `AuditItem`). Пункты несут `@StringRes`, поэтому экран
`ui/screens/phonecheck` рисует их на выбранном языке, а полное сканирование использует тот же аудит
16-м шагом и добавляет найденные проблемы в отчёт (`ScanIssue`). Результат сохраняется в DataStore —
дашборд показывает его без повторной проверки.

Оценка: `100 − 15 × критичных − 7 × предупреждений`, не ниже 0.

### Локализация

Интерфейс переведён на русский, английский и немецкий: `values` (по умолчанию русский),
`values-en`, `values-ru`, `values-de` — 636 ключей в каждой локали, паритет проверяется
`tools/static_audit.py`. Язык переключается в настройках и применяется через `attachBaseContext`.

Проверки полного сканирования не знают о `Context`: они возвращают `CheckDetail(textRes, args)`
(`fullscan/checks/CheckDetail.kt`), а текст собирается в UI через `resolve(context)` — поэтому
детали отчёта не «застывают» на языке, который был при сканировании.

## Сборка и запуск

Нужны JDK 17 и Android SDK (плюс `ANDROID_HOME`/`local.properties`).

```bash
# Debug-сборка
./gradlew :app:assembleProductionDebug

# Юнит-тесты (JVM, без устройства)
./gradlew :app:testProductionDebugUnitTest

# Инструментальные тесты (нужен эмулятор или устройство)
./gradlew :app:connectedProductionDebugAndroidTest

# Release: production/staging → подписанный APK и AAB
cp keystore.properties.example keystore.properties   # вписать свои ключи, не коммитить
./build_release.sh production release
# или напрямую:
./gradlew :app:assembleProductionRelease :app:bundleProductionRelease
```

## Проверки без Gradle

В песочнице без Android SDK доступны статические проверки:

```bash
./tools/check_all.sh                     # аудит + строки (+ компилятор/парсер, если установлены)

python3 tools/gradle_check.py            # обязательно: конфигурация Gradle (Groovy DSL)
python3 tools/static_audit.py            # обязательно: 0 error(s), 0 warning(s)
python3 tools/sync_strings.py            # идемпотентная синхронизация строк в 4 локалях
python3 tools/kotlin_frontend_check.py   # опционально: настоящий компилятор (с android.jar — строже)
python3 tools/aapt2_check.py             # опционально: ресурсы и манифест через aapt2 (как Gradle)
python3 tools/kotlin_analysis.py         # опционально: парсер Kotlin (нужен tree-sitter)
python3 tools/jvm_unit_test.py           # опционально: **запуск** тестов чистой логики на JVM (kotlinc + шим JUnit)
python3 tools/webhook_notify.py --dry-run # опционально: payload уведомления о сборке (docs/CI_WEBHOOKS.md)
```

`gradle_check.py` проверяет сами Gradle-скрипты (Kotlin-DSL-функции в Groovy, объявленные `ext`,
версии плагинов, `minSdk/targetSdk/compileSdk`, proguard-файлы) — ошибка там валит любую задачу
ещё до компиляции. `static_audit.py` проверяет импорты и `R.*`, Room-запросы и DAO-вызовы, Hilt-привязки, манифест и
`android:exported`, разрешения, паритет локалей, арность `getString`, `PendingIntent`-флаги,
миграции Room против `@Entity`, маршруты навигации и Gradle-обвязку. `kotlin_frontend_check.py` прогоняет все
209 файлов через настоящий компилятор Kotlin (опционально — с настоящим `android.jar`,
`tools/fetch_android_jar.sh`) и отсекает ошибки, вызванные
отсутствующими библиотеками — им найдены семь реальных ошибок сборки, в том числе в тестах.
`kotlin_analysis.py` дополнительно разбирает файлы парсером и ищет вызовы `suspend`-функций вне
suspend-контекста. Подробнее — [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md).

## CI

`.github/workflows/android-ci.yml` — единственный обязательный workflow (остальные не дублируют сборку):

| Job | Что делает |
|---|---|
| `static-checks` | `gradle_check.py` (конфигурация Gradle) + `static_audit.py` + проверка, что `strings.xml` совпадает со словарём `sync_strings.py` |
| `kotlin-frontend` | ставит Kotlin 2.1.0 + `platforms;android-35`/`build-tools`, прогоняет `kotlin_frontend_check.py` (с настоящим `android.jar`) и `aapt2_check.py` (ресурсы + манифест) |
| `lint`, `unit-test` | `lintDebug`, `testDebugUnitTest` (отчёты тестов выгружаются при падении) |
| `build-debug`, `build-release` | `assembleProductionDebug`, `assembleProductionRelease` (APK как артефакт) |
| `notify` | отправляет итог сборки на внешний webhook (`tools/webhook_notify.py`, `if: always()`) |

Codemagic (`codemagic.yaml`) делает то же самое плюс `publishing.scripts` с уведомлением о
статусе и `triggering` на push/PR/теги. Настройка обоих направлений webhook'ов —
[`docs/CI_WEBHOOKS.md`](docs/CI_WEBHOOKS.md).

## Документация

| Файл | О чём |
|---|---|
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | окружение, команды, конвенции кода и строк, инструменты |
| [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | журнал изменений и исправлений |
| [`docs/RELEASE.md`](docs/RELEASE.md) | чек-лист релиза, подпись, flavors, публикация |
| [`docs/MANUAL_TESTING.md`](docs/MANUAL_TESTING.md) | ручные сценарии проверки на устройствах |
| [`docs/PRIVACY_POLICY.md`](docs/PRIVACY_POLICY.md) | политика конфиденциальности (что и где хранится) |
| [`docs/PLAY_STORE_LISTING.md`](docs/PLAY_STORE_LISTING.md) | тексты для магазина |
| [`docs/CI_WEBHOOKS.md`](docs/CI_WEBHOOKS.md) | входящие (trigger) и исходящие (notify) webhook'и CI, подпись HMAC, локальная проверка |
| [`LICENSE`](LICENSE) | лицензия MIT (зависимости — под своими лицензиями, см. «Интеграции») |

## Модель безопасности

- **Минимум привилегий:** разрешения запрашиваются по мере необходимости, VPN и службы — только для своих функций.
- **Шифрование:** AES-256-GCM для сейфа, ключи в Android Keystore, база под SQLCipher.
- **Целостность сборки:** R8/ProGuard с keep-правилами для reflection-зависимостей (Room, SQLCipher, libphonenumber, apk-parser).
- **Приватность:** нет телеметрии и облака; сеть используется только для опционального threat intel (имя хоста).

## Лицензия

MIT — см. [LICENSE](LICENSE). Зависимости сохраняют собственные лицензии (см. таблицу
«Интеграции»): AndroidX, Room, OkHttp, libphonenumber и apk-parser — Apache-2.0, SQLCipher — BSD.
