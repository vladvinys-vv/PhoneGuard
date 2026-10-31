# HANDOFF: PhoneGuard

## Mission
Private, permission-conscious Android security tool: on-device full scan and phone check,
local VPN firewall without root, trusted-sender anti-theft commands, call/SMS blocking,
and an encrypted biometric vault. Everything that can run offline does; nothing is uploaded
except an opt-in host-name lookup (threat intelligence, off by default).

## Current state (2026-10-31)
- Branch `arena/28ec46dc-phoneguard`; all checks green:
  `tools/static_audit.py` 0/0, `tools/sync_strings.py --check` 0 missing,
  `tools/kotlin_frontend_check.py` 0 problems (209 files, with real `android.jar`),
  `tools/kotlin_analysis.py` 0/0.
- Build automation: `.github/workflows/android-ci.yml` (static checks → Kotlin frontend with
  `platforms;android-35` → lint → unit tests → production debug/release APKs), `codemagic.yaml`,
  `build_release.sh`. `jules.yml` only validates `.jules` config.
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
