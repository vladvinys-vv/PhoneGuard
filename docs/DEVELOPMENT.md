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
./gradlew :app:assembleDebug              # debug APK
./gradlew :app:testDebugUnitTest          # юнит-тесты на JVM
./gradlew :app:connectedDebugAndroidTest  # инструментальные тесты (устройство/эмулятор)
./gradlew :app:lintDebug                  # Android Lint (abortOnError = true)
./build_release.sh production release     # подписанные APK + AAB
```

Перед любым PR/релизом — всё одной командой:

```bash
./tools/check_all.sh
```

Она запускает `static_audit.py`, проверяет идемпотентность `sync_strings.py` и, если установлены,
`kotlin_frontend_check.py` (kotlinc) и `kotlin_analysis.py` (tree-sitter). Обязательные проверки
(`static_audit.py` и строки) прогоняются в CI-job `static-checks`, компиляторная проверка — в
`kotlin-frontend`; сборка и тесты — в `lint`, `unit-test`, `build-debug`, `build-release`.

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
Первой настоящей проверкой после правок остаётся `./gradlew :app:assembleDebug testDebugUnitTest`,
затем `connectedDebugAndroidTest` на устройстве.
