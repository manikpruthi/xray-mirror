#!/usr/bin/env bash
# Builds the X-ray Mirror APK.
#   ./build.sh            debug APK  -> release/XrayMirror-<version>-debug.apk
#   ./build.sh release    minified release APK -> release/XrayMirror-<version>.apk
#   ./build.sh install    release APK, then `adb install -r` to the connected phone
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-debug}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

VERSION=$(grep -m1 'versionName' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
mkdir -p release

case "$MODE" in
  debug)
    ./gradlew --console=plain -q assembleDebug
    cp app/build/outputs/apk/debug/app-debug.apk "release/XrayMirror-$VERSION-debug.apk"
    echo "release/XrayMirror-$VERSION-debug.apk"
    ;;
  release|install)
    ./gradlew --console=plain -q assembleRelease
    cp app/build/outputs/apk/release/app-release.apk "release/XrayMirror-$VERSION.apk"
    echo "release/XrayMirror-$VERSION.apk"
    if [ "$MODE" = install ]; then
      "$ANDROID_HOME/platform-tools/adb" install -r "release/XrayMirror-$VERSION.apk"
    fi
    ;;
  *) echo "usage: $0 [debug|release|install]"; exit 1 ;;
esac
