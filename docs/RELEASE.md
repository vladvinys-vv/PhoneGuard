# Release Checklist

## Before Building
**Static checks first:** `./tools/check_all.sh` (or at least `python3 tools/static_audit.py`, which
must report `0 error(s), 0 warning(s)`). If `aapt2` is installed, `tools/aapt2_check.py` must also
pass — otherwise the Gradle build will fail later at `processDebugResources` (unescaped apostrophes,
broken resource XML, manifest attributes, missing `R.*`).
It validates unresolved/missing imports in Compose code, `R.*` references, XML validity, Room `@Query`
tables/columns, Room-DAO call sites, Hilt bindings, manifest components and `android:exported`,
requested vs declared permissions, locale parity of `strings.xml`, `getString` argument counts vs
format placeholders, `PendingIntent` mutability flags, guarded `startActivity`/`startService`, dead
manifest components, Room migrations vs `@Entity` schemas, navigation routes referenced by `navigate()`,
`Icons.Default.*` imports, block comments terminated too early by an embedded `/*` or `*/`, optional
hardware features for declared permissions, backup/D2D exclusion rules, mockk stub return types,
constructor arity, literal types passed to constructor parameters, missing required named
arguments across `src/test` and `src/androidTest`, **unique notification ids** between
components, **deep links** (shortcut → graph → manifest), and Gradle wiring (`rootProject.ext`, Room schema export, androidTest schema assets).

Optionally also `python3 tools/kotlin_frontend_check.py` (needs `kotlinc` 2.1+ and JDK 17): it runs
the real Kotlin compiler over every source file, including tests, and reports only diagnostics that
are not caused by the missing Android SDK. This catches errors a regex audit cannot see (`@Volatile`
on `val`, private member called from UI, `return` inside a `withContext` lambda, constructor
mismatches in tests).

Optionally also `python3 tools/kotlin_analysis.py` (needs `tree-sitter` + `tree-sitter-kotlin`): it
parses every `.kt` file and reports syntax errors and suspend calls outside a suspend context. Without
the parser installed the script prints a hint and exits 0.

`python3 tools/sync_strings.py --check` must exit 0 (636 keys in each of `values`, `values-en`,
`values-ru`, `values-de`); it reports exactly which keys are missing instead of writing files.

1. **No Firebase / no telemetry**
   - The app intentionally has **no** Firebase (Analytics, Crashlytics, Performance) and no `google-services.json`.
   - Diagnostics stay on-device: `util/CrashHandler.kt` (crash reports in `files/crash-reports/`),
     `util/PerformanceMonitor.kt` (timing), `util/AnalyticsHelper.kt` (session counters). Keep it that way —
     phone numbers must never be logged or uploaded.
   - Threat intelligence is opt-in and sends only the host name (see `threatintel/ThreatIntelClient.kt`).

2. **Signing Config**
   - Generate release keystore: `keytool -genkey -v -keystore phoneguard.jks -keyalg RSA -keysize 2048 -validity 10000`
   - Store in secure location, add path/passwords to `keystore.properties` or env variables
   - Never commit `keystore.properties` or `.jks` to version control

3. **Build Flavors**
   - `./build_release.sh production release` — проверки, затем APK **и** AAB одной командой
     (`app/build/outputs/apk/production/release/*.apk`, `app/build/outputs/bundle/*/*.aab`);
     без `keystore.properties`/`KEYSTORE_PATH` скрипт предупреждает, что APK неподписанный
   - `./gradlew assembleProductionRelease` — только production APK
   - `./gradlew assembleStagingRelease` — staging build for testing
   - `./gradlew bundleProductionRelease` — только Play Store App Bundle

4. **Play Store Assets**
   - Screenshots: phone (320dp, 384dp, 480dp), tablet (600dp, 720dp), foldable
   - Feature graphic: 1024x500
   - App icon: 512x512
   - Promo video (optional)

5. **Manual Testing** (see `MANUAL_TESTING.md`)
   - Android 8, 10, 12, 13, 14, 15
   - Samsung, Xiaomi, Huawei, Pixel
   - Battery historian test
   - Permission flow test

6. **Phone check** (`deviceaudit/`) must be verified manually on each OS version:
   the audit reads settings without new permissions, so it must not crash on restricted profiles;
   check that screen lock, encryption, patch level and free space show plausible values.

7. **Compliance**
   - Privacy Policy URL (host `docs/PRIVACY_POLICY.md`)
   - Terms of Service URL
   - Support email configured
   - Data safety form filled in Play Console

## 16 КБ страницы памяти (`targetSdk 35`)

С 1 ноября 2025 Play не принимает обновления приложений с `targetSdk 35`, чьи native-библиотеки не
поддерживают страницы памяти 16 КБ. Старый `net.zetetic:android-database-sqlcipher` (последний
релиз 4.5.4, 2023) этому требованию не соответствовал, поэтому выполнена миграция на его преемника:

| | было | стало |
|---|---|---|
| зависимость | `net.zetetic:android-database-sqlcipher:4.5.4` | `net.zetetic:sqlcipher-android:4.19.1` (+ `androidx.sqlite:sqlite:2.7.0` как compile-зависимость библиотеки) |
| пакет | `net.sqlcipher.database.*` | `net.zetetic.database.sqlcipher.*` |
| загрузка ядра | `SQLiteDatabase.loadLibs(context)` | `System.loadLibrary("sqlcipher")` |
| фабрика для Room | `SupportFactory(passphrase)` | `SupportOpenHelperFactory(passphrase)` |

16 КБ-выравнивание обеспечено самой библиотекой: в её `Application.mk` стоит
`APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true`.

Проверка после сборки релиза:

```bash
unzip -l app/build/outputs/apk/production/release/*.apk | grep 'lib/.*sqlcipher'   # .so на месте
zipalign -c -P 16 -v 4 app/build/outputs/apk/production/release/*.apk             # 16 КБ-выравнивание
```

Если появится новое native-содержащее зависимое (или собственный NDK-код), проверять так же.

## Post-Release
- Ask users to export local crash reports (`adb pull /data/data/com.phoneguard/files/crash-reports`) if they hit issues
- Verify release build has no network calls except the opt-in threat intel endpoints
- Watch ANR/crash rate in the Play Console vitals (no Firebase Performance anymore);
  local timings are available via `util/PerformanceMonitor.kt` in debug builds
- Respond to reviews within 24h
- Plan hotfix process
