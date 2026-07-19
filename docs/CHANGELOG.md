# Changelog

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
