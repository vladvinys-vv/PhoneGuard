# Project rules: PhoneGuard

This file describes the repository for automated agents and new contributors.
Facts here must match the code; the authoritative docs are `README.md`,
`docs/DEVELOPMENT.md` and `docs/CHANGELOG.md`.

## Project
- Android native security app: full device scan, phone check, local VPN firewall,
  anti-theft SMS commands, call/SMS blocking, encrypted biometric vault.
- Stack: Kotlin 2.1.0, Jetpack Compose + Material 3, Hilt, Room 2.6.1 + SQLCipher,
  WorkManager, Coroutines. `minSdk 26`, `compileSdk`/`targetSdk 35`, JDK 17,
  Gradle 8.9 (Groovy DSL), AGP 8.5.2, flavors `staging` / `production`.
- Everything that can run offline does; UI strings live in `values`, `values-en`,
  `values-ru`, `values-de`.

## Commands (run from the repository root)
- All checks available without Android SDK: `./tools/check_all.sh`
  (`static_audit.py`, `sync_strings.py --check`, optionally `kotlin_frontend_check.py`
  and `kotlin_analysis.py`).
- Strings: `python3 tools/sync_strings.py` (writes) / `--check` (reports only).
  After editing strings run the check and commit the result.
- Compiler check: `python3 tools/kotlin_frontend_check.py` — needs `kotlinc` 2.1+ and
  JDK 17; with `--android-jar` (or `ANDROID_JAR` / `ANDROID_HOME`) it also validates
  real `android.*` signatures. `./tools/fetch_android_jar.sh` fetches `android.jar`
  where `dl.google.com` is blocked.
- Build / test / lint (needs the Android SDK and Maven access):
  `./gradlew assembleProductionDebug`, `./gradlew testDebugUnitTest`, `./gradlew lintDebug`.
  CI (`.github/workflows/android-ci.yml`) runs the static checks, the Kotlin frontend,
  lint, unit tests and both production builds.

## Boundaries
- Never commit keystores, `*.jks`, `keystore.properties`, or cryptographic master keys.
- Keep `allowBackup="false"`, `dataExtractionRules` and `backup_rules` cloud/D2D-safe.
- Do not weaken privacy claims: no Firebase, no analytics/crash SDKs, no telemetry.
  Threat intelligence stays opt-in and sends only a host name.
- Declared permissions must be used by code or a manifest component; unused sensitive
  permissions are a store-review risk.

## Conventions
- No user-facing literals: use `R.string.*`. New keys go through `tools/sync_strings.py`
  with `default`/`en`/`ru`/`de` values.
- Compare text with `uppercase(Locale.US)` / `lowercase(Locale.ROOT)`.
- Room migrations must be added to `ALL_MIGRATIONS` and to `AppDatabase` version.
- Conventional commits (`fix:`, `feat:`, `chore:`, `docs:`), one logical change per commit.
- Any change that touches behavior updates `docs/CHANGELOG.md`; claims in `README.md`
  and `docs/*.md` must be true (numbers of checks, steps, permissions, features).
