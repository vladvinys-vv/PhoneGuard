# Разработка

Практические правила для этого репозитория: окружение, команды, конвенции кода, инструменты проверки.
Если что-то здесь расходится с кодом — прав код, а документ нужно поправить.

## Окружение

| Что | Версия |
|---|---|
| JDK | 17 (`compileOptions`/`jvmTarget` = 17) |
| Android SDK | compileSdk 35, build-tools из AGP 8.5.2 |
| Gradle | wrapper из репозитория, Groovy DSL |
| Kotlin | 2.1.0 (+ `org.jetbrains.kotlin.plugin.compose`) |
| Python (только для `tools/`) | 3.9+ |

`minSdk 26`, `targetSdk 35`, `applicationId com.phoneguard`. Flavors: `staging` (`.staging`, подпись
`PhoneGuard Staging`) и `production`. Debug-сборка получает суффикс `.debug`.

## Команды

```bash
./gradlew :app:assembleProductionDebug    # debug APK (production flavor)
./gradlew :app:testProductionDebugUnitTest # юнит-тесты на JVM
./gradlew :app:connectedProductionDebugAndroidTest  # инструментальные тесты
./gradlew :app:lintProductionDebug        # Android Lint (abortOnError = true)
./build_release.sh production release     # подписанные APK + AAB
```

Перед любым PR/релизом — всё одной командой:

```bash
./tools/check_all.sh
```

Она запускает `gradle_check.py`, `static_audit.py`, проверяет `sync_strings.py --check` и, если
установлены, `kotlin_frontend_check.py` (kotlinc), `aapt2_check.py` (aapt2) и `kotlin_analysis.py`
(tree-sitter). Обязательные проверки (`gradle_check.py`, `static_audit.py` и строки) прогоняются в
CI-job `static-checks`, компиляторная проверка — в `kotlin-frontend`; сборка и тесты — в `lint`,
`unit-test`, `build-debug`, `build-release`.

Если job'ы падают за 1–2 секунды **без запущенных шагов**, это не YAML: GitHub в этом случае
пишет в аннотации check-run «The job was not started because your account is locked due to a
billing issue». Проверить: `gh api repos/<owner>/<repo>/check-runs/<id>/annotations`. Лечится
на стороне GitHub (Billing & plans), а не в репозитории.

## Конвенции

### Строки и локализация

- В UI и уведомлениях **не бывает** литералов: только `stringResource`/`getString(R.string.…)`.
- Новая строка добавляется в словарь `tools/sync_strings.py` (`default`/`en`/`ru`/`de`) и затем
  разливается скриптом. Сейчас 636 ключей в каждой локали, паритет проверяет `static_audit.py`,
  а `python3 tools/sync_strings.py --check` отвечает, каких ключей не хватает (ничего не записывая).
- Форматирование: только позиционные аргументы (`%1$s`, `%2$d`), иначе скрипт и проверка арности
  не смогут сопоставить строку с вызовом.
- Регистр для сравнений — `uppercase(Locale.US)` / `lowercase(Locale.ROOT)`: в турецкой локали
  `i`/`I` ведут себя иначе (`wipe` → `WİPE`), и это уже ломало SMS-команды и разбор алгоритма подписи.

### Проверки полного сканирования

- Класс проверки **не получает `Context`** и возвращает `CheckDetail(textRes, args)`
  (`fullscan/checks/CheckDetail.kt`); текст собирается в UI (`resolve(context)` / `describe(context)`).
- Если проверке нужен Android API, `Context` передаётся в конструктор (Hilt), но строк в ней всё равно нет.
- `suspend fun performCheck()` — только если проверка делает IO (пример: `ApkSignatureCheck`).

### Корутины

- `CancellationException` нельзя глотать: `catch (e: CancellationException) { throw e }` до общего `catch`.
- В `finally` не гасить чужое состояние: `finally { if (isActive) _isLoading.value = false }`, иначе
  отменённая загрузка выключает индикатор новой.
- В `Worker` отмена — это `Result` от WorkManager, а не `Result.retry()`.

### Данные

