#!/usr/bin/env bash
# Build the Skiff Go Android APK (see README.md).
#
# Usage:
#   ./build-android.sh           # debug APK (default)
#   ./build-android.sh release   # release APK (debug-signed)
#
# Environment (optional):
#   ANDROID_SDK_ROOT / ANDROID_HOME   path to Android SDK
#   JAVA_HOME                         JDK 17+ (Gradle uses it automatically if set)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$ROOT/android"
PROPS="$ANDROID_DIR/local.properties"

find_sdk() {
    for candidate in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/android-sdk" "$HOME/Android/Sdk"; do
        if [ -n "$candidate" ] && [ -d "$candidate" ]; then
            echo "$candidate"
            return
        fi
    done
    return 1
}

if [ ! -f "$PROPS" ]; then
    if ! sdk="$(find_sdk)"; then
        echo "build-android.sh: Android SDK not found. Set ANDROID_SDK_ROOT or create $PROPS with sdk.dir=..." >&2
        exit 1
    fi
    printf 'sdk.dir=%s\n' "$sdk" >"$PROPS"
    echo "build-android.sh: wrote $PROPS (sdk.dir=$sdk)"
fi

case "${1:-debug}" in
    debug | "") task=assembleDebug; apk_rel=app/build/outputs/apk/debug/app-debug.apk ;;
    release) task=assembleRelease; apk_rel=app/build/outputs/apk/release/app-release.apk ;;
    *) echo "build-android.sh: unknown variant '$1' (use: debug | release)" >&2; exit 1 ;;
esac

command -v java >/dev/null 2>&1 || { echo "build-android.sh: java not found (need JDK 17+)" >&2; exit 1; }

cd "$ANDROID_DIR"
chmod +x ./gradlew 2>/dev/null || true
./gradlew "$task"

APK="$ANDROID_DIR/$apk_rel"
[ -f "$APK" ] || { echo "build-android.sh: expected APK missing: $APK" >&2; exit 1; }
echo ""
echo "OK: $APK"
echo "Install: adb install -r \"$APK\""
