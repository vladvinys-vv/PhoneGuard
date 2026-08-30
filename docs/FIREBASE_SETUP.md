# Firebase Setup Guide

## 1. Create Firebase Project
1. Go to https://console.firebase.google.com
2. Click "Add project"
3. Enter project name: `PhoneGuard`
4. Disable Google Analytics (or enable if needed)
5. Click "Create project"

## 2. Add Android App
1. In project overview, click "Add app" → Android
2. Enter package name: `com.phoneguard`
3. (Optional) Enter SHA-1 and SHA-256 fingerprints from your keystore
   - Debug: `./gradlew signingReport`
   - Release: from `keystore.properties`
4. Click "Register app"

## 3. Download Configuration
1. Download `google-services.json`
2. Place it in `app/` directory (same level as `build.gradle`)
3. Commit to version control (if open source) or add to `.gitignore` (if private)

## 4. Enable Services
In Firebase Console:
- **Crashlytics**: Automatically enabled after adding SDK
- **Analytics**: Click "Get started" if not enabled
- **Performance Monitoring**: Click "Get started"

## 5. Build and Test
```bash
./gradlew assembleProductionDebug
```

## 6. Verify Integration
- Run the app and trigger a test crash:
  ```kotlin
  FirebaseCrashlytics.getInstance().log("Test crash")
  throw RuntimeException("Test crash")
  ```
- Check Firebase Console → Crashlytics for the crash

## 7. Release Preparation
- Add `google-services.json` to CI/CD secrets
- Ensure signing config is correct for release builds
- Upload App Bundle to Play Store

## google-services.json Template
```json
{
  "project_info": {
    "project_number": "YOUR_PROJECT_NUMBER",
    "project_id": "YOUR_PROJECT_ID",
    "storage_bucket": "YOUR_PROJECT_ID.appspot.com"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "YOUR_APP_ID",
        "android_client_info": {
          "package_name": "com.phoneguard"
        }
      },
      "oauth_client": [],
      "api_key": [
        {
          "current_key": "YOUR_API_KEY"
        }
      ],
      "services": {
        "appinvite_service": {
          "other_platform_oauth_client": []
        }
      }
    }
  ],
  "configuration_version": "1"
}
```

## Important Notes
- Never commit real `google-services.json` with actual API keys to public repositories
- For CI/CD, use environment variables or secret management
- For staging builds, you can use a separate Firebase project
- Firebase automatically handles Google Play Services availability