- Room: `AppDatabase` версии 8, миграции `MIGRATION_1_2 … MIGRATION_7_8` в `ALL_MIGRATIONS`,
  `LATEST_VERSION` совпадает с `version`. Схемы экспортируются в `app/schemas/` при сборке — в
  репозиторий они не коммитятся, поэтому `MigrationTestHelper` не используется; цепочку миграций
  проверяет JVM-тест `AppDatabaseMigrationConfigTest`.
- Номера телефонов нормализуются `util/PhoneNumberNormalizer` (libphonenumber, офлайн). Сравнение
  списков идёт по канонической форме, а не по строке из UI.
- Аналитика (`util/AnalyticsHelper`) — счётчики в памяти процесса. В неё нельзя передать номер
  телефона, текст сообщения или что-либо ещё персональное: у `logCallBlocked` есть только флаг `isSms`.

### Уведомления

Числовые id уникальны, проверяются `tools/static_audit.py`:

| id | Кто | Зачем |
|---|---|---|
| 1001 | `PhoneGuardVpnService` | постоянное уведомление брандмауэра |
| 1002 / 1003 | `ShoulderSurferService` | работа детектора / предупреждение |
| 1101 / 1102 | `BlockerNotifications` | заблокированный звонок / SMS |
| 4101 | `SimSwapNotifications` | «Вы поменяли SIM?» → экран подтверждения |
| 8101 | `SmsCommandReceiver` | подтверждение анти-воровской команды |
| 9001 | `StealthCameraService` | скрытая съёмка |

## Инструменты

### `tools/gradle_check.py`

Проверяет **конфигурацию** Gradle, потому что её ошибки валят любую задачу (`lintDebug`,
`testDebugUnitTest`, `assembleRelease`) ещё до компиляции — с многоэкранным стектрейсом Groovy
вместо понятного сообщения. Главный кейс: функции Kotlin stdlib в Groovy-скриптах. Так реально
падало `./gradlew lintProductionDebug` в Codemagic:

```groovy
arguments += mapOf("room.schemaLocation" to "…")   // Kotlin DSL; в Groovy метода mapOf() нет
arguments = ["room.schemaLocation": projectDir.absolutePath + "/schemas"]   // правильно
```

Что проверяется: `mapOf`/`listOf`/`val`/`.let { }` и прочие Kotlin-конструкции в `.gradle`;
`rootProject.ext.X`, которого нет в `ext`; `id 'plugin'` без classpath в `buildscript`;
расхождение версий `kotlin-gradle-plugin` и `compose-compiler-gradle-plugin`; AGP против версии
Gradle wrapper'а и `compileSdk` (с учётом `android.suppressUnsupportedCompileSdk`);
`minSdk ≤ targetSdk ≤ compileSdk`; согласованность `sourceCompatibility` и `jvmTarget`;
отсутствующий файл из `proguardFiles`; дубликаты зависимостей с разными версиями; отсутствие
`android.useAndroidX=true`; `kapt`/Hilt без соответствующих плагинов.

Отдельная проверка — **имена задач Gradle**. У проекта два flavor'а, поэтому задач без flavor'а
нет: `lintDebug`/`testDebugUnitTest`/`assembleDebug` не существуют, а Gradle отвечает
`Task '…' is ambiguous` и перечисляет кандидатов (`lintProductionDebug`, `lintStagingDebug`, …).
`gradle_check.py` сканирует CI-конфиги, скрипты и документацию: если в строке с `./gradlew`
встречается задача вида `lintDebug`, `testDebugUnitTest`, `assembleDebug`, `connectedDebugAndroidTest`,
это ошибка с подсказкой, какое имя нужно.

Проверяется и сам `app/proguard-rules.pro`: неизвестная директива R8 (опечатка вида `-dontwarns`)
не видна до релизной сборки, а падает уже на `assembleProductionRelease`.

Ещё одна проверка — **версия зависимости против `compileSdk`**. AAR-metadata каждой библиотеки
записывает минимальный `compileSdk`, который она требует, и несовпадение валит сборку на
`CheckAarMetadataTask` ещё до компиляции. Так падало в Codemagic:
`Dependency 'net.zetetic:sqlcipher-android:4.19.1' requires ... compile against version 36 or later`,
а проект на `compileSdk 35`. Таблица `AAR_MIN_COMPILE_SDK` в `gradle_check.py` знает такие пары
(сейчас — `sqlcipher-android ≥ 4.19 → compileSdk 36`) и выдаёт ошибку заранее. Само правило
проверяется самотестом на синтетических скриптах:

