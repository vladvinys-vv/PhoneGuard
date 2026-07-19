# PhoneGuard — Remaining Features Roadmap

This document tracks features that are planned but not yet fully implemented.

## CallBlocker
- **SMS Blocking**: Android does not provide a public API for SMS blocking without carrier integration. See `sms-blocker-limitation.md` for details.
- **CSV Import**: Add import/export for blacklist/whitelist via CSV files.
- **Spam Database**: Local JSON asset with known spam numbers + periodic updates.
- **Pattern Rules**: Regex/wildcard-based blocking rules (e.g., `+7*`).
- **Block Notifications**: Show notification when a call/SMS is blocked.

## Firewall
- **Real Traffic Forwarding**: Requires `Tun2Socket` / `PacketForwarder` library or root access. Current implementation is MVP with VPN interface but no actual packet inspection.
- **Domain/IP Management**: UI for managing blocked domains and IPs per app.
- **Traffic Statistics**: Daily/weekly blocking statistics per app (partially implemented via `FirewallStats`).
- **Log Details**: Add traffic direction (inbound/outbound) and app name to logs.

## FullScan
- **Detailed Report Screen**: Tap on scan history to view full report with all issues.
- **PDF Export**: Export scan report as PDF.
- **Scan Comparison**: Basic comparison implemented (`ScanComparison` in `FullScanViewModel`). Needs UI integration.

## Vault
- **Video/Document Support**: Currently supports images only. Extend to video, PDF, documents.
- **File Preview**: Show thumbnail preview before importing.
- **File Size Limit**: Implemented (50MB limit in `VaultViewModel`).

## AntiTheft
- **Remote Wipe via FCM**: Implement Firebase Cloud Messaging for remote wipe command.
- **Remote Lock via Device Admin**: Use `DeviceAdminReceiver` for remote lock.
- **Siren/Alarm**: Customizable alarm sound for remote alarm feature.
- **Failed Attempts Photo**: Implemented via camera permission and photo capture.

## Performance
- **Camera Resolution**: Optimized to 320x240 for better battery life.
- **Network Caching**: Implemented 24h cache for `getInstalledApps()` via `InstalledAppsCache`.

## Testing
- **Manual Testing**: Required on Android 8, 10, 12, 13, 14, 15 across Samsung, Xiaomi, Huawei, Pixel.
- **Battery Testing**: Use Battery Historian to verify background task efficiency.
- **Memory Testing**: Use LeakCanary to detect memory leaks.

## Release
- **Firebase Config**: Add `google-services.json` for Firebase Analytics and Crashlytics.
- **Play Store Assets**: Screenshots, description, keywords, changelog.
- **Privacy Policy**: Create privacy policy document.
- **Support Email**: Configure support contact.

## Completed
- [x] Bottom navigation
- [x] Performance Monitoring integration
- [x] ShoulderSurferService camera resolution optimization
- [x] Navigation tests
- [x] UseCase/Repository tests
- [x] SMS blocker limitation documentation
- [x] Scan comparison (basic)
- [x] Firewall traffic statistics (basic)
