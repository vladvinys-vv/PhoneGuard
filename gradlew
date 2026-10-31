#!/bin/sh
# Gradle wrapper script for Unix.
#
# Скачивает дистрибутив Gradle 8.9 при первом запуске (в CI он обычно уже в кэше
# $GRADLE_USER_HOME) и передаёт управление настоящему gradle. `set -e` + `curl -f`
# нужны, чтобы сетевой сбой давал внятную ошибку, а не «unzip: cannot find zipfile».
set -eu

DIST_URL="https://services.gradle.org/distributions/gradle-8.9-bin.zip"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DISTS_DIR="$GRADLE_USER_HOME/wrapper/dists"
ZIP_FILE="$DISTS_DIR/gradle-8.9-bin.zip"
UNZIP_DIR="$DISTS_DIR/gradle-8.9"
GRADLE_BIN="$UNZIP_DIR/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
    mkdir -p "$DISTS_DIR"
    echo "Downloading Gradle 8.9..."
    if ! curl -fL --retry 3 --connect-timeout 30 "$DIST_URL" -o "$ZIP_FILE"; then
        echo "gradlew: не удалось скачать $DIST_URL" >&2
        exit 1
    fi
    if ! unzip -q -o "$ZIP_FILE" -d "$DISTS_DIR"; then
        echo "gradlew: скачанный архив не распаковался ($ZIP_FILE)" >&2
        exit 1
    fi
fi

exec "$GRADLE_BIN" "$@"
