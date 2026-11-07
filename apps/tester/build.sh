#!/usr/bin/env bash
# 手工打包 APK（arm64 原生工具链）：aapt -> javac -> d8 -> zip -> 对齐 -> apksigner
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
PROJ="$(cd "$(dirname "$0")" && pwd)"
OUT="$PROJ/build"
KS="$PROJ/debug.keystore"
AAPT="${AAPT:-/usr/bin/aapt}"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt package"
python3 "$PROJ/gen_icon.py" >/dev/null
"$AAPT" package -f \
  -M "$PROJ/AndroidManifest.xml" \
  -S "$PROJ/res" \
  -A "$PROJ/assets" \
  -I "$PLATFORM" \
  -F "$OUT/base.apk" \
  --min-sdk-version 26 --target-sdk-version 34

echo "[2/6] javac"
find "$PROJ/src" -name '*.java' > "$OUT/sources.txt"
javac -nowarn --release 8 -encoding UTF-8 \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/6] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$PLATFORM" --min-api 26 --release \
  --output "$OUT/dex" @"$OUT/classes.txt"

echo "[4/6] 装入 dex"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex)

echo "[5/6] 对齐"
python3 "$PROJ/align.py" "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] 签名"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android \
    -alias dsh -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=DSH Test, O=DSH, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$PROJ/VibShakeTest.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify "$PROJ/VibShakeTest.apk" && echo "签名校验 OK"
"$AAPT" dump badging "$PROJ/VibShakeTest.apk" | head -6
ls -lh "$PROJ/VibShakeTest.apk"
echo "OK -> $PROJ/VibShakeTest.apk"
