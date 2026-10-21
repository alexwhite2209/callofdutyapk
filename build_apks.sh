#!/bin/bash
# Builds CoD4 Winlator as a single .apks (split APK set) for installation with SAI.
# Needs Android SDK (platform 35, build-tools, NDK 24.0.8215888, CMake 3.22.1) from dl.google.com.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
export ANDROID_HOME=${ANDROID_HOME:-$ROOT/android-sdk}
APP=$ROOT
BUNDLETOOL_VER=1.17.2

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q /tmp/cmdtools.zip -d "$ANDROID_HOME/cmdline-tools" && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
yes 2>/dev/null | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;34.0.0" "ndk;24.0.8215888" "cmake;3.22.1"

echo "sdk.dir=$ANDROID_HOME" > "$APP/local.properties"
(cd "$APP" && chmod +x gradlew && ./gradlew --no-daemon bundleDebug)

AAB=$(find "$APP/app/build/outputs/bundle/debug" -name '*.aab' | head -1)
JAR=$ROOT/bundletool-$BUNDLETOOL_VER.jar
[ -f "$JAR" ] || curl -fL -o "$JAR" "https://maven.google.com/com/android/tools/build/bundletool/$BUNDLETOOL_VER/bundletool-$BUNDLETOOL_VER.jar"

java -jar "$JAR" build-apks --bundle="$AAB" --output="$ROOT/CoD4-Winlator.apks" --overwrite \
  --ks="$HOME/.android/debug.keystore" --ks-pass=pass:android --ks-key-alias=androiddebugkey --key-pass=pass:android
echo "Готово: $ROOT/CoD4-Winlator.apks"
