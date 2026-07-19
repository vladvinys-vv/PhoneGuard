# PhoneGuard — Remaining Features Roadmap

This document tracks features that are planned but not yet fully implemented.

## CallBlocker
- **SMS Blocking**: Documented limitation (see `sms-blocker-limitation.md`).
- **CSV Import**: Implemented via `CsvImportHelper` and `CallBlockerViewModel.importFromCsv`.
- **Spam Database**: Local JSON asset with known spam numbers + periodic updates.
- **Pattern Rules**: Regex/wildcard-based blocking rules (e.g., `+7*`).
- **Block Notifications**: Implemented via `CallScreeningServiceImpl.showBlockedNotification`.

## Firewall
- **Real Traffic Forwarding**: Requires `Tun2Socket` / `PacketForwarder` library or root access. Current implementation is MVP with VPN interface but no actual packet inspection.
- **Domain/IP Management**: UI for managing blocked domains and IPs per app (implemented via edit dialog in AppFirewallItem).
- **Traffic Statistics**: Daily/weekly blocking statistics per app (partially implemented via `FirewallStats`).
- **Log Details**: Add traffic direction (inbound/outbound) and app name to logs (implemented).

## FullScan
- **Detailed Report Screen**: Tap on scan history to view full report with all issues.
- **PDF Export**: Export scan report as PDF (integrated into ScanDetailScreen).
- **Scan Comparison**: Basic comparison implemented (`ScanComparison` in `FullScanViewModel`). UI integration in progress.

## Vault
- **Video/Document Support**: Implemented via `VaultItemCategory` enum (IMAGE, VIDEO, DOCUMENT, OTHER).
- **File Preview**: Show thumbnail preview before importing.
- **File Size Limit**: Implemented (50MB limit in `VaultViewModel`).

## AntiTheft
- **Remote Wipe via FCM**: Implement Firebase Cloud Messaging for remote wipe command (placeholder).
- **Remote Lock via Device Admin**: Use `DeviceAdminReceiver` for remote lock (placeholder).
- **Siren/Alarm**: Customizable alarm sound implemented via MediaPlayer with system alarm URI.
- **Failed Attempts Photo**: Implemented via camera permission and photo capture.

## Performance
- **Camera Resolution**: Optimized to 320x240 for better battery life.
- **Network Caching**: Implemented 24h cache for `getInstalledApps()` via `InstalledAppsCache`.

## Testing
- **Manual Testing**: Checklist created (`MANUAL_TESTING.md`). Required on Android 8, 10, 12, 13, 14, 15 across Samsung, Xiaomi, Huawei, Pixel.
- **Battery Testing**: Use Battery Historian to verify background task efficiency.
- **Memory Testing**: LeakCanary added as debug dependency.

## Release
- **Firebase Config**: Add `google-services.json` for Firebase Analytics and Crashlytics (requires manual Firebase project setup).
- **Play Store Assets**: Screenshots, description, keywords, changelog (templates created in `docs/`).
- **Privacy Policy**: Created (`docs/PRIVACY_POLICY.md`).
- **Support Email**: Configure support contact.
- **Build Flavors**: staging/production flavors added to `build.gradle`.
- **Battery Optimization**: `BatteryOptimizationHelper` + WorkManager constraints + adaptive Shoulder Surfer intervals.

## Completed
- [x] Bottom navigation
- [x] Performance Monitoring integration
- [x] ShoulderSurferService camera resolution optimization (320x240)
- [x] Navigation tests
- [x] UseCase/Repository tests
- [x] SMS blocker limitation documentation
- [x] Scan comparison (basic)
- [x] Firewall traffic statistics (basic)
- [x] CSV import for CallBlocker
- [x] Block notifications for CallBlocker
- [x] LeakCanary for memory leak detection
- [x] gradle.properties for production builds
- [x] Firebase Performance Monitoring dependency
- [x] NetworkMonitor utility
- [x] CrashHandler with global uncaught exception handler
- [x] DataCleanupWorker for auto-deleting logs older than 90 days
- [x] ScheduledFullScanWorker for periodic scans
- [x] ConsentScreen for permissions
- [x] GDPR delete all data functionality
- [x] Battery optimization prompt
- [x] Deep linking for all main screens
- [x] Pull-to-refresh actions for main lists
- [x] Empty states with icons for all lists
- [x] Confirm dialogs for destructive actions
- [x] FLAG_SECURE for Vault screen
- [x] SQLCipher encryption with Android Keystore
- [x] Network security config
- [x] ProGuard rules for all modules
- [x] CI/CD with GitHub Actions
- [x] Signing config with keystore.properties support
- [x] Dark theme support
- [x] Onboarding screen
- [x] Language selector
- [x] LogExporter with real data
- [x] AnalyticsHelper for Firebase Analytics
- [x] All ViewModel tests
- [x] All DAO tests
- [x] UI tests for Settings, CallBlocker, Firewall
- [x] Navigation tests
- [x] Pagination for large lists
- [x] InstalledAppsCache (24h TTL)
- [x] StrictMode in debug
- [x] Scan throttle (6 hours)
- [x] Battery optimization prompt
- [x] usesPermissionFlags and permissionGroup in manifest
- [x] Detailed report screen for FullScan (`ScanDetailScreen`)
- [x] PDF export utility (`PdfExportHelper`) with UI integration
- [x] File preview button in Vault
- [x] FCM remote wipe/lock placeholders in AntiTheft
- [x] Spam database schema (`SpamNumber` entity + DAO + migration)
- [x] Pattern rules utility (`PatternRules`)
- [x] Accessibility documentation (`accessibility.md`)
- [x] CSV import for CallBlocker
- [x] Block notifications for CallBlocker
- [x] LeakCanary for memory leak detection
- [x] gradle.properties for production builds
- [x] Vault multi-category support (IMAGE, VIDEO, DOCUMENT, OTHER)
- [x] Firewall log detailization (appName, trafficDirection)
- [x] Firewall domain/IP management UI
- [x] AntiTheft siren/alarm with MediaPlayer
- [x] Unit tests for PdfExportHelper and AntiTheft alarm
- [x] Privacy Policy template
- [x] Play Store listing template
- [x] Changelog template
- [x] Manual testing checklist
- [x] Build flavors (staging/production)
- [x] Battery optimization helper
- [x] WorkManager battery constraints
- [x] Release documentation template
