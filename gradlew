#!/bin/sh
# Gradle wrapper script for Unix

DIST_URL="https://services.gradle.org/distributions/gradle-8.9-bin.zip"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DISTS_DIR="$GRADLE_USER_HOME/wrapper/dists"
ZIP_FILE="$DISTS_DIR/gradle-8.9-bin.zip"
UNZIP_DIR="$DISTS_DIR/gradle-8.9"
GRADLE_BIN="$UNZIP_DIR/gradle-8.9/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
    mkdir -p "$DISTS_DIR"
    echo "Downloading Gradle 8.9..."
    curl -L "$DIST_URL" -o "$ZIP_FILE"
    unzip -q "$ZIP_FILE" -d "$DISTS_DIR"
fi

exec "$GRADLE_BIN" "$@"
