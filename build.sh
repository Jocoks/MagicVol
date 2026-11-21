#!/bin/sh
# MagicVol LSPosed 模块：容器内构建脚本（javac + d8 + aapt2 + pack.py）
# 用法：./build.sh            # 只出未签名 APK（交付用）
#       ./build.sh --sign     # 额外用 tools/debug.keystore 签一份用于真机自测
set -e

ROOT=$(cd "$(dirname "$0")" && pwd)
TOOLS="$ROOT/tools"
BUILD="$ROOT/build"
# 可用环境变量覆盖（默认值是本机开发环境）
AAPT2=${AAPT2:-/usr/local/bin/aapt2}
ANDROID_JAR=${ANDROID_JAR:-/root/build/sdk/platforms/android-36/android.jar}
D8_JAR=${D8_JAR:-/root/build/sdk/build-tools/36.0.0/lib/d8.jar}
D8_CLASS=com.android.tools.r8.D8

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex"

echo "[1/5] javac"
javac -source 8 -target 8 -nowarn \
    -classpath "$ANDROID_JAR:$TOOLS/libxposed-api.jar" \
    -d "$BUILD/classes" \
    $(find "$ROOT/src" -name '*.java')

echo "[2/5] d8 -> classes.dex"
java -cp "$D8_JAR" "$D8_CLASS" \
    --lib "$ANDROID_JAR" --min-api 29 \
    --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name '*.class')

echo "[3/5] aapt2 compile"
"$AAPT2" compile --dir "$ROOT/res" -o "$BUILD/res.zip"

echo "[4/5] aapt2 link"
"$AAPT2" link \
    -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/AndroidManifest.xml" \
    "$BUILD/res.zip"

echo "[5/5] pack"
python3 "$TOOLS/pack.py" "$BUILD/base.apk" "$BUILD/MagicVol-unsigned.apk" \
    "$BUILD/dex/classes.dex" "$ROOT/meta"

if [ "$1" = "--sign" ]; then
    echo "[sign] apksigner (debug keystore, 仅供自测)"
    cp "$BUILD/MagicVol-unsigned.apk" "$BUILD/MagicVol-test.apk"
    apksigner sign \
        --ks "$TOOLS/debug.keystore" --ks-pass pass:android --key-pass pass:android \
        --ks-key-alias androiddebugkey --min-sdk-version 29 \
        --v1-signing-enabled true --v2-signing-enabled true \
        "$BUILD/MagicVol-test.apk"
    ls -l "$BUILD/MagicVol-test.apk"
fi
ls -l "$BUILD/MagicVol-unsigned.apk"
echo "OK"
