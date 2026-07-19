# Release Checklist

## Before Building
1. **Firebase Setup**
   - Create Firebase project at https://console.firebase.google.com
   - Add Android app with package name `com.phoneguard`
   - Download `google-services.json` and place in `app/`
   - Enable Crashlytics, Analytics, Performance Monitoring in Firebase Console
   - Add SHA-1/SHA-256 fingerprints from `keystore.properties`

2. **Signing Config**
   - Generate release keystore: `keytool -genkey -v -keystore phoneguard.jks -keyalg RSA -keysize 2048 -validity 10000`
   - Store in secure location, add path/passwords to `keystore.properties` or env variables
   - Never commit `keystore.properties` or `.jks` to version control

3. **Build Flavors**
   - `./gradlew assembleProductionRelease` — production APK/AAB
   - `./gradlew assembleStagingRelease` — staging build for testing
   - `./gradlew bundleProductionRelease` — Play Store App Bundle

4. **Play Store Assets**
   - Screenshots: phone (320dp, 384dp, 480dp), tablet (600dp, 720dp), foldable
   - Feature graphic: 1024x500
   - App icon: 512x512
   - Promo video (optional)

5. **Manual Testing** (see `MANUAL_TESTING.md`)
   - Android 8, 10, 12, 13, 14, 15
   - Samsung, Xiaomi, Huawei, Pixel
   - Battery historian test
   - Permission flow test

6. **Compliance**
   - Privacy Policy URL (host `docs/PRIVACY_POLICY.md`)
   - Terms of Service URL
   - Support email configured
   - Data safety form filled in Play Console

## Post-Release
- Monitor Crashlytics for crash-free users > 99%
- Monitor Performance Monitoring for ANR rate < 0.1%
- Respond to reviews within 24h
- Plan hotfix process
