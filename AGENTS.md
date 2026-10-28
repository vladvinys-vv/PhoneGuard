# Project rules: PhoneGuard

Global rules live in ~/.zcode/AGENTS.md. This file adds project specifics.
HANDOFF.md is the full contract (mission, queue, acceptance per task).

## Project
- What it is: Android native security application with device malware scanning, local VPN firewall, anti-theft SMS remote commands, call screening, and encrypted biometric vault.
- Stack: Android SDK (Kotlin), Jetpack Compose, Room DB, Android VpnService, Biometric API, Coroutines
- Target users / market: Global (UI in Russian/English)

## Commands (run from the workspace root with absolute paths)
- Build: `./gradlew assembleDebug` (Windows: `.\gradlew.bat assembleDebug`)
- Test: `./gradlew test` (Windows: `.\gradlew.bat test`)
- Language check (must print nothing before each commit):
  `git -C C:/Users/vladv/projects/PhoneGuard diff --cached | grep -nP '[\x{4e00}-\x{9fff}]' || true`

## Boundaries
- Workspace: C:\Users\vladv\projects\PhoneGuard
- Allowed write paths: `app/src/`, `docs/`, `WORKLOG.md`, `STUCK.md`, `STATUS.md`, `requests/`
- Read-only: `gradle/`, `gradlew*`, `build.gradle` root
- Project red lines: NEVER commit .keystore, .jks, or hardcoded cryptographic master keys; keep allowBackup="false".

## Conventions
- Kotlin: idiomatic Kotlin, safe coroutines, proper timeout handling in background services
- One feature per commit; commit message format: `T<id>: <what>` or conventional commit
