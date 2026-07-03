# Project Summary: PhoneGuard (Защитник)

## Overall Goal
Develop a comprehensive personal Android security application ("PhoneGuard") to protect the user's device from malware, spyware, network threats, and physical theft, while modernizing the project's build system and dependencies.

## Key Knowledge
- **Tech Stack:** Kotlin 2.1.0, Jetpack Compose (Material 3), AGP 8.5.2 (downgraded from 8.7.3 due to manifest merger bugs on Windows), Gradle 8.9.
- **Architecture:** Clean-ish architecture with `model` → `data/repository` → `ui/screens`. Dependency Injection via Hilt 2.55. Local storage via Room 2.6.1.
- **Core Modules:**
  - **Full Scan:** 13-step heuristic scan including Root detection, System Integrity, Accessibility abuse, Overlay attacks, Permission abuse, Hidden apps, and Device Admin abuse.
  - **Firewall:** VPN-based packet filtering (TUN interface) without root. Parses IPv4/TCP/UDP headers to identify UIDs and apply block/allow rules per app or network type (Wi-Fi/Mobile).
  - **Anti-Theft:** SMS-command driven (`#PG:PIN:COMMAND`) for locking, alarming, location tracking, stealth photography, and remote wipe. Uses `DeviceAdmin` and `StealthCameraService`.
  - **Vault:** Encrypted file storage with biometric unlock.
- **Build Environment:** Windows 11 with a Cyrillic project path (`d:\Проектики\Защитник`). 
- **Critical Constraint:** AGP 8.7+ has a known bug with `ManifestMerger2` on paths containing non-ASCII characters. Downgrading to AGP 8.5.2 was attempted to resolve this. `android.overridePathCheck=true` is set in `gradle.properties`.

## Recent Actions
- **Dependency Modernization:** Upgraded Kotlin to 2.1.0 (requiring the Compose Compiler plugin), AGP to 8.7.3 (then downgraded to 8.5.2), and updated all major libraries (Hilt, Room, Lifecycle, CameraX).
- **Scan Enhancements:** Implemented five new security checks: `AccessibilityServiceCheck`, `OverlayAttackCheck`, `PermissionAbuseCheck`, `HiddenAppsCheck`, and `DeviceAdminAbuseCheck`. Integrated them into `FullScanOrchestrator`.
- **Anti-Theft Overhaul:** Rewrote `SmsCommandReceiver` to support PIN-authenticated commands. Added `LocationProvider` (Fused Location) and `StealthCameraService` (Camera2 API) for remote tracking and evidence gathering.
- **Firewall Rewrite:** Reimplemented `PhoneGuardVpnService` with robust IPv4/TCP/UDP parsing, UID-based blocking via `ConnectivityManager`, rate-limited logging to Room, and an expanded `FirewallRule` model (supporting domain/IP blocking and background traffic restrictions).
- **Build Troubleshooting:** Encountered persistent `processDebugMainManifest` failures. Attempted fixes included creating symlinks to ASCII paths, copying the project to `d:\PhoneGuard`, and adjusting AGP versions. The build consistently fails at the manifest merger step when run via CLI, likely due to encoding/path issues specific to the Windows environment.

## Current Plan
1. [DONE] Modernize dependencies and build scripts.
2. [DONE] Implement advanced security scans (Accessibility, Overlay, Permissions, etc.).
3. [DONE] Enhance Anti-Theft with SMS commands and stealth features.
4. [DONE] Rewrite Firewall with packet-level filtering and logging.
5. [IN PROGRESS] Resolve build failures. The project currently fails to assemble via CLI due to manifest parsing errors. 
6. [TODO] Verify build in Android Studio (recommended next step as CLI is unstable in this environment).
7. [TODO] Implement domain-based filtering logic in the Firewall service.

---

## Summary Metadata
**Update time**: 2026-07-03T08:36:57.187Z 
