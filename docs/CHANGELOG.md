# Changelog

## [Unreleased] — интеграции и исправления

### Added
- **`tools/test_coverage_map.py` — карта тестового покрытия без Gradle.** Показывает, какие
  классы вообще упомянуты в тестах (сейчас 101 из 166), и отдельно — «логика без тестов» против
  «UI/Hilt/сервисы, которые проверяются Robolectric и вручную». Честно названо картой, а не
  процентом покрытия: упоминание класса не доказывает покрытие ветвей.
- **`tools/jvm_unit_test.py` — реальный прогон тестов без Gradle.** `kotlinc` + шим `org.junit`
  (аннотации, `Assert`, раннер на reflection) компилируют и **выполняют** тесты чистой логики
  там, где нет Google Maven: в песочнице так проходят 165 тестов в 15 файлах. Заглушки
  `androidx.room`/`androidx.annotation`/`javax.inject`/`android.util.Log`/`android.os.Build`/
  `android.util.Base64`/`android.content.Context`, авто-генерация заглушки `R` и разворачивание
  `import org.junit.Assert.*` втаскивают под прогон модели, конвертеры, хеширование PIN, разбор улик
  и проверку патчей безопасности. Включён в `./tools/check_all.sh` (пропускается без `kotlinc`);
  `--self-test` проверяет сам раннер на синтетических тестах.
- **Тесты на непокрытую логику (98 новых, выполняются 158):** конвертеры Room (`ConvertersTest`, включая чтение
  неизвестных значений enum), эвристика «спрятанных» приложений (`HiddenAppHeuristicsTest`),
  флаги фрагментации IPv4 (`PacketMetadataTest`), устойчивость парсеров пакетов
  (`PacketParserRobustnessTest`: fuzz случайных байт, обрезанные и лживые заголовки),
  формат `IntrusionEvent.summary()` (`IntrusionEventSummaryTest`), сериализация улик
  `IntrusionEventEntity↔IntrusionEvent` (`IntrusionEventEntityExtTest`, 13 тестов);
  плюс усилены существующие: `DeviceHeuristicsTest` (7 → 18), `PinAttemptLimiterTest` (4 → 10),
  `SmsPartsTest` (4 → 10), `IntrusionScanPolicyTest` (4 → 8).
- **Лицензия MIT.** В корень добавлен `LICENSE` (MIT, © 2026 Vlad Vinys), раздел README
  «Лицензия» больше не утверждает «all rights reserved» — это противоречило файлу лицензии.
  Заодно удалён корневой `CHANGELOG.md`: он дублировал этот файл и содержал неверную дату
  релиза 1.0.0 (2026-10-28 вместо 2026-07-19), источник истины один — `docs/CHANGELOG.md`.
- **CI проверяет 16 КБ-выравнивание релизного APK.** Новый шаг `zipalign -c -P 16 -v 4` в
  `codemagic.yaml` и в GH-job `build-release` (пропускается, если APK или `zipalign` недоступны).
  Это фактическая проверка требования Play, а не только наличие библиотеки в зависимостях.
- **Codemagic: кэш Gradle** (`~/.gradle/caches`, `~/.gradle/wrapper`) — повторные сборки не
  перекачивают зависимости, что важно при лимите `max_build_duration: 120`.
- **`tools/gradle_check.py`: проверки зависимостей.** Пары «рантайм ↔ обработчик» с обязательным
  совпадением версий (`hilt-android`/`hilt-android-compiler`, `room-runtime`/`room-compiler`,
  `hilt-work`/`hilt-compiler`), предупреждения о предрелизных версиях, о явных версиях
  compose-артефактов при подключённом `compose-bom` и о закрытом
  `net.zetetic:android-database-sqlcipher` (нет поддержки 16 КБ страниц → блокер Play при
  `targetSdk 35`). Версии из `$rootProject.ext.*` резолвятся в реальные значения.
- **`tools/gradle_check.py` — проверка конфигурации Gradle.** Ловит ошибки, которые Gradle
  показывает только при запуске и всегда одним и тем же многоэкранным стектрейсом Groovy:
  функции Kotlin stdlib в `.gradle` (`mapOf`, `listOf`, `val`, `.let { }`), `rootProject.ext.X`
  без объявления, `id 'plugin'` без classpath, расхождение версий `kotlin-gradle-plugin` и
  `compose-compiler-gradle-plugin`, AGP против Gradle wrapper'а и `compileSdk`, нарушение
  `minSdk ≤ targetSdk ≤ compileSdk`, рассогласование `sourceCompatibility`/`jvmTarget`,
  отсутствующий proguard-файл, дубликаты зависимостей, отсутствие `android.useAndroidX`.
  Запускается первым в `./tools/check_all.sh`, в GH-job `static-checks` и в `codemagic.yaml`.
- **`tools/webhook_notify.py` / `tools/webhook_listen.py` — уведомления о сборке.** Один скрипт на
  Codemagic, GitHub Actions и локальную сборку: payload `build.finished` (статус, ветка, коммит,
  build id/url, артефакты из `CM_ARTIFACT_LINKS`), подпись `X-PhoneGuard-Signature: sha256=…`
  (HMAC-SHA256), 3 попытки с backoff, `--dry-run`, `--strict`, `--mark-success`. Ошибка доставки не
  роняет сборку, отсутствие `WEBHOOK_URL` — no-op. `webhook_listen.py` — локальный приёмник с
  проверкой подписи. Проверено end-to-end: `HTTP 200`, `подпись: верна`.
- **`codemagic.yaml`:** `triggering` (push/PR/теги `v*`, `main`/`master`, `cancel_previous_builds`),
  шаг `Mark build successful` (маркер `~/.phoneguard-build-success`, потому что publishing-скрипты
  выполняются и при падении) и `publishing.scripts` с `webhook_notify.py`; секреты
  `WEBHOOK_URL`/`WEBHOOK_SECRET` задаются переменными приложения (без обязательной
  переменной-группы, иначе сборка падала бы на чистом форке).
- **GitHub Actions:** job `notify` (`if: always()`, статус по `needs.*.result`).
- **`docs/CI_WEBHOOKS.md`:** таблица «входящий trigger / исходящий notify», схема payload, проверка
  подписи на Python/Node, настройка Codemagic и GitHub, локальный сценарий проверки.
- **`tools/kotlin_frontend_check.py` — проверка настоящим компилятором.** Запускает `kotlinc 2.1`
  на всех 209 файлах (`main` + `test` + `androidTest`) без Android SDK и отбрасывает диагностики,
  вызванные отсутствующими библиотеками (`-Xrender-internal-diagnostic-names` + список ожидаемых
  имён с пояснениями). Найдено и исправлено 7 ошибок компиляции, которые не видел регексп-аудит
  (см. Fixed). Без `kotlinc` скрипт завершается с кодом 0 и подсказкой, как его получить.
- **`tools/static_audit.py`: две новые проверки конструкторов.** «Тип литерала на позиции
  параметра» (ловит `BlockedNumber("+7999…", "Spam", false, ts)` — строка попадала в `id: Long`)
  и «не переданы обязательные именованные аргументы» (ловит `SimSwapEvent(...)` без
  `latitude/longitude/addressString`). Обе подтверждены поломкой кода.
- **`tools/check_all.sh`** — одна команда для всех доступных проверок (аудит, идемпотентность строк,
  компилятор Kotlin, парсер) с ненулевым кодом возврата при любой ошибке.
- **CI приведён в порядок.** Дублирующий `.github/workflows/ci.yml` удалён; `android-ci.yml` теперь
  запускается на `main`/`master` и на любой PR, отменяет устаревшие прогоны и первым делом гоняет
  быстрые `static-checks` (аудит + синхронизация строк) и `kotlin-frontend` (настоящий компилятор без
  Android SDK), а `lint`/`unit-test`/сборки идут после них; при падении тестов выгружаются отчёты.
  В `jules.yml` убран `|| echo`, который скрывал падения тестов, и убрано дублирование Gradle-задач —
  workflow проверяет только конфигурацию `.jules`.
- **`build_release.sh` переписан.** `set -euo pipefail`, проверка flavor/build type, запуск
  `static_audit.py` до сборки, задачи собираются из аргументов (`assembleProductionRelease`,
  `bundleProductionRelease`), а не хардкодятся; вместо тихого «файл не найден, ну и ладно» скрипт
  падает с ошибкой, показывает размер и SHA-256 артефактов. Путь к AAB исправлен:
  `app/build/outputs/bundle/<variant>/*.aab` (прежний путь не существовал). Без keystore сборка
  продолжается, но печатает предупреждение, что APK неподписанный.
