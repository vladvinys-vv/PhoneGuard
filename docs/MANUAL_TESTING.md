# Manual Testing Checklist

## Devices / Emulators
- Android 8.0 (API 26) — Oreo
- Android 10 (API 29)
- Android 12 (API 31)
- Android 13 (API 33)
- Android 14 (API 34)
- Android 15 (API 35)

## Scenarios
1. **Onboarding & Permissions**
   - [ ] Consent screen appears on first launch.
   - [ ] Camera, SMS, Phone, Location permissions are requested with rationale.
   - [ ] App handles permission denial gracefully.

2. **Call & SMS Blocker**
   - [ ] CallScreeningService blocks numbers from blacklist.
   - [ ] Whitelist numbers bypass blocking.
   - [ ] Blocked call notification appears.
   - [ ] CSV import adds numbers correctly.

3. **Firewall**
   - [ ] VPN toggle starts/stops foreground service.
   - [ ] App rules (Wi-Fi/Mobile/Block All) persist after restart.
   - [ ] Domain/IP blocking dialog works.
   - [ ] Logs show appName and traffic direction.

4. **Full Scan**
   - [ ] Scan runs all 13 checks.
   - [ ] Progress updates correctly.
   - [ ] Scan history persists.
   - [ ] PDF export creates file and shows path.
   - [ ] Scan comparison shows diffs.

5. **Vault**
   - [ ] Biometric unlock works.
   - [ ] Import image/video/document works.
   - [ ] File size limit (50MB) enforced.
   - [ ] FLAG_SECURE prevents screenshots.
   - [ ] Delete confirmation dialog appears.

6. **Anti-Theft**
   - [ ] PIN set/locked correctly.
   - [ ] SIM lock detection triggers on change.
   - [ ] Shoulder surfer starts with camera permission.
   - [ ] Siren alarm plays and stops.
   - [ ] Remote wipe/lock placeholders show correct messages.

7. **Settings**
   - [ ] Language selection persists.
   - [ ] Log export creates JSON file.
   - [ ] Data deletion clears all local data.
   - [ ] Battery optimization prompt appears.

8. **Navigation**
   - [ ] Bottom navigation switches between main screens.
   - [ ] Deep links work (phoneguard://antitheft, etc.).

## Notes
- Battery drain should be minimal; VPN and foreground service must not keep device awake unnecessarily.
- On Chinese OEMs (Xiaomi, Huawei), background services may be killed; verify battery optimization whitelelisting.