```bash
python3 tools/gradle_check.py --self-test
```

Отдельно проверяются пары «рантайм ↔ обработчик» — их версии обязаны совпадать, иначе kapt падает
на несовместимом API: `hilt-android` ↔ `hilt-android-compiler`, `room-runtime` ↔ `room-compiler`,
`hilt-work` ↔ `hilt-compiler`. Предупреждения (не ошибки) выдаются про предрелизные версии
(`security-crypto`, `biometric`), про явные версии compose-артефактов при подключённом
`compose-bom` и про закрытый `net.zetetic:android-database-sqlcipher` — он не поддерживает 16 КБ
страницы памяти, что блокирует публикацию с `targetSdk 35` (план миграции — `docs/RELEASE.md`).

```bash
python3 tools/gradle_check.py
python3 tools/gradle_check.py --warnings-as-errors
```

### `tools/jvm_unit_test.py`

Компилирует и **выполняет** unit-тесты чистой логики без Gradle, Maven и JUnit-jar: `kotlinc`
плюс минимальный шим `org.junit` (аннотации `@Test`/`@Before`/`@After`/`@Ignore`, `Assert`,
раннер через reflection). Нужен там, где нет доступа к Google Maven — например, в песочнице, где
`./gradlew testProductionDebugUnitTest` не запускается, а `kotlin_frontend_check.py` только
компилирует.

```bash
python3 tools/jvm_unit_test.py            # прогнать всё, что получится
python3 tools/jvm_unit_test.py --verbose  # показать, что пропущено и почему
python3 tools/jvm_unit_test.py --self-test # проверить сам раннер на синтетических тестах
```

`--self-test` компилирует два синтетических теста (успешный и заведомо падающий) и проверяет,
что раннер считает успехи и **ловит** падения — иначе зелёный прогон ничего не доказывал бы.

Как выбираются тесты: берётся тест-файл без Android/Robolectric/MockK/OkHttp/Gson/корутин,
к нему рекурсивно подтягиваются исходники по импортам и одноимённым классам, а вместо аннотаций
Room, `androidx.annotation`, `javax.inject`, `android.util.Log`, `android.os.Build` (SDK_INT = 35),
`android.text.TextUtils`, `android.util.Base64` и минимального `android.content.Context`
подставляются заглушки, а `R.<тип>.<имя>` генерируется по фактическим ссылкам в коде — поэтому под прогон попадают
модели, DAO-независимые репозитории и конвертеры. Сейчас так выполняется 165 тестов в 15 файлах.

Что остаётся Gradle: тесты на Android/Robolectric/MockK и всё, что требует настоящих AndroidX-
артефактов; для них скрипт печатает `skip … — импорт …`.

### `tools/test_coverage_map.py`

Карта упоминаний классов приложения в тестах — **не** процент покрытия и не замена JaCoCo.
Отвечает на вопрос «есть ли вообще тест, который трогает этот класс», и показывает пакеты,
где тестов нет совсем.

```bash
python3 tools/test_coverage_map.py            # сводка по пакетам + логика без тестов
python3 tools/test_coverage_map.py --detail   # полный список
```

Сейчас: 166 классов, 101 упомянут в тестах (60%). Отсутствие упоминания не всегда пробел —
UI, Hilt-модули и сервисы проверяются Robolectric-тестами и вручную (`docs/MANUAL_TESTING.md`),
поэтому отчёт разделяет «логику» и «Android-связанное».

### `tools/static_audit.py`

Проверки без Gradle (регулярные выражения + разбор XML). Ловит то, что иначе падает на сборке или
в рантайме: неразрешённые импорты и `R.*`, дубликаты объявлений, баланс скобок, Room `@Query`
(таблицы и колонки), вызовы DAO и методов классов, Hilt-привязки, компоненты манифеста,
`android:exported`, разрешения, паритет локалей, арность `getString`, `PendingIntent`-флаги,
незащищённые `startActivity`/`startService`, мёртвые компоненты (включая `<activity>`, кроме
ярлыков `LAUNCHER` и `BROWSABLE`-ссылок — так нашлась недоступная `SimSwapConfirmationActivity`),
недостижимые экраны, YAML workflows и конфиги (`.github/workflows`, `codemagic.yaml`, `.jules`),
миграции против `@Entity`,
маршруты навигации, `Icons.Default.*`, `uses-feature`, backup-правила, mockk-стабы, арность
конструкторов в тестах, типы литералов на позициях параметров, пропущенные обязательные
именованные аргументы, Gradle-обвязку, **уникальность id уведомлений** и **целостность deep links**.