- **Codemagic** теперь тоже гоняет `static_audit.py` и проверку синхронизации строк до Gradle
  и выгружает отчёты lint/тестов.
- **`tools/fetch_android_jar.sh`** — достаёт настоящий `android.jar` (кеш → установленный SDK →
  sparse checkout GitHub-зеркала `Sable/android-platforms`, ~26 МБ за секунды). Нужен там, где
  `dl.google.com`/`maven.google.com` закрыты.
- **`kotlin_frontend_check.py --android-jar`** — проверка с настоящим Android SDK на classpath:
  шумных диагностик 10 500 → 8 100, и если в сообщении фигурирует уже разрешённый тип `android.*`,
  диагностика считается настоящей ошибкой. Новое правило сразу нашло три дефекта (см. Fixed).
  В CI (`kotlin-frontend`) теперь устанавливается `platforms;android-35`, поэтому проверка идёт
  в строгом режиме.
- **`tools/aapt2_check.py` — настоящая проверка ресурсов и манифеста.** Запускает `aapt2 compile`
  всех ресурсов и `aapt2 link` манифеста против `android.jar` (как `processDebugResources`),
  генерирует `R.java` и сверяет с ним все ссылки `R.type.name` из Kotlin. Так найдены **три строки,
  на которых падала бы любая сборка** (aapt2: `unescaped apostrophe in string`): `values-en`
  «Build tags contain '%1$s'», «'which su' succeeded» и `values-de` «'which su' erfolgreich».
  Проверка добавлена в `tools/check_all.sh` и в CI-job `kotlin-frontend` (там же теперь ставится
  `build-tools`).
- **`static_audit.py`: проверка апострофов.** `check_string_apostrophes()` повторяет правило aapt2
  без самого aapt2 — ловит неэкранированный `'` в `<string>`, из-за которого сборка падает на
  ресурсах. `sync_strings.py` теперь сам экранирует апострофы (`\\'`) при добавлении строк.
- **`static_audit.py`: проверка YAML.** `check_workflow_yaml()` парсит `.github/workflows/*.yml`,
  `codemagic.yaml` и `.jules/*.json` (PyYAML опционален) и проверяет, что у каждого job есть
  `runs-on` и `steps`. Сразу нашла собственную ошибку: `run: python3 -c "…print('config.json: OK')"`
  в `jules.yml` — двоеточие с пробелом в plain-скаляре превращало строку в mapping, и **workflow
  вообще не запускался бы**.
- **`sync_strings.py --check`** — режим проверки без записи: печатает, каких ключей не хватает
  в каждой локали, и возвращает 1. CI и `tools/check_all.sh` используют его вместо
  `git diff -- app/src/main/res`, который ломался при незакоммиченных правках строк.
- **`static_audit.py`: мёртвые активности.** Проверка `check_dead_components()` смотрела только
  сервисы/приёмники/провайдеры; теперь проверяются и `<activity>`, кроме настоящих точек входа
  (`LAUNCHER`-ярлык, `BROWSABLE`-ссылки). Именно так нашлась недоступная `SimSwapConfirmationActivity`.
- **`static_audit.py`: недостижимые экраны.** Новый `check_unreachable_screens()` ловит
  `@Composable fun XxxScreen(...)`, который нигде не вызывается. Так нашёлся `FirewallLogsScreen`:
  экран полного журнала брандмауэра был полностью реализован, но в граф навигации не попал.
  Теперь он доступен кнопкой в шапке экрана брандмауэра (маршрут `firewall_logs`), а не только
  последними 20 записями.
- **Служебные документы приведены к реальности.** `AGENTS.md` переписан (актуальные команды,
  границы, конвенции; удалён блок про сторонние «skills/MCP» и таблицу немецких API, которых в
  проекте нет), `HANDOFF.md` — вместо устаревшей очереди задач T1 теперь текущее состояние и
  открытые пункты, `.qwen/PROJECT_SUMMARY.md` — исправлены «13 шагов» (их 16), убрано утверждение,
  что сборка стабильно падает, и упоминание `android.overridePathCheck`, которого в
  `gradle.properties` нет.
- **Документация по проверкам:** в `docs/DEVELOPMENT.md` добавлен раздел про новый скрипт (включая
  установку компилятора из npm в песочнице без Maven Central), в `README.md` и `docs/RELEASE.md`
  обновлены списки проверок.

- **Deep links и ярлыки заработали.** В графе навигации ссылки `phoneguard://…` были объявлены
  (`navDeepLink`), но манифест не принимал схему, а `handleDeepLink(intent)` никто не вызывал:
  ярлык «Антивор» на рабочем столе открывал дашборд. Теперь у `MainActivity` есть VIEW/BROWSABLE
  фильтр и `launchMode="singleTop"`, intent передаётся в `PhoneGuardNavHost` (`deepLinkIntent`),
  а повторное открытие обрабатывается в `onNewIntent`. Работает и снаружи:
  `adb shell am start -a android.intent.action.VIEW -d phoneguard://settings`.
- **`tools/kotlin_analysis.py`** — разбор Kotlin настоящим парсером (tree-sitter, зависимость
  опциональная): синтаксис всех 209 `.kt` и поиск вызовов `suspend`-функций вне suspend-контекста.
  Проверкой найден реальный баг сборки (см. Fixed). Ложные срабатывания из-за одноимённых функций
  перечислены в `tools/kotlin_analysis_allow.txt` с пояснениями.
- **`tools/static_audit.py`: две новые проверки.** «Уникальность id уведомлений» (был конфликт
  1001 у блокировщика и VPN) и «deep links»: каждая ссылка из `shortcuts.xml` должна быть в графе,
  схема — в манифесте, а в NavHost — вызов `handleDeepLink`.
- **`docs/DEVELOPMENT.md`** — правила работы с репозиторием: окружение, команды, конвенции строк и
  `CheckDetail`, безопасная отмена корутин, id уведомлений, инструменты и ограничения песочницы.

- **Тесты сейфа**: `VaultViewModelTest` покрывает ошибку предпросмотра (битый шифротекст →
  `VaultError.PreviewFailure`, интент не создаётся) и сброс этой ошибки.

- **Сейф: предпросмотр файлов**. Кнопка «открыть» была заглушкой, то есть файл можно было
  добавить, но не открыть. Теперь `VaultViewModel.previewItem()` расшифровывает копию в
  `cacheDir/vault_preview`, отдаёт её через `FileProvider` (`${applicationId}.fileprovider`,
  `res/xml/file_paths.xml` открывает только этот каталог) и запускает `ACTION_VIEW`;
  предыдущий предпросмотр удаляется, `FLAG_SECURE` продолжает действовать на экране сейфа.
- **Чек всего телефона** (`deviceaudit/DeviceAuditChecker`, экран `ui/screens/phonecheck`,
  карточка на дашборде, deep link `phoneguard://phonecheck`): 20 проверок устройства
  (блокировка экрана, шифрование, SELinux, ADB, режим разработчика, патч-безопасности,
  Play Protect, неизвестные источники, свободное место, устаревшие/debuggable-приложения,
  службы доступности, разрешения, уведомления, энергосбережение, администраторы, версия ОС/модель)
  с оценкой 0..100 и рекомендациями. Результат сохраняется в DataStore.
- Полное сканирование расширено до **16 шагов**: чек телефона выполняется последним и добавляет
  найденные проблемы в общий отчёт (`ScanIssue`). Этапы сканирования теперь `ScanStage` с `@StringRes`
  (прогресс переводится), а «Проверить весь телефон» доступно ещё и из настроек.
