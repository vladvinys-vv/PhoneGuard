# HANDOFF: PhoneGuard

## Mission
Private, permission-conscious Android security tool: on-device full scan and phone check,
local VPN firewall without root, trusted-sender anti-theft commands, call/SMS blocking,
and an encrypted biometric vault. Everything that can run offline does; nothing is uploaded
except an opt-in host-name lookup (threat intelligence, off by default).

## Current state (2026-10-31)
- Branch `arena/681b6949-phoneguard` (PR #4); PR #3 уже влит в `main`. Все проверки зелёные:
  `tools/gradle_check.py` 0/0 (4 предупреждения), `tools/static_audit.py` 0/0,
  `tools/sync_strings.py --check` 0 missing, `tools/kotlin_frontend_check.py` 0 problems
  (209 files, with real `android.jar`), `tools/aapt2_check.py` compile+link (672 ресурса),
  `tools/kotlin_analysis.py` 0/0.
- Конфигурация Gradle починена: в `app/build.gradle` был `mapOf(...)` из Kotlin stdlib, из-за
  которого падала **любая** задача (`MissingMethodException` на этапе конфигурации). Первый
  зелёный прогон `./gradlew lintDebug testProductionDebugUnitTest assembleProductionDebug`
  ещё предстоит подтвердить в Codemagic — до этого Gradle в CI ни разу не доходил до компиляции.
- Build automation: `.github/workflows/android-ci.yml` (Gradle scripts check → static checks →
  Kotlin frontend with `platforms;android-35` → lint → unit tests → production debug/release APKs),
  `codemagic.yaml` (то же + webhook о статусе сборки), `build_release.sh`.
  `jules.yml` only validates `.jules` config.
- 16 КБ страницы памяти: закрытый `android-database-sqlcipher:4.5.4` заменён на
  `net.zetetic:sqlcipher-android:4.19.1` (`APP_SUPPORT_FLEXIBLE_PAGE_SIZES`), пакет
  `net.zetetic.database.sqlcipher`, `System.loadLibrary("sqlcipher")`. Проверка —
  `zipalign -c -P 16` (см. `docs/RELEASE.md`).
- Known, documented limitation: domain/IP lists in the firewall are stored but not enforced
  without an upstream VPN forward (see `firewall_lists_not_enforced_note` and the firewall
  screen). Full per-app blocking is unaffected.

## Finish line
- `./gradlew assembleProductionDebug testDebugUnitTest lintDebug` passes on a machine with the
  Android SDK (or in CI), including the `production` flavor.
- Working tree clean, no untracked build caches, no keystores in history.

## Where the truth lives
- `README.md` — features, integrations, CI, commands.
- `docs/DEVELOPMENT.md` — environment, checks (including how to get `kotlinc`/`android.jar`),
  conventions.
- `docs/CHANGELOG.md` — what changed and why (including fixed defects).
- `docs/RELEASE.md`, `docs/MANUAL_TESTING.md` — release checklist and manual scenarios.

## Boundaries
- Never commit keystores, `keystore.properties` or cryptographic master keys.
- Keep `allowBackup="false"` and the backup/data-extraction rules.
- No Firebase, analytics, crash-reporting or attribution SDKs.
- Declared permissions must be used; store review punishes unused sensitive ones.