### `tools/sync_strings.py`

Идемпотентно раскладывает словарь строк в `values`, `values-en`, `values-ru`, `values-de`.
Запускать после любого изменения текстов; повторный запуск ничего не меняет.

### `tools/kotlin_frontend_check.py` (опционально, самый строгий)

Запускает **настоящий компилятор Kotlin** на всех 209 файлах. Без Android SDK он не может
разрешить `android.*`, `androidx.*`, `kotlinx.coroutines`, Hilt и mockk, но полностью проверяет
синтаксис, объявления, вывод типов там, где типы известны, и правила языка. Ошибки, заведомо вызванные
отсутствующими библиотеками, отфильтрованы по внутренним именам диагностик
(`-Xrender-internal-diagnostic-names`); всё остальное — реальные проблемы.

```bash
export JAVA_HOME=/path/to/jdk-17
python3 tools/kotlin_frontend_check.py          # main + test + androidTest
python3 tools/kotlin_frontend_check.py --scope main

# строже: с настоящим android.jar проверяются сигнатуры android.* API
./tools/fetch_android_jar.sh                     # печатает путь к android.jar
python3 tools/kotlin_frontend_check.py --android-jar "$(./tools/fetch_android_jar.sh)"
```

С `android.jar` на classpath (флаг `--android-jar`, переменные `ANDROID_JAR` или
`ANDROID_HOME`/`ANDROID_SDK_ROOT` — скрипт находит `platforms/android-*/android.jar` сам)
количество «шумных» диагностик падает примерно с 10 500 до 8 100, а часть шума становится
ошибками: если в сообщении фигурирует уже разрешённый тип `android.*`, это настоящая проблема.
Так найдены `Geocoder.getFromLocation()` без проверки на `null` и два обращения к
`PackageInfo.applicationInfo` без проверки на `null`.

`tools/fetch_android_jar.sh` достаёт `android.jar` там, где `dl.google.com` недоступен
(например, в изолированных песочницах): сначала смотрит кеш `~/.cache/phoneguard/android-jar`,
затем установленный SDK, а в крайнем случае делает sparse checkout нужной платформы из
GitHub-зеркала `Sable/android-platforms` (~26 МБ, несколько секунд).

Требуется `kotlinc` 2.1+ на `PATH` (или в `KOTLINC`). Если компилятора нет, скрипт завершается с
кодом 0 и подсказкой. В песочнице без доступа к Maven Central компилятор берётся из npm:

```bash
npm pack kotlin-compiler@2.1.0 && tar xzf kotlin-compiler-2.1.0.tgz
export PATH="$PWD/package/bin:$PATH"
```

Именно этой проверкой найдены и исправлены реальные ошибки сборки: `@Volatile` на `val`,
приватный `loadApps()` вызывался из UI, `return` внутри lambda `withContext`, имя enum-класса
присвоено в `val`, точка в backtick-имени теста, позиционные аргументы `BlockedNumber(...)` и
пропущенные обязательные `latitude/longitude/addressString` в `SimSwapEvent(...)`.

### `tools/aapt2_check.py` (опционально, ловит падения `processDebugResources`)

Запускает настоящий `aapt2` так, как это делает Gradle: `compile` всех ресурсов и `link`
манифеста против `android.jar` с генерацией `R.java`. Затем сверяет все ссылки `R.type.name`
в Kotlin с тем, что реально попало в `R.java`.

```bash
# aapt2 ≥ 2.20: в CI приходит из build-tools Android SDK, локально проще всего из npm
npm pack aaptjs3 && tar xzf aaptjs3-*.tgz
export AAPT2="$PWD/package/bin/x64/linux/aapt2"

python3 tools/aapt2_check.py          # compile + link + сверка R.*
python3 tools/aapt2_check.py --compile-only
```

