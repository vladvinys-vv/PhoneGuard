#!/usr/bin/env bash
# Сборка релизного APK/AAB PhoneGuard.
#
#   ./build_release.sh                    # production release (APK; AAB — если есть keystore)
#   ./build_release.sh production release
#   ./build_release.sh staging debug
#
# Подпись включается только при наличии keystore.properties или переменных окружения
# KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD (см. app/build.gradle).
set -euo pipefail
cd "$(dirname "$0")"

FLAVOR=${1:-production}
BUILD_TYPE=${2:-release}

case "$FLAVOR" in
    staging|production) ;;
    *) echo "ERROR: неизвестный flavor '$FLAVOR' (ожидается staging или production)"; exit 1 ;;
esac
case "$BUILD_TYPE" in
    debug|release) ;;
    *) echo "ERROR: неизвестный build type '$BUILD_TYPE' (ожидается debug или release)"; exit 1 ;;
esac

capitalize() { local s=$1; printf '%s%s' "$(printf '%s' "${s:0:1}" | tr '[:lower:]' '[:upper:]')" "${s:1}"; }
VARIANT="$(capitalize "$FLAVOR")$(capitalize "$BUILD_TYPE")"

echo "=== PhoneGuard Release Builder ==="
echo ""

# Для release нужен либо keystore.properties, либо KEYSTORE_PATH.
if [ "$BUILD_TYPE" = "release" ]; then
    if [ -z "${KEYSTORE_PATH:-}" ] && [ ! -f keystore.properties ]; then
        echo "WARNING: keystore.properties не найден и KEYSTORE_PATH не задан —"
        echo "         APK соберётся неподписанным (unsigned) и не будет принят Play Store."
        echo "         Создайте keystore.properties:"
        echo "           storeFile=path/to/keystore.jks"
        echo "           storePassword=..."
        echo "           keyAlias=..."
        echo "           keyPassword=..."
        echo ""
    fi
fi

# Быстрые проверки до тяжёлой сборки.
if command -v python3 >/dev/null 2>&1; then
    echo "--- Static audit"
    python3 tools/static_audit.py
    echo ""
fi

echo "Building $FLAVOR $BUILD_TYPE..."
./gradlew clean "assemble${VARIANT}" --stacktrace

APK_DIR="app/build/outputs/apk/${FLAVOR}/${BUILD_TYPE}"
if ! compgen -G "${APK_DIR}/*.apk" >/dev/null 2>&1; then
    echo "ERROR: APK не найден в ${APK_DIR}"
    exit 1
fi
for apk in "${APK_DIR}"/*.apk; do
    echo "APK built: ${apk} ($(du -h "$apk" | cut -f1))"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$apk"
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$apk"
    fi
done

if [ "$BUILD_TYPE" = "release" ]; then
    echo ""
    echo "Building App Bundle for Play Store..."
    ./gradlew "bundle${VARIANT}" --stacktrace

    if ! compgen -G "app/build/outputs/bundle/*/*.aab" >/dev/null 2>&1; then
        echo "ERROR: AAB не найден в app/build/outputs/bundle/"
        exit 1
    fi
    for aab in app/build/outputs/bundle/*/*.aab; do
        echo "AAB built: ${aab} ($(du -h "$aab" | cut -f1))"
    done
fi

echo ""
echo "=== Build complete ==="
