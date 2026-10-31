# Privacy Policy

_Last updated: 2026-10-31_

PhoneGuard ("we", "our", or "us") protects your privacy by design: the app has no backend, no account
and no telemetry SDK. This policy explains exactly what the app reads, what it stores on your device,
and the only situation in which anything leaves it.

## Summary

- Everything is processed and stored **on your device**.
- We do not collect accounts, advertising IDs, contacts, message contents or location history.
- The single optional network feature (threat intelligence) sends **only a host name** and is **off by
  default**.

## What the app reads (locally)

| Data | Why | Leaves the device? |
|---|---|---|
| Device state: Android version, model, security patch level, screen lock, storage encryption, SELinux, ADB/developer options, free space, installed packages and their requested permissions, device admins, accessibility services | the whole-phone check, the full scan and the privacy scanner | never |
| Incoming call number | call screening (whitelist → blacklist → local spam database → hidden/international rules) | never |
| Incoming SMS: sender and text | spam filter and the local phishing heuristic (a link plus pressure wording) | never (see threat intelligence below) |
| Location (last known) | anti-theft: send coordinates to your trusted number when the SIM is swapped or on request | only to your own trusted number, by SMS, when you enabled it |
| Camera | shoulder-surfing detector (frames analysed in memory by ML Kit) and anti-theft photos | never |
| Files you add to the Vault | encrypted storage | never |

Messages and phone numbers are **never** written to logs, analytics or crash reports. The local
`AnalyticsHelper` keeps only in-memory counters (for example "a call was blocked") and drops them when
the process ends; phone numbers cannot be passed to it by design.

## What the app stores on your device

- **Encrypted database** (SQLCipher, key in the Android Keystore): blocked and whitelisted numbers,
  block log (calls and SMS), firewall rules and log, vault metadata, scan history, spam entries,
  intrusion and SIM-swap events.
- **Encrypted vault files** (AES-256-GCM, keys in the Android Keystore).
- **Preferences** (DataStore): your settings, the language, the last phone-check score and the number
  of issues found — no personal content.
- **Local diagnostics**: crash reports in `files/crash-reports/` and timing measurements in the app log.
  They stay in the app's private storage and can be inspected or deleted by you at any time.

Old records are removed automatically by a scheduled cleanup task according to the retention settings.

## Third-party services

- **Opt-in threat intelligence (off by default).** When you enable it in Settings, the app sends *only
  the host name* of a link found in a suspicious SMS to the public URLhaus and OpenPhish databases, and
  caches the verdict locally. Message text, phone numbers, device identifiers and your own domain
  history are never sent.
- **Google Play Services**: the fused location provider (anti-theft) and ML Kit face detection
  (shoulder-surfing detector) run on-device; we do not receive any data from them.
- No advertising, analytics, crash-reporting or attribution SDKs are bundled with the app. There is no
  Firebase project and no `google-services.json` in the build.

## Permissions and what they are for

Everything declared in the manifest, and nothing else:

`SMS` (filter and remote commands) · `PHONE` / call log / phone numbers (screening and spam rules) ·
`CAMERA` + `RECORD_AUDIO` + `MICROPHONE` foreground service (shoulder-surfing detector, anti-theft
photo) · `LOCATION` (anti-theft last known position) · `NOTIFICATIONS` (block and scan notices) ·
`VPN` (the firewall) · `DEVICE ADMIN` (optional: lock/wipe) · `PACKAGE_USAGE_STATS` + `SYSTEM_ALERT_WINDOW`
(overlay and hidden-app checks) ·
`BATTERY OPTIMIZATION EXEMPTION` (optional, keeps background protection alive) · media/storage read
access (full scan of shared storage, vault import) · `BIOMETRIC` (vault unlock) · `BOOT_COMPLETED`
(restore protection after reboot).

`QUERY_ALL_PACKAGES` is used only to enumerate installed packages for the scan and the firewall list;
the result never leaves the device.

Each permission is requested when the related feature is used, and the app keeps working (with reduced
functionality) when you deny it.

## Your rights

- Delete all app data at any time in Settings (database, vault files, crash reports).
- Revoke any permission in Android system settings.
- Keep the threat intelligence check disabled; it is disabled by default.
- Export the local block/firewall logs as JSON and inspect them yourself.

## Children's privacy

PhoneGuard is not intended for users under the age of 13. We do not knowingly collect data from children.

## Changes to this policy

We may update this policy from time to time. Changes will be published in the repository and in the app.

## Contact

privacy@phoneguard.app
