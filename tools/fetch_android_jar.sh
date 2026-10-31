#!/usr/bin/env bash
# Достаёт android.jar для проверок и печатает путь к нему на stdout.
#
#   ANDROID_JAR="$(./tools/fetch_android_jar.sh)" && \
#       python3 tools/kotlin_frontend_check.py --android-jar "$ANDROID_JAR"
#
# Порядок поиска:
#   1. уже скачанный jar в кеше (~/.cache/phoneguard/android-jar);
#   2. установленный Android SDK ($ANDROID_HOME / $ANDROID_SDK_ROOT);
#   3. зеркало платформ в GitHub (Sable/android-platforms) — нужно там, где
#      dl.google.com и maven.google.com недоступны (например, в песочницах).
#      Качается только каталог нужной платформы (sparse checkout, ~26 МБ).
#
# Переменные: ANDROID_API (по умолчанию 35), ANDROID_JAR_CACHE (каталог кеша).
set -euo pipefail

API="${ANDROID_API:-35}"
CACHE="${ANDROID_JAR_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/phoneguard/android-jar}"
INSTALLED="$CACHE/android-$API/android.jar"

if [ -f "$INSTALLED" ]; then
    echo "$INSTALLED"
    exit 0
fi

for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    if [ -n "$root" ] && [ -f "$root/platforms/android-$API/android.jar" ]; then
        echo "$root/platforms/android-$API/android.jar"
        exit 0
    fi
done

if ! command -v git >/dev/null 2>&1; then
    echo "fetch_android_jar: нет git и не найден android.jar для API $API" >&2
    exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "fetch_android_jar: скачиваю android-$API из зеркала Sable/android-platforms" >&2
git clone --filter=blob:none --no-checkout --depth 1 \
    https://github.com/Sable/android-platforms.git "$tmp/repo" >&2
git -C "$tmp/repo" sparse-checkout init --cone >&2
git -C "$tmp/repo" sparse-checkout set "android-$API" >&2
git -C "$tmp/repo" checkout >&2

if [ ! -f "$tmp/repo/android-$API/android.jar" ]; then
    echo "fetch_android_jar: в зеркале нет android-$API/android.jar" >&2
    exit 1
fi

mkdir -p "$(dirname "$INSTALLED")"
mv "$tmp/repo/android-$API/android.jar" "$INSTALLED"
echo "$INSTALLED"