- `tools/static_audit.py`: новые проверки — «известный Android/Compose-символ без импорта»,
  импорты `getValue`/`setValue` для `by`-делегирования, вызовы несуществующих методов Room-DAO,
  обращений к несуществующим методам классов проекта, Hilt-привязки для интерфейсов,
  `@InstallIn` у `@Module`, `android:exported` у компонентов манифеста, таблицы/колонки в `@Query`,
  запрошенные, но не объявленные разрешения, ссылки `@string/@drawable/...` в XML ресурсов и манифеста,
  паритет ключей во всех локалях, число аргументов `getString`/`stringResource` против плейсхолдеров,
  `PendingIntent` без `FLAG_IMMUTABLE`, `startActivity` вне `IntentUtils.open`, `startService` вне
  `try`/`runCatching`, мёртвые компоненты манифеста, соответствие миграций Room схемам `@Entity`,
  существование маршрутов навигации, импорты `Icons.Default.*` (extension-свойства), закрытие блочных комментариев «/*»/«*/»,
  `<uses-feature required="false">` для аппаратных разрешений, правила резервного копирования,
  типы возврата в mockk-заглушках (`returns Unit` для `Boolean`-функции), число аргументов
  конструкторов проекта, согласованность Gradle (`rootProject.ext`, `room.schemaLocation`,
  assets для `MigrationTestHelper`), неиспользуемые импорты. Эти проверки нашли 7 реальных ошибок компиляции
  (экраны dashboard/anti-theft/settings/consent, `ShoulderSurferService`) и необъявленную
  константу `TOTAL_STEPS` в `FullScanOrchestrator`; удалено 93 мёртвых импорта.
- **libphonenumber** (`com.googlecode.libphonenumber`): `util/PhoneNumberNormalizer.kt` — E.164-нормализация
  для чёрных/белых списков, скрининга звонков и CSV-импорта (офлайн metadata).
- **apk-parser** (`net.dongliu:apk-parser`): `malware/ApkSignatureScanner.kt` — цепочка сертификатов,
  алгоритм подписи, срок действия, подпись debug-ключом, изменение APK после установки.
  Подключено к `ApkSignatureCheck` (15-й шаг полного скана) и к `SpywareCheckRepository`.
- **OkHttp** + `threatintel/` — opt-in проверка доменов из подозрительных SMS по URLhaus/OpenPhish;
  по умолчанию выключено, наружу уходит только имя хоста.
- Локальные замены Firebase: `util/AnalyticsHelper.kt`, `util/PerformanceMonitor.kt`, `util/CrashHandler.kt`
  (отчёты о падениях в `files/crash-reports/`, без сети).
- Honeypot теперь реально отслеживается (`HoneypotObserver`), событие `HONEYPOT_ACCESSED`.
- Тумблер «Threat intelligence» в настройках, `PreferencesManager.isThreatIntelEnabled`.
- `tools/static_audit.py` (проверка ресурсов/дубликатов/баланса скобок) и `tools/sync_strings.py`
  (синхронизация 4 локалей).

### Security
- **Подбор PIN по SMS больше не бесконечен.** `SmsCommandReceiver` (`#PG:PIN:CMD`) проверял PIN
  без всяких ограничений — 4-значный код перебирался за минуты. Добавлен
  `util/PinAttemptLimiter` (5 неудач → блокировка 15 минут, успешный ввод сбрасывает счётчик,
  состояние в DataStore — перезагрузка телефона блокировку не снимает; логика покрыта
  `PinAttemptLimiterTest`). При отклонённой команде владелец получает локальное уведомление
  «Попытка управлять телефоном по SMS» (наружу ничего не уходит).
- `setPin()` отклоняет нецифровой/короткий PIN: раньше его можно было сохранить, и тогда
  SMS-команды (`\d+` в шаблоне) переставали совпадать с PIN — удалённое управление ломалось молча.
- **Перенос данных на новое устройство закрыт.** `android:allowBackup="false"` сам по себе не
  запрещает Device-to-Device transfer на Android 12+: правила нужно задавать в
  `dataExtractionRules`. Файл `res/xml/data_extraction_rules.xml` существовал, но не был подключён
  к манифесту и **включал** `sharedpref` (хэш PIN, ключи) в копию. Теперь он подключён и исключает
  все домены (`root`, `file` — сейф и фото вторжений, `database`, `sharedpref`, `external`)
  и для cloud backup, и для D2D; `backup_rules.xml` приведён к тому же виду как fail-safe.
- **`<uses-feature required="false">`** для камеры, вспышки, микрофона, GPS и телефонии: без них
  Play считал эти модули обязательными и скрывал приложение от Wi-Fi-only планшетов.

### Removed
- **Мёртвый код, который вводил в заблуждение:** `util/PatternRules.kt` с тестом `PatternRulesTest`
  (шаблоны `*`/`?` для чёрного списка) нигде не использовался — список блокируется точным SQL-совпадением
  по канонизированному номеру, так что тест «шаблоны работают» проверял то, чего нет в приложении.
  Также удалены неиспользуемые `model/MalwareApp.kt` (`MalwareApp`/`MalwareType`),
  `TransportProtocol` и `IntrusionSeverityPolicy`.

- **Неиспользуемые разрешения удалены из манифеста:** `PACKAGE_USAGE_STATS` (нет ни одного
  `UsageStatsManager`), `ANSWER_PHONE_CALLS`, `ACCESS_WIFI_STATE`, `WAKE_LOCK` (WorkManager объявляет
  его сам), `MANAGE_NETWORK_POLICY`, `RECORD_AUDIO` (записи звука нет — разрешение фигурировало только
  как метка в списках чужих приложений), `READ_MEDIA_IMAGES/VIDEO/AUDIO` и три
  `FOREGROUND_SERVICE_*` (`dataSync`/`location`/`microphone`), для которых нет ни одного сервиса.
  `READ_EXTERNAL_STORAGE` ограничено `maxSdkVersion="32"`. `docs/PRIVACY_POLICY.md` приведён
  в соответствие: убраны `PACKAGE_USAGE_STATS`, `RECORD_AUDIO`/`MICROPHONE` и обещание читать
  общее хранилище на Android 13+.
- **`ui/screens/PlaceholderScreens.kt`** — экран-заглушка с русским литералом «Coming Soon!»
  не вызывался ниоткуда.

### Changed
- **Зависимости приведены к актуальным стабильным версиям** (существование версий проверено по
  публичному коду на GitHub: `androidx.room:room-runtime:2.7.2` — 756 файлов,
  `net.zetetic:sqlcipher-android:4.16.0` — 29 файлов):
  - **Room 2.6.1 → 2.7.2.** 2.6.1 выпущен под Kotlin 1.9, а проект на Kotlin 2.1: его kapt-обработчик
    не читает metadata 2.1, а пин `kotlinx-metadata-jvm:0.7.0` («Force newer … for Kotlin 2.1»)
    этого не решает — 0.7.0 понимает только metadata 1.9. Пин удалён, Room обновлён до версии
    с поддержкой Kotlin 2.x.
  - **SQLCipher: `android-database-sqlcipher:4.5.4` → `net.zetetic:sqlcipher-android:4.16.0`**
    (последняя версия, собранная с `compileSdk 34`; 4.19+ требует `compileSdk 36` — см. Fixed).
    Старый артефакт закрыт (2023) и не поддерживает 16 КБ страницы памяти → Play не примет обновление
    с `targetSdk 35`. Новый пакет — `net.zetetic.database.sqlcipher`: `SupportFactory` →
    `SupportOpenHelperFactory`, `SQLiteDatabase.loadLibs(this)` → `System.loadLibrary("sqlcipher")`,
    `androidx.sqlite:sqlite:2.6.2` добавлен как compile-зависимость библиотеки. Keep-правила R8
    расширены на новый пакет.
  - **`androidx.security:security-crypto` 1.1.0-alpha06 → 1.1.0** и **`androidx.biometric`
    1.2.0-alpha05 → 1.1.0** — обе стабильные, используемые API (`EncryptedFile`, `MasterKey`,
    `BiometricPrompt`, `BiometricManager.Authenticators`) в них есть.
  - **`material3` без явной версии** — её задаёт `compose-bom:2024.12.01` (1.3.1); явная версия
    отключала управление BOM (на это ругается `gradle_check.py`).
- **Конфигурация Gradle приведена в порядок.** Удалён устаревший `android.enableAapt2` (в AGP 8
  не используется), добавлены `android.suppressUnsupportedCompileSdk=35` (осознанное использование
  compileSdk 35 на AGP 8.5.2) и `org.gradle.configuration-cache.problems=warn` — проблемы кэша
  конфигурации при kapt/Hilt больше не роняют сборку.
- **Порядок инициализации в `PhoneGuardApplication`** зафиксирован комментариями и исправлен:
  обработчик падений ставится первым (до StrictMode и WorkManager), чтобы ловить сбои
  инициализации; `crashHandler.install()` больше не вызывается после StrictMode.

### Fixed
- **`DeviceAuditModelsTest` ожидал неверную оценку.** В тесте «чека всего телефона» стояло
  `assertEquals(51, report.score)` при 15+15+7 = 63 — тест никогда не запускался (Gradle не
  доходил до тестов), и на первом же реальном прогоне упал бы. Ожидание исправлено на 63.
