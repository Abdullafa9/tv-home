#!/usr/bin/env bash
# Builds out/tv-home.apk with the Android SDK command-line tools only (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
exec > >(tee out/build.log) 2>&1

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK/build-tools" ]; then
  echo "Android SDK not found (ANDROID_HOME is not set)"; exit 1
fi
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
ANDROID_JAR="$SDK/platforms/$(ls "$SDK/platforms" | grep -E '^android-[0-9]+$' | sort -V | tail -1)/android.jar"
VERSION_CODE="${GITHUB_RUN_NUMBER:-1}"
echo "build-tools: $BT"
echo "platform:    $ANDROID_JAR"
echo "version:     1.$VERSION_CODE"
java -version 2>&1 | head -1

rm -rf build && mkdir -p build/gen build/classes build/dex

echo "== resources"
"$BT/aapt2" compile --dir app/res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$ANDROID_JAR" \
  --manifest app/AndroidManifest.xml -A app/assets --java build/gen \
  --min-sdk-version 23 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "1.$VERSION_CODE" \
  build/res.zip

echo "== java"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -d build/classes \
  $(find app/src build/gen -name '*.java')

echo "== dex"
"$BT/d8" --lib "$ANDROID_JAR" --min-api 23 --output build/dex $(find build/classes -name '*.class')
zip -q -j build/base.apk build/dex/classes.dex

echo "== sign"
"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk
"$BT/apksigner" sign --ks app/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out out/tv-home.apk build/aligned.apk
"$BT/apksigner" verify out/tv-home.apk
ls -l out/tv-home.apk
echo "BUILD OK"
