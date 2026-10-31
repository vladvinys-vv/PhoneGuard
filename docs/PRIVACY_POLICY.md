# Privacy Policy

_Last updated: 2026-10-31_

PhoneGuard ("we", "our", or "us") is committed to protecting your privacy. This Privacy Policy explains how we collect, use, and safeguard your information when you use our Android application.

## Information We Collect

- **Device Information**: Android version, device model and security patch level. These values are read
  locally to show the device state; they are never transmitted and no advertising/device identifiers are used.
- **Permissions Data**: We request permissions only when needed (camera, SMS, phone, location, notifications). Usage is limited to the features described in the app.
- **Scan & Block Logs**: Local history of security scans, blocked calls/SMS, and firewall events. All data is stored locally on your device and encrypted using SQLCipher.
- **Vault Files**: Files you import into the Secure Vault are encrypted before storage. We cannot access the contents of your vault.
- **Phone check**: the whole-phone audit reads device settings (screen lock, storage encryption, ADB/developer
  options, patch level, free space, counts of installed/outdated apps, granted permissions). Everything stays on
  the device; only the resulting score, the number of problems and the timestamp are stored in local preferences.

## How We Use Your Information

- To provide security scanning, call/SMS blocking, and firewall protection.
- To generate local reports and statistics.
- To keep local diagnostics (crash reports and timing measurements) on the device.

## Data Storage and Security

- All sensitive data is stored locally on your device.
- The Room database is encrypted with SQLCipher using a key managed by the Android Keystore.
- Vault files are encrypted with Android Security Crypto (AES256-GCM).
- We do not upload your personal data to external servers. The app has no cloud backend and no telemetry SDK.
- Crash reports and performance traces never leave the device: they are stored in the app's private storage
  (`files/crash-reports/`) and can be inspected or deleted by the user.

## Third-Party Services

- **Opt-in threat intelligence (off by default)**: when enabled in Settings, the app sends *only the host name*
  of a link found in a suspicious SMS to the public URLhaus and OpenPhish databases. Message text, phone numbers
  and device identifiers are never sent.
- **Google Play Services**: used locally for the fused location provider (anti-theft) and ML Kit face detection
  (shoulder-surfing detector). No analytics data is collected by us.
- No advertising, analytics, crash-reporting or attribution SDKs are bundled with the app.

## Your Rights

- You can delete all app data at any time from Settings.
- You can revoke permissions in Android system settings.
- You can delete locally stored crash reports at any time (Settings or app data).
- You can leave the opt-in threat intelligence check disabled (it is disabled by default).

## Children’s Privacy

PhoneGuard is not intended for users under the age of 13. We do not knowingly collect data from children.

## Changes to This Policy

We may update this policy from time to time. Changes will be posted within the app and on our website.

## Contact Us

If you have questions about this Privacy Policy, contact us at: privacy@phoneguard.app