- **`IntrusionEvent.summary()` оставлял хвостовой пробел и разделял улики запятыми.** При пустом
  `evidence` получалось `"BRUTE_FORCE MEDIUM "`, а `"a=1, b=2"` неудобно грепать. Теперь
  `"BRUTE_FORCE MEDIUM"` и `"a=1 b=2"`.
- **Пакет `android` не считался доверенной службой доступности.** В списке префиксов стоял только
  `android.`, поэтому собственный пакет системного фреймворка попадал в «недоверенные включённые
  службы» — ложная находка в отчёте. Добавлено точное совпадение.
- **Улики вторжений терялись при записи в базу.** Поле хранится строкой `k=v;k=v` без
  экранирования: значение с `;` (`path=/sdcard/a;b.jpg`) читалось как две улики, значение с `=`
  теряло хвост, а обратная косая ломала разбор. Добавлено экранирование `\ ; =` при записи и
  разбор с учётом экранирования при чтении; строки старого формата разбираются как раньше,
  миграция не нужна.
- **`CheckAarMetadataTask`: `sqlcipher-android:4.19.1` требует `compileSdk 36`.** Сборка падала с
  «requires libraries and applications that depend on it to compile against version 36 or later».
  Виновник — сама библиотека: с 4.19 она собирается с `compileSdk 36`, и её AAR-metadata требует 36
  от потребителя, а проект на AGP 8.5.2 / `compileSdk 35`. Взята последняя версия, собранная с
  `compileSdk 34`, — **4.16.0** (16 КБ страницы в ней поддерживаются: `APP_SUPPORT_FLEXIBLE_PAGE_SIZES`
  есть), `androidx.sqlite:sqlite` приведён к её compile-зависимости **2.6.2**. В `gradle_check.py`
  добавлена проверка «версия зависимости против `compileSdk`» — такая несовместимость теперь
  ловится статически, а не на `CheckAarMetadataTask`. Правило покрыто самотестом
  (`python3 tools/gradle_check.py --self-test`, вызывается из `./tools/check_all.sh`).
- **Codemagic падал на шаге `Lint`: `Task 'lintDebug' is ambiguous`.** У проекта два flavor'а
  (`staging`, `production`), поэтому задач без flavor'а не существует: Gradle предлагал
  `lintProductionDebug`, `lintStagingDebug` и ещё десяток кандидатов. `lintDebug` заменён на
  `lintProductionDebug`, `testDebugUnitTest` — на `testProductionDebugUnitTest` во всех CI-конфигах
  и документации (README, `docs/DEVELOPMENT.md`, `AGENTS.md`, `HANDOFF.md`, `.qwen/PROJECT_SUMMARY.md`,
  `app/schemas/README.md` — там же `kspDebugKotlin` исправлен на `kaptProductionDebugKotlin`, проект
  использует kapt). В `gradle_check.py` добавлена проверка имён задач: `./gradlew lintDebug` при
  наличии flavor'ов теперь ошибка с подсказкой правильного имени.
- **R8/ProGuard: подписи обобщённых типов и JSR-305.** В `app/proguard-rules.pro` не было
  `-keepattributes Signature` (Gson/Room не восстанавливают generic-поля после обфускации —
  падение уже в рантайме) и `-dontwarn javax.annotation.**` (R8 считает отсутствующие
  compileOnly-аннотации ошибкой и валит `assembleProductionRelease`). Добавлена проверка
  синтаксиса правил в `gradle_check.py`: неизвестная директива (опечатка `-dontwarns`) ловится
  заранее, тело `-keep … { … }` при этом не разбирается.
- **`aapt2_check.py` падал трейсбеком, если `aapt2` без права на исполнение.** Теперь
  `run()` превращает `OSError` в понятный лог, а отсутствие `+x` сообщается отдельно
  (`chmod +x '<путь>'`) вместо `PermissionError` из `subprocess`.
- **Codemagic падал на `Lint`: `mapOf(...)` из Kotlin stdlib в Groovy DSL.**
  `app/build.gradle` вызывал `arguments += mapOf("room.schemaLocation" to …)` — такого метода в
  Groovy нет, поэтому конфигурация проекта падала с `MissingMethodException`, и вместе с ней
  **любая** задача: `lintDebug`, `testDebugUnitTest`, сборки. Заменено на литерал Map
  (`arguments = ["room.schemaLocation": projectDir.absolutePath + "/schemas"]`). Заодно
  `app/build.gradle` приведён к каноническому Groovy-виду (`= ` вместо method-call для свойств:
  `minSdk`, `targetSdk`, `versionCode`, `minifyEnabled`, `shrinkResources`, `compose`,
  `abortOnError`, `correctErrorTypes`, `vectorDrawables.useSupportLibrary`), а `flavorDimensions`
  оставлен вызовом метода — у него нет сеттера в публичном API AGP.
- **`gradlew` перестал врать при сетевом сбое.** Добавлены `set -eu`, `curl -f --retry`,
  проверка распаковки и понятное сообщение вместо `unzip: cannot find zipfile`; чистка задачи
  `clean` переведена с устаревшего `buildDir` на `layout.buildDirectory` (Gradle 9).
- **Ложное падение `aapt2_check` в Codemagic: `'specialUse' is incompatible with attribute
  foregroundServiceType`.** Причина — не манифест, а выбор `android.jar`: платформы
  сортировались лексикографически, поэтому `platforms/android-9` считался «новее»
  `android-35`, и манифест линковался против API ≤ 33, где `specialUse` (API 34) ещё нет.
  Теперь платформы сортируются численно, приоритет — точное совпадение с `compileSdk`
  (из `app/build.gradle`), затем ближайший больший, затем самый новый jar. Если jar всё же
  старее `compileSdk`, `link` пропускается с пояснением (ресурсы проверены), а новый флаг
  `--require-compile-sdk` делает это ошибкой — он включён в GitHub Actions, где `android-35`
  ставится явно; в `codemagic.yaml` добавлен шаг `sdkmanager "platforms;android-35"`.
- **`.pyc` больше не в репозитории.** `tools/__pycache__/*.pyc` были закоммичены (и менялись при
  каждом запуске проверок) — удалены из индекса, в `.gitignore` добавлены `__pycache__/` и `*.py[cod]`.
- **Найдено компилятором с `android.jar` (три реальные ошибки):**
  - `SimSwapDetector`: `Geocoder.getFromLocation()` возвращает `List<Address>?` — результат
    присваивался в non-null `List<Address>` (NPE, который молча съедал внешний `catch`, и адрес
    подмены SIM терялся) — добавлен `?: emptyList()`;
  - `PrivacyScannerRepository`: `pkg.applicationInfo` (в SDK помечен nullable) разыменовывался
    без проверки — теперь пакеты без `applicationInfo` пропускаются;
  - `HiddenAppsCheck`: `packageInfo.applicationInfo.targetSdkVersion` — то же самое, добавлена
    явная проверка.
- **Подтверждение подмены SIM теперь можно получить.** `SimSwapConfirmationActivity` была объявлена
  в манифесте, но её никто не запускал, а `SimSwapDetector` только отправлял событие в
  `uiEvents` — горячий `SharedFlow` без replay, у которого в фоне (обычный случай для подмены SIM)
  нет подписчика. Пользователь ничего не видел и не мог подтвердить/отклонить смену SIM, то есть
  сценарий «это не я — блокируй устройство» не срабатывал. Теперь `simswap/SimSwapNotifications.kt`
  показывает уведомление с переходом на экран подтверждения (id 4101, отдельный канал), экран
  сам показывается поверх блокировки (`setShowWhenLocked` на API 27+, window-флаги на 26),
  а после подтверждения/отклонения уведомление снимается.
- **Манифест приведён в порядок:**
  - `READ_CLIPBOARD_IN_BACKGROUND` удалено — буфер обмена в коде не читается ни разу;
  - `SimSwapConfirmationActivity`: `exported="true"` → `false` (запускается только своим
    `PendingIntent`, чужие приложения не могут показать поддельное «подтвердите SIM»),
    убран пустой `intent-filter` с одним `MAIN`;
  - убраны непубличные атрибуты `android:showOnLockScreen`/`android:turnScreenOn` у
    `SimSwapConfirmationActivity` и `IntrusionWarningActivity` — они ничего не делали, «поверх
    блокировки» выставляется из кода.
