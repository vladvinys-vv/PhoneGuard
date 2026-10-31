# Release Checklist

## Before Building

**Static checks first:** `python3 tools/static_audit.py` must report `0 error(s), 0 warning(s)`.
   It validates: unresolved/missing imports in Compose code, `R.*` references, XML validity,
   Room `@Query` tables/columns, Room-DAO call sites, Hilt bindings, manifest components and
   `android:exported`, requested vs declared permissions, locale parity of `strings.xml`,
   `getString` argument counts vs format placeholders, `PendingIntent` mutability flags,
   guarded `startActivity`/`startService`, dead manifest components, Room migrations vs `@Entity`
   schemas, navigation routes referenced by `navigate()`, `Icons.Default.*` imports, and
   block comments terminated too early by an embedded `/*` or `*/`, optional hardware features
   for declared permissions, backup/D2D exclusion rules, mockk stub return types, and
   constructor arity across `src/test` and `src/androidTest`, and Gradle wiring
   (`rootProject.ext`, Room schema export, androidTest schema assets).

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
   - `./gradlew assembleProductionRelease` — production APK/AAB
   - `./gradlew assembleStagingRelease` — staging build for testing
   - `./gradlew bundleProductionRelease` — Play Store App Bundle

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

## Post-Release
- Ask users to export local crash reports (`adb pull /data/data/com.phoneguard/files/crash-reports`) if they hit issues
- Verify release build has no network calls except the opt-in threat intel endpoints
- Watch ANR/crash rate in the Play Console vitals (no Firebase Performance anymore);
  local timings are available via `util/PerformanceMonitor.kt` in debug builds
- Respond to reviews within 24h
- Plan hotfix process
