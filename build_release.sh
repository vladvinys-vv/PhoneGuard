#!/bin/bash
set -e

echo "=== PhoneGuard Release Builder ==="
echo ""

# Check for keystore
if [ -z "$KEYSTORE_PATH" ] && [ ! -f "keystore.properties" ]; then
    echo "ERROR: keystore.properties not found and KEYSTORE_PATH env not set"
    echo "Create keystore.properties with:"
    echo "  storeFile=path/to/keystore.jks"
    echo "  storePassword=..."
    echo "  keyAlias=..."
    echo "  keyPassword=..."
    exit 1
fi

FLAVOR=${1:-production}
BUILD_TYPE=${2:-release}

echo "Building $FLAVOR $BUILD_TYPE..."

./gradlew clean "assemble${FLAVOR^}${BUILD_TYPE^}"

APK_PATH="app/build/outputs/apk/${FLAVOR}/${BUILD_TYPE}/app-${FLAVOR}-${BUILD_TYPE}.apk"
AAB_PATH="app/build/outputs/bundle/${FLAVOR}/app-${FLAVOR}-release.aab"

if [ -f "$APK_PATH" ]; then
    echo "APK built: $APK_PATH"
    ls -lh "$APK_PATH"
fi

if [ "$BUILD_TYPE" = "release" ] && [ "$FLAVOR" = "production" ]; then
    echo "Building App Bundle for Play Store..."
    ./gradlew "bundle${FLAVOR^}${BUILD_TYPE^}"
    if [ -f "$AAB_PATH" ]; then
        echo "AAB built: $AAB_PATH"
        ls -lh "$AAB_PATH"
    fi
fi

echo ""
echo "=== Build complete ==="