- **Компиляция: 7 реальных ошибок, найденных фронтендом Kotlin**
  - `FullScanOrchestrator`: `@Volatile` стоял на `val` (запрещено) — убран, атомарность и так даёт
    `AtomicBoolean.compareAndSet`;
  - `FirewallScreen` вызывал `viewModel.loadApps()`, который был `private` — метод открыт (экран
    вызывает его кнопкой «обновить»);
  - `SimSwapDetector`: `return` внутри lambda `withContext` — заменён на `return@withContext`;
  - `PermissionAbuseCheckTest`: имя enum-класса (`PermissionAbuseCheck.PermissionGroup`) присвоено
    в `val` и использовалось как `groups.CAMERA` — теперь импорт вложенного enum;
  - `PhishingUrlCheckerTest`: в backtick-имени теста была точка (`t.me`) — Kotlin такие имена
    запрещает, тест переименован;
  - `BlockedNumberDaoInstrumentedTest`: позиционные аргументы `BlockedNumber(...)` не совпадали с
    конструктором (`id: Long` первым) — переведены на именованные;
  - `SimSwapViewModelTest`: в `SimSwapEvent(...)` не передавались обязательные `latitude`,
    `longitude`, `addressString` — добавлены.

- **`PhoneGuardVpnService`: не компилировалось.** `handlePacket` объявлен `suspend`, а вызывался из
  обычной функции `runVpnLoop` («suspension functions can be called only within a coroutine body»).
  `runVpnLoop` стал `suspend` — он и так запускается внутри `vpnScope.launch`.
- **Документация приведена к коду:** убраны несуществующие возможности и устаревшие имена
  («ML-based анализ APK», «SSL pinning», «SafetyNet/Play Integrity», Firebase-раздел,
  `SimChangeReceiver`, раздел про AI-агентов и MCP-серверы в README), исправлены числа
  (16 шагов сканирования, 20 проверок телефона, 634 строки в каждой локали), переписаны
  `MANUAL_TESTING.md` (сценарии SMS-фильтра, чека телефона, threat intel) и `PLAY_STORE_LISTING.md`,
  политика конфиденциальности дополнена разбором SMS и полным списком разрешений.

- **Фильтр входящих SMS (новое).** Приложение обещало «Блокировщик звонков & SMS», журнал
  поддерживал `isSms = true`, но входящие SMS никто не проверял: был только
  `SmsCommandReceiver` для анти-воровских команд. Добавлен `SmsSpamReceiver`
  (`SMS_RECEIVED`, priority 998, `BROADCAST_SMS`): белый список важнее правил, затем чёрный
  список и локальная спам-база; запись в журнал и тихое уведомление с маскированным номером.
  Отмена доставки (`abortBroadcast`) выполняется только если PhoneGuard — приложение SMS по
  умолчанию, и это честно написано под правилами блокировки (`sms_blocking_hint`).
  Многочастные SMS склеиваются по отправителю (`SmsParts`, покрыт `SmsPartsTest`).
- **Канал и уведомления блокировщика вынесены в `BlockerNotifications`**: канал создавался
  в двух местах по-разному, а id звонка/`SMS` теперь различаются (1101/1102) и не пересекаются
  с уведомлением VPN (1001).
- **`AnalyticsHelper.logCallBlocked` больше не принимает номер телефона** — раньше подпись
  требовала его и вызывающий код передавал персональные данные в «аналитику». Удалены
  неиспользуемые `logScreenView`, `logFirewallBlock`, `logConsentResult` (наследие Firebase).
- **Workers и ViewModel-ы не глотали отмену:** `ScheduledFullScanWorker`, `DataCleanupWorker`,
  `FullScanViewModel`, `SpywareCheckViewModel`, `PrivacyScannerViewModel` ловили
  `CancellationException` обычным `catch (Exception)` и превращали отмену в `Result.retry()`
  или в «ошибка сканирования». Теперь отмена пробрасывается, а отменённая загрузка не гасит
  индикатор новой (`if (isActive)`), из-за чего после повторного запуска экран оставался пустым.
- **Локаль в сравнениях:** `SmsCommandReceiver` (`uppercase()`) и `ApkSignatureScanner`
  (`uppercase()`) зависели от локали — в турецкой команда «wipe» превращалась в «WİPE», а
  «sha256withrsa» в «SHA256WİTHRSA». Теперь `Locale.US`.
- **Фишинг в SMS теперь ловится и в фильтре входящих:** используется локальная эвристика
  `PhishingUrlChecker.isSuspicious` (ссылка + давящие слова, без сети и без отправки текста
  наружу). Такое сообщение журналируется и показывается как «Подозрительное SMS», но
  доставка **не отменяется** — эвристика ошибается, и молча съедать чужую SMS нельзя
  (отмена `abortBroadcast` только при блокировке по номеру).
- **VPN-фаервол не оставался «активным» после обрыва туннеля:** когда цикл чтения
  заканчивался сам (EOF/ошибка), сервис продолжал висеть foreground-сервисом с уведомлением
  «блокировка включена», приложения уже ходили в сеть мимо туннеля, а повторный `CONNECT`
  упирался в `vpnInterface != null` и ничего не делал. Теперь состояние сбрасывается и
  сервис останавливается; заодно убран двойной `notify()` на каждый заблокированный пакет.
- **`IntrusionResponseExecutor` не компилировался:** строка `R.string.intrusion_sms_prefix`
  использовалась без `import com.phoneguard.R`. Добавлен импорт и проверка в `tools/static_audit.py`
  (`R.… без import com.phoneguard.R`), чтобы такой случай больше не проходил незамеченным.
- **Диагностика проверок переведена на ресурсы.** `SystemIntegrityCheck`, `RootDetectionCheck`,
  `DeveloperOptionsCheck`, `FileSystemScanCheck`, `ApkSignatureCheck`, `PhishingUrlChecker` и
  `PermissionAbuseCheck` собирали строки `details` по-русски прямо в коде. Теперь это ресурсы
  (`integrity_*`, `root_detail_*`, `dev_options_*`, `file_scan_limit_*`, `apk_sig_checked_count`,
  `phishing_limit_*`), а `PermissionAbuseCheck.assessRisk` возвращает `CheckDetail` (ресурс +
  аргументы) и не требует `Context` — его можно тестировать без mockk-`getString`.
- **`SecurityPatchCheck` больше не отдаёт русское «неизвестно»** в отчёт: при пустой дате
  возвращается пустая строка, а подпись подставляет оркестратор (`patch_date_unknown`).
  Детали стали `CheckDetail`; тест сверяет `textRes` вместо слов «Внимание»/«в будущем».
- **Наборы тестов, которые не компилировались:** `PermissionAbuseCheckTest` звал приватный
  `assessRisk` с `Set<String>` вместо `Set<PermissionGroup>`, `RootDetectionCheckTest` вызывал
  метод другого класса, `PhishingUrlCheckerTest` — конструктор с одним аргументом вместо двух,
  `PrivacyScannerViewModelTest` передавал `null` в не-nullable `Drawable`. Всё исправлено.
- **Тесты, которые не могли пройти:** `SettingsViewModelTest` не импортировал `coEvery`;
  `CallBlockerViewModelTest` ждал второго события от замоканного DAO (Turbine-таймаут);
  `PrivacyScannerViewModelTest` перезаглушал поток уже после `init`; `FullScanViewModelTest`
  ожидал блокировку второго скана на мгновенно завершающемся потоке. Тесты переписаны на
  детерминированный сценарий (`CompletableDeferred`, `first { … }`, проверка аргументов DAO).
- **Инструментальные UI-тесты проверяли русские строки напрямую** (`«Настройки»`, `«Язык»`,
  `«Все»`, `«Сканировать сейчас»`) — на устройстве с английской локалью они падали бы.
  Теперь тексты берутся из ресурсов, а иконка сканирования ищется по `contentDescription`.
- **`AppDatabaseMigrationTest` не мог пройти:** `MigrationTestHelper` требует экспортированных
  JSON-схем, а `app/schemas/` содержит только README. Инструментальный тест заменён на
  JVM-тест `AppDatabaseMigrationConfigTest` (непрерывность цепочки миграций, отсутствие
  дублей, совпадение с `LATEST_VERSION`); список миграций теперь один — `AppDatabase.ALL_MIGRATIONS`.
- **Технические сообщения `DatabaseKeyManager`** переведены на английский: они попадают в лог и
  crash-report, а не в UI, где показывается локализованный текст.