Именно этой проверкой найдены строки, на которых **падала бы любая сборка**:
`values-en`: `'which su' succeeded`, `Build tags contain '%1$s'` и `values-de`:
`'which su' erfolgreich` — aapt2 отвечает `unescaped apostrophe in string`. Апостроф в
`<string>` обязан быть экранирован (`\'`); `tools/sync_strings.py` теперь делает это сам,
а `static_audit.py` ловит регресс без установленного aapt2.

PyPI-пакет `aapt2` даёт версию 2.19 — `compile` с ним работает, но `link` не читает
`resources.arsc` современного `android.jar`, поэтому нужен 2.20+ (npm `aaptjs3` или build-tools).

**`android.jar` выбирается по `compileSdk`, а не по имени папки.** Платформы сортируются
численно: при лексикографической сортировке `platforms/android-9` оказывается «новее»
`android-35`, и манифест проверяется против древнего API — aapt2 выдаёт ложную ошибку вида
`'specialUse' is incompatible with attribute foregroundServiceType` (атрибут появился только
в API 34, а `compileSdk` = 35). Приоритет: точное совпадение с `compileSdk`, затем ближайший
больший, затем самый новый доступный `android.jar`. Если jar всё же старее `compileSdk`, `link`
пропускается с пояснением (ресурсы при этом проверяются), а `--require-compile-sdk` превращает
это в ошибку — флаг для CI, где нужная платформа ставится явно:

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0"
python3 tools/aapt2_check.py --require-compile-sdk
```

### `tools/kotlin_analysis.py` (опционально)

Разбор настоящим парсером (tree-sitter): синтаксис всех `.kt` и вызовы `suspend`-функций вне
suspend-контекста. Требует `pip install tree-sitter tree-sitter-kotlin`; без них проверка
пропускается с кодом 0.

```bash
python3 -m venv .venv-analysis && . .venv-analysis/bin/activate
pip install tree-sitter tree-sitter-kotlin
python3 tools/kotlin_analysis.py
```

Имена `suspend`-функций собираются по всему проекту, поэтому одноимённая не-suspend функция может
дать ложное срабатывание. Такие случаи вносятся в `tools/kotlin_analysis_allow.txt` с пояснением,
почему вызов безопасен.

### `tools/webhook_notify.py` / `tools/webhook_listen.py`

Уведомление о завершении сборки во внешний сервис: Codemagic (`publishing.scripts`),
GitHub Actions (job `notify`) и локально. Payload `build.finished` (статус, ветка, коммит,
build id/url, артефакты), подпись `X-PhoneGuard-Signature: sha256=…` (HMAC-SHA256 по
`WEBHOOK_SECRET`), 3 попытки с backoff. Без `WEBHOOK_URL` шаг ничего не делает, при ошибке
доставки сборка не падает (`--strict` — наоборот). `webhook_listen.py` — локальный приёмник
с проверкой подписи. Полная схема, настройка секретов и входящий trigger-webhook —
[`docs/CI_WEBHOOKS.md`](CI_WEBHOOKS.md).


## Тесты

- **JVM (`app/src/test`)**: JUnit4 + MockK + Turbine + Robolectric. Для Android-API берём
  `RuntimeEnvironment.getApplication()`, а не `ApplicationProvider` (последний требует
  `androidx.test:core`, которого нет в `testImplementation`).
- **Инструментальные (`app/src/androidTest`)**: Compose UI-тесты и тесты Room DAO. Тексты ищутся по
  `R.string`, а не по русским литералам — иначе тест падает на устройстве с другой локалью.
  Для дублирующихся подписей (`Язык`) используется `onAllNodesWithText(...).onFirst()`.
- Тесты, затрагивающие реальные `MediaPlayer`/`RingtoneManager`, помечаются `@RunWith(RobolectricTestRunner::class)`.

## Ограничения среды

Если Android SDK недоступен (например, в песочнице), `./gradlew` запустить нельзя, но проверки
остаются: `static_audit.py`, `sync_strings.py`, `kotlin_frontend_check.py` (нужен `kotlinc` + JDK 17;
проверяет всё, кроме разрешения внешних библиотек) и `kotlin_analysis.py` (нужен tree-sitter).
Первой настоящей проверкой после правок остаётся
`./gradlew :app:assembleProductionDebug testProductionDebugUnitTest`,
затем `connectedDebugAndroidTest` на устройстве.