- **Уведомление VPN-файрвола**: счётчик блокировок обновлялся по `blockedCount % 10`
  после ограничителя частоты логов, поэтому при всплеске трафика уведомление «замирало»,
  а само чтение счётчика из другого потока не было видимым (`@Volatile`). Теперь обновление
  привязано к разнице счётчиков и вызывается до троттлинга.
- **Ответы на SMS-команды и причины находок** (`SmsCommandReceiver`, `AccessibilityServiceCheck`,
  `DeviceAdminAbuseCheck`, `OverlayAttackCheck`) были захардкожены по-русски; теперь это
  ресурсы (`sms_reply_*`, `sim_state_*`, `sms_status_*`, `accessibility_reason_*`,
  `device_admin_*`, `overlay_reason`), а мёртвая константа `UNKNOWN_APP_NAME` удалена.
- **Экспорт истории SIM в CSV**: `File(null, …)` при недоступном внешнем хранилище давал
  относительный путь, ошибка записи проглатывалась, а тост всё равно сообщал об успехе;
  кавычки в адресе не экранировались. Теперь функция возвращает путь или `null`, сообщение
  соответствует результату, поле адреса экранируется, у списка истории есть `key`.
- **Honeypot**: файл-маркер `<имя>.marker` не подходил под маску приманок, поэтому не
  отслеживался и не удалялся — создание удалено, остаётся только `HoneypotManager`.
- **`FullScanViewModel`**: флаг «сканирование идёт» мог остаться `true`, если оркестратор
  завершал поток без результата (скан уже запущен воркером) — снимается в `finally`.
- **Отчёт полного сканирования был только на русском.** Все `ScanIssue` (заголовок,
  описание, рекомендация) собирались строками в `FullScanOrchestrator`; в английской и
  немецкой локали пользователь получал русский текст — и в приложении, и в PDF. Тексты
  вынесены в 58 ресурсов `scan_issue_*`/`hidden_app_reason_*`/`perm_*`/`apk_sig_reason_*`
  (ru/en/de), `FullScanOrchestrator` получает `Context` и формирует их через
  `getString(...)`. Заодно локализованы причины находок `HiddenAppsCheck`,
  `FileSystemScanCheck`, `ApkSignatureCheck`.
- **Логика зависела от русского текста.** `PermissionAbuseCheck` использовал названия групп
  разрешений («Камера», «СМС») как ключи множеств, а `FullScanOrchestrator` искал подстроку
  «Подозрительное» в `riskReason`. Теперь есть `PermissionGroup` (enum + `labelRes`) и
  структурный флаг `HiddenApp.isSuspiciousName`.
- **Две ошибки компиляции**: `VaultScreen` передавал несуществующий `scaffoldState` в
  `Scaffold`, а `ApkSignatureCheck` использовал необъявленную переменную `pkg`.
- **`CircularProgressIndicator`** на дашборде вызывался с `Float` — в Material3 1.3 это
  устаревшая перегрузка; переведено на лямбду `progress = { … }`.
- **`FullScanViewModel`**: `loadHistory()` запускал новый `collect` при каждом вызове
  (подписки накапливались); ошибка сканирования и ошибка экспорта PDF проглатывались
  молча — теперь пользователь видит снекбар, а подписка на историю одна.
- **`EventViewModel`** использовал `MutableSharedFlow(extraBufferCapacity = 1)`: событие,
  отправленное до подписки, терялось, а `emit` без коллектора зависал. Переведено на
  `Channel` (доставка ровно один раз), удалён мёртвый наблюдатель жизненного цикла.
- **`SimSwapViewModel`**: события экрана подтверждения терялись при повороте, а таймер
  автоблокировки каждый раз начинался заново с 300 с (при повторном открытии экрана
  блокировка могла не наступить никогда). Теперь `Channel` и отсчёт от времени события.
- **Поле резервного номера в «Анти-краже»** перезаписывалось при каждом изменении
  `uiState` — набранный текст стирался при переключении любого тумблера.
- **`NetworkMonitor`**: `registerNetworkCallback` мог бросить исключение (лимит колбэков,
  отозванное разрешение) и уронить сборщика потока; регистрация и отмена теперь под
  `runCatching`.
- **Дашборд** не обновлял сводку последнего скана после возврата с экрана сканирования —
  добавлено обновление по `ON_RESUME`.
- **`VaultScreen` не компилировался**: в `Scaffold(...)` передавался несуществующий
  `scaffoldState` (дважды на одной строке — `scaffoldState = scaffoldState`). Строка удалена.
- **Сейф оставался открытым в фоне** и был недоступен без настроенной биометрии. Добавлена
  автоблокировка по `ON_STOP` (`VaultViewModel.lock()`) и запасной вход по коду/ключу
  устройства (`DEVICE_CREDENTIAL`), если палец не зарегистрирован.
- **Путь из БД в сейфе**: `decryptFileFromVault()`/`deleteVaultFile()` принимали имя файла из
  записи `VaultItem` без проверки каталога, поэтому подменённое имя `../../databases/…`
  вывело бы расшифровку за пределы сейфа. Добавлена проверка канонического пути.
- **Скрининг звонков отвечал не по правилам**: `respondToCall()` вызывался из IO-корутины
  (метод требует главного потока), без ограничения времени — при медленном чтении базы
  система не получала ответ и звонок проходил мимо блокировки. Теперь `withContext(Main)`
  и таймаут 4 с (не успели — пропускаем, fail-open).
- **Уведомление «звонок заблокирован» затирало статус VPN**: оба использовали id 1001.
  Блокировщик переведён на 1101, а id уведомлений о новых пакетах ограничены диапазоном
  2000..6095 (раньше до 67 535 и могли совпасть с 8101/9001).
- **«Удалить все данные» не удаляло всё**: журнал вторжений, спам-номера и файлы (снимки
  нарушителя, приманки, отчёты о падениях, экспорт, расшифрованные копии) оставались на
  устройстве; ошибка любого шага валила корутину, а флаг успеха выставлялся всегда. Теперь
  очистка включает `intrusion_events`, `spam_numbers` и `SettingsRepository.deleteLocalFiles()`,
  а о сбое сообщается тостом.
- **Спам-база засевалась заново после удаления записей**: условие «таблица пуста» заменено
  одноразовым флагом `spam_db_seeded` в DataStore (сбрасывается при полной очистке данных).
- **`exportLogs()` в настройках молчал при ошибке чтения из БД** (исключение валивало
  корутину, пользователь не видел ни успеха, ни ошибки) — обёрнуто в `runCatching`.
- **Версия в настройках** была захардкожена («PhoneGuard v1.0.0») — теперь `BuildConfig.VERSION_NAME`.
- **Порт-скан больше не срабатывает на браузеры и мессенджеры.** VPN-детектор создавал
  событие `HIGH` уже на 30 разных портах за 10 секунд, то есть на штатном профиле любого
  браузера (QUIC, P2P, рекламные сети), и мог включить автоматическую реакцию. Теперь порог
  60 портов, системные приложения и сам PhoneGuard не проверяются, на один UID — не больше
  одного события в 10 минут, а пакетный поток больше не требует загруженного кэша пакетов.
- **SMS из пяти мест вели себя по-разному.** `SecurityService` и `IntrusionResponseExecutor`
  не разбивали длинный текст на части (длинное предупреждение обрезалось или падало с
  `IllegalArgumentException`), `StealthCameraService` использовал только deprecated
  `getDefault()`, номера не нормализовались. Появился общий `util/SmsSender` (E.164 через
  libphonenumber, `divideMessage`/`sendMultipartTextMessage`, отсутствие телефонии, логирование
  вместо исключений в фоне), все пять вызывающих переведены на него.
- **PDF-отчёт был целиком на английском** с захардкоженными подписями и «сырыми» миллисекундами
  эпохи в поле `Timestamp`; длинные строки уезжали за край листа. Подписи вынесены в ресурсы
  (`pdf_*`), дата форматируется, текст переносится по ширине страницы (`Paint.breakText`).
- **Ключ SQLCipher при восстановлении бэкапа.** Если AndroidKeyStore больше не содержит ключ
  (восстановление на другом устройстве, сброс keystore), `getDatabasePassphrase()` бросал
  `DatabaseKeyException` при каждом старте — приложение не запускалось вообще и не могло даже
  показать причину. Теперь `resetWithNewPassphrase()` удаляет нерасшифровываемую базу и
  создаёт новую фразу; `getDatabasePassphrase()` синхронизирован, поэтому два потока при
  первом запуске не создают разные фразы (одна из них раньше перетирала другую, и база
  становилась нечитаемой).
- **`LocationUtils.await()` висел без таймаута.** `Task.await()` завершается только когда
  FusedLocationProvider пришлёт фикс; без сети реакция на подмену SIM зависала до записи
  события и показа подтверждения. Заменено на `awaitLastLocationOrNull()` с таймаутом 3 с.
- **Журнал брандмауэра показывал `OUTBOUND • WIFI`** (имена enum'ов) на любом языке, а
  заголовок «Recent Blocks» был захардкожен по-английски. Добавлены `FirewallLabels`, ресурсы
  `firewall_recent_blocks`, `traffic_direction_unknown`, ключ у списка логов.
- **Экспорт журналов был реализован дважды** (`SettingsRepository` и `LogExporter`), причём в
  одной копии не было `trafficDirection`, а `File(context.getExternalFilesDir(null), …)` при
  недоступном хранилище давал относительный путь и молча не писал файл. Осталась одна
  реализация в `LogExporter` (проверка `mkdirs()`, `Locale.US` для имени файла), плюс удалён
  неиспользуемый `Flow<File?>`, который при каждом сборе запускал экспорт заново.
- **`CircularProgressIndicator` на дашборде** получал `score / 100f` без ограничения —
  оценка вне 0..100 из старой записи истории рисовала дугу «на 130%».
- **`NetworkAvailabilityTracker`** менял `LinkedHashSet` из потока колбэков `ConnectivityManager`
  без синхронизации, а читался из UI и воркеров (риск устаревшего значения). Методы и геттер
  синхронизированы; карта антидребезга honeypot ограничена 200 записями.
- **Удалён мёртвый код**: неиспользуемый `ApkSignatureScanner.groupByCertificate()` (открывал
  APK всех установленных приложений) и дублирующий `exportLogsInternal()`.
- **`ThreatIntelClient`**: фид OpenPhish читается построчно с предохранителями (50 000 строк /
  8 МБ) вместо `body.string()` целиком, отказы API abuse.ch (`401/403 → auth_required`,
  `429 → rate_limited`) попадают в `details` вместо «HTTP 401».
- **Детектор подмены SIM молчал на новых установках.** Базовый отпечаток SIM записывался
  только при уже случившейся подмене (или при миграции старого формата в `SharedPreferences`),
  поэтому сравнение шло с пустым значением и событие не создавалось никогда. Теперь первый
  прочитанный идентификатор сохраняется как базовая линия, а недоступный идентификатор
  (Android 10+ отдаёт `null` для IMSI/ICCID) **не** затирает сохранённый отпечаток. Решение
  вынесено в чистую `SimIdentityLogic` и покрыто `SimIdentityLogicTest` (6 тестов).
- **`SimSwapReceiver` не реагировал на `BOOT_COMPLETED`** (action был объявлен в манифесте, но
  игнорировался) и завершал `goAsync()` раньше, чем детектор успевал что-либо сохранить:
  `onSimStateChanged()` лишь запускал корутину. Добавлен `suspend SimSwapDetector.checkSimState()`,
  который приёмник дожидается перед `PendingResult.finish()`. Компонент переведён в
  `exported="false"` — системные broadcast'ы доставляются и без экспорта, а подделать событие
  подмены SIM сторонним приложением нельзя.
- **Приманки honeypot выдавали себя и росли без ограничений.** В файлах лежал текст
  «PhoneGuard honeypot <id>», а каждый инцидент добавлял новый набор без удаления старых.
  Теперь содержимое похоже на реальную находку (строки `password:`/`pin:`, BIP-39 seed, список
  фото), держится не больше 3 наборов (старые удаляются после создания нового), а
  `isDecoyAccess()` сравнивает **имя файла** с шаблоном `<ts>_{passwords.txt,wallet_backup.json,private_photos}`
  вместо поиска подстроки — иначе `mypasswords.txt.bak` считался обращением к приманке.
  Старые приманки удаляются и по 90-дневному сроку хранения улик
  (`DataRetentionManager.deleteStaleHoneypotDecoys`). `HoneypotManagerTest` переписан (4 теста).
- **APK-сканер объявлял debug-ключом почти любое приложение.** Признаком служила подстрока
  `O=Android, C=US`, которая встречается и в release-сертификатах. Остались только маркеры
  `CN=Android Debug` / `CN=Debug` / `CN=Application Debug`; отказ `PackageManager`
  (`SecurityException` и др.) больше не роняет проверку подписи, а пропускает приложение.
- **Проверка security-патча** разбирала дату «мягким» `SimpleDateFormat`: `2024-13-45` и
  `2024-01-01-хвост` считались валидными, а дата «из будущего» (сбитые часы, баг OEM) давала
  отрицательное число месяцев и выглядела как «патч свежий». Теперь `isLenient = false` +
  проверка полного совпадения, месяцы ограничены снизу нулём, о будущей дате отдельная пометка.
  Логика вынесена в чистую `evaluate(patchDateStr, now)` и покрыта `SecurityPatchCheckTest` (6 тестов).
- **«Скрытые приложения» больше не пугают ложными находками**: раньше в отчёт попадало любое
  несистемное приложение без иконки в лаунчере — наборы иконок, виджеты, плагины камеры,
  Pro-разблокировщики. Теперь приложение попадает в отчёт, только если у него есть activity
  **и** оно просит опасное разрешение (SMS, звонки, контакты, микрофон, геолокация, overlay,
  accessibility/notification-listener/device-admin) или носит подозрительное имя пакета;
  в причине перечислены конкретные разрешения.
- **Фаервол пропускал IPv6.** В туннель добавлялся только адрес/маршрут IPv4, поэтому
  заблокированное приложение продолжало ходить в сеть по IPv6 (на мобильных операторах с
  IPv6-only + 464XLAT это дыра в блокировке). Добавлены `addAddress(fd00:…/128)` и `addRoute(::/0)`;
  IPv6-пакеты приходят в туннель и отбрасываются, не попадая в счётчик «битых».
  `IPv4PacketParserTest` покрывает пустые/усечённые и IPv6-пакеты.
- **Детектор «подглядывающего» спамил уведомлениями**: предупреждение приходило на каждый кадр с
  лицом (раз в ~2 с). Теперь не чаще раза в 30 секунд; клик по уведомлению поднимает приложение
  (`SINGLE_TOP`) вместо `CLEAR_TASK`, который перезапускал его с потерей состояния. ML Kit
  переведён с `CONTOUR_MODE_ALL` на `CONTOUR_MODE_NONE` — контуры не нужны для проверки взгляда,
  а стоят заметного CPU и батареи. Убраны лишний `Preview` и неиспользуемая инъекция.
- **`StealthCameraService` не компилировался и не мог сделать фото.** В
  `CaptureRequest.Builder.addTarget(...)` передавался `SurfaceTexture` (метод ждёт `Surface`);
  `ImageReader` создавался после запроса и слушал события без `Looper`-`Handler`; камера не
  закрывалась по таймауту. Переписано на CameraX `ImageCapture` (уже подключён): перебор
  задней/фронтальной камеры, таймаут 20 с, `unbindAll()` после съёмки, SMS «фото сделано»
  отправляется только при реально сохранённом файле (раньше — и при ошибке тоже).
- **Улики вторжений хранились вечно.** `DataRetentionManager` чистил только журналы;
  `intrusion_events` и фото из `phoneguard_stealth` не удалялись никогда, хотя обещано
  хранение 90 дней. Теперь удаляются старые события вместе с их файлами (только внутри
  приватных каталогов приложения — путь из БД не удаляется вслепую) и «осиротевшие» фото.
  Добавлен `IntrusionEventDao.getOlderThan()` и `DataRetentionManagerTest` (3 теста).
- **Тесты миграций Room не запускались**: `MigrationTestHelper` читает схемы из assets, но
  `androidTest.assets.srcDirs += files("$projectDir/schemas")` не был настроен — тест падал с
  «Cannot find the schema file». Добавлено, `app/schemas/README.md` описывает процесс.
- **Тесты не компилировались**: `ShoulderSurferUseCaseTest` ставил `coEvery { repository.startService() }
  returns Unit`, хотя функция теперь возвращает `Boolean`; сами тесты ничего не проверяли
  (`coEvery` внутри проверки вместо `verify`). Переписаны на `every { … } returns true/false`,
  `verify(exactly = 1)` и проверку `isRunning()`.
- **Мёртвые элементы управления**: переключатель Device Admin в анти-краже не реагировал на нажатие
  (`onCheckedChange` был пустым) — теперь тумблер и кнопка делают одно и то же; кнопки «обновить»
  в блокировщике звонков и сейфе ничего не делали (списки реактивны) — убраны.
- **Иконки приложений в Privacy Scanner**: вместо настоящих иконок рисовалась одна «шестерёнка»,
  теперь иконка берётся из `PackageManager` (`toBitmap` → `asImageBitmap`, кэш через `remember`).
- **28 отсутствующих импортов иконок** (`Icons.Default.Image`, `PlayArrow`, `Description`,
  `InsertDriveFile`, `Visibility`, `Lock`, `Block`, `Search`, `VisibilityOff`, `ArrowBack`,
  `FileDownload`, `Error`, `Warning`, `CheckCircle`, `Info`, `Home`, `Security`, `Settings`,
  `PhonelinkSetup`): `Icons.Default.X` — это extension-свойство, без
  `import androidx.compose.material.icons.filled.X` файлы не компилировались.
  Теперь это ловит `check_icon_imports()`.
- **Краши «на пустом месте»**: `startActivity` для системных экранов (`ACTION_NOTIFICATION_LISTENER_SETTINGS`,
  `ACTION_MANAGE_OVERLAY_PERMISSION`, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, детали приложения)
  больше не бросает `ActivityNotFoundException` на прошивках без этих разделов — все вызовы идут через
  `util/IntentUtils.open()` с подсказкой пользователю.
- Детектор «подглядывающего»: если Android 12+ запретил запуск foreground-сервиса из фона, тумблер больше
  не остаётся включённым «вхолостую» — показывается `shoulder_surfer_start_failed` и настройка
  сбрасывается (`ShoulderSurferService.start()`/`stop()` теперь возвращают `Boolean` и не падают).
- Уведомление VPN: `PendingIntent.getActivity(null)` при отсутствии launcher-activity пакета — уведомление
  теперь создаётся без клика вместо NPE.
- Удалён мёртвый `IntrusionResponseService` (foregroundServiceType `dataSync`): его никто не запускал,
  ответ на интрузию уже выполняет `IntrusionResponseExecutor` — заодно снята лишняя декларация FGS для Play.
- VPN-фаервол: туннель забирал весь трафик и «писал пакеты обратно» — сеть отключалась, блокировки не было.
  Теперь в туннель попадают только приложения с активной блокировкой, пакеты отбрасываются, туннель
  пересоздаётся при смене Wi-Fi/мобильной сети; добавлен `foregroundServiceType="specialUse"`.
- Дашборд: подоценки `callBlocker`/`privacy`/`spyware` всегда были 0 (итог не превышал 35).
- `FullScanViewModel`: первый тап мог ничего не делать, сравнение сканов всегда давало 0, `trace?.` на non-null.
- `SmsCommandReceiver`: Flow читался как значение, вайп без подтверждения, IMEI без разрешения,
  многочастные SMS выполнялись дважды, отсутствовал `goAsync()`.
- `LocationProvider`: `CountDownLatch` без таймаута — вызов мог висеть вечно.
- `EncryptionManager`: кириллические имена файлов превращались в `____`, одинаковые имена перезаписывали
  друг друга, NPE при отзыве доступа, лимит размера не проверялся.
- `DatabaseKeyManager`: `apply()` мог потерять пароль БД, отсутствие ключа давало невнятный каст-эксепшен.
- `PdfExportHelper`: после разрыва страницы текст рисовался на canvas предыдущей страницы.
- `PermissionAbuseCheck`: проверялись запрошенные, а не выданные разрешения.
- `SystemIntegrityCheck`: рефлексия `SystemProperties` на Android 9+ не работает, `/system/bin/debuggerd`
  есть на всех устройствах (ложные срабатывания).
- `RootDetectionCheck`: лишний `File`, `Runtime.exec` без `waitFor`.
- `SpywareCheckRepository`: подпись APK не анализировалась; UID-кэш фаервола терял пакеты с общим UID.
- Onboarding/consent: побочный эффект вызывался в теле композиции.
- Vault: `InputStream.available()` вместо реального размера, отсутствие `USE_BIOMETRIC` в манифесте.
- Язык интерфейса: селектор не применялся и сбрасывался при перезапуске; теперь язык хранится в
  SharedPreferences-зеркале и применяется в `MainActivity.attachBaseContext()`, выбор пересоздаёт Activity.
- Экран согласий: галочки были декоративными — системный запрос разрешений не вызывался
  (SMS, геолокация, уведомления, камера не работали). Теперь галочка запускает
  `RequestPermission`, состояние берётся из реального гранта, при отказе доступна ссылка в настройки.
- Уведомления и SMS (`ShoulderSurferService`, `IntrusionResponseService`, `IntrusionResponse`,
  `SimSwapDetector`) берут текст из ресурсов, а не из русских литералов.
- Ошибки хранилища и экспорта логов: вместо русских строк — типизированные коды, текст в UI;
  Toast об экспорте больше не повторяется на каждой рекомпозиции.
- «Удалённое стирание/блокировка» больше не ссылаются на удалённый FCM — подсказывают SMS-команды.
- `CallBlockerScreen`: ошибка «некорректный номер» теперь видна (snackbar), раньше молча игнорировалась.
- Периодическое сканирование ставилось в очередь при каждом старте приложения даже при выключенной
  настройке — теперь работа создаётся/отменяется по `isScheduledScanEnabled`, а код расписания
  живёт в одном месте (`ScheduledFullScanWorker.schedule/cancel`), а не в трёх копиях.
- Текст этапов полного сканирования был зашит по-русски в оркестраторе — теперь `ScanStage`
  с `@StringRes` и подстановкой на экране (ru/en/de).
- Чек телефона перечисляет установленные приложения один раз вместо трёх (`getInstalledApplications`
  вызывался трижды за проверку).
- Полнота локалей en/ru/de (все ключи синхронизированы), темы `values-night`, `QUERY_ALL_PACKAGES`,
  keep-правила R8 для apk-parser/libphonenumber/OkHttp.

### Removed
- `util/SslPinningUtil.kt` — неиспользуемый «задел» под пиннинг: ни одного вызова, ни одного пина;
  оставлять файл с таким именем значит создавать иллюзию защиты. Threat-intel ходит по HTTPS через
  OkHttp с системным хранилищем сертификатов.
- `model/SmishingSms.kt` — неиспользуемые entities `smishing_sms`/`known_phishing_domains` и enum
  `SmishingStrictness`: они не входили в `@Database`, на них никто не ссылался, а `List<String>`
  в них не имел `TypeConverter` — при регистрации таблицы сборка бы упала.
- `intrusion/IntrusionResponseService.kt` — мёртвый foreground-сервис (см. Fixed).

### Changed
- **Удалён Firebase** (Analytics, Crashlytics, Performance, BOM, плагин, `google-services.json`):
  проект заявляет «Zero telemetry», а `logCallBlocked` отправлял номера телефонов.
- Удалены неиспользуемые `SimChangeReceiver`, `model/SwapEvent.kt`, `ui/navigation/DrawerContent.kt`.
- README/PRIVACY_POLICY/RELEASE приведены в соответствие с фактическими интеграциями.

## [1.0.0] - 2026-07-19

### Added
- Initial production-ready release.
- Anti-theft: PIN, SIM lock, remote alarm, shoulder surfer, photo on failed attempts.
- Call & SMS blocker: blacklist/whitelist, spam database, CSV import, call blocking notifications.
- Firewall: VPN-based traffic monitoring, app-level rules, block statistics, traffic direction logging.
- Full scan: 13 security checks, scan history, PDF report export, scan comparison.
- Secure vault: encrypted storage for photos, videos, documents, biometric unlock, FLAG_SECURE.
- Privacy scanner & spyware check modules.
- Bottom navigation, onboarding, consent screens.
- SQLCipher database encryption, SSL pinning utilities.
- Firebase Performance Monitoring, Crashlytics, Analytics.
- LeakCanary for memory leak detection (debug).
- Data cleanup worker, scheduled scans, battery optimization prompts.

### Fixed
- Removed GlobalScope and runBlocking from main code.
- Fixed MockK syntax in ViewModel tests (every for Flow, coEvery for suspend).
- Fixed ShoulderSurferService SecurityException handling.
- Fixed CallScreeningService coroutine scope cancellation.
- Fixed OnboardingViewModel to use viewModelScope.

### Changed
- Migrated to Hilt DI across all ViewModels and services.
- Replaced drawer navigation with bottom navigation bar.
- Unified loading/error/success state handling in UI.
