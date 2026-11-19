#!/usr/bin/env bash
# Сборка модуля LSPatch/Xposed: javac(стабы+модуль) -> d8 -> aapt2 -> zipalign -> apksigner.
# Требует Android SDK. Запуск: ANDROID_SDK_ROOT=/opt/android-sdk ./build.sh
set -e
cd "$(dirname "$0")"
unset _JAVA_OPTIONS JAVA_TOOL_OPTIONS            # иначе d8/javac могут падать (см. SESSION_LOG)

SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
AJ="$SDK/platforms/android-34/android.jar"
BT="$(ls -d "$SDK"/build-tools/* | sort | tail -1)"
echo "SDK=$SDK  build-tools=$BT"

rm -rf build && mkdir -p build/stubs build/classes

echo "== javac: стабы Xposed (только для компиляции) =="
javac -source 8 -target 8 -nowarn -cp "$AJ" -d build/stubs $(find src/de -name '*.java')

echo "== javac: модуль =="
javac -source 8 -target 8 -nowarn -bootclasspath "$AJ" -cp build/stubs -d build/classes \
  $(find src/com -name '*.java') 2>&1 | grep -v 'bootstrap class path' || true

echo "== d8: только классы модуля -> classes.dex =="
"$BT/d8" --min-api 24 --lib "$AJ" --output build/ $(find build/classes -name '*.class')

echo "== aapt2 link =="
LIBARG=""
if ls libs/arm64-v8a/libfarmerv8.so >/dev/null 2>&1; then
  mkdir -p build/lib/arm64-v8a build/assets
  cp libs/arm64-v8a/libfarmerv8.so build/lib/arm64-v8a/
  cp libs/arm64-v8a/libfarmerv8.so build/assets/        # запасной путь для System.load
  cp assets/xposed_init build/assets/ 2>/dev/null || true
  A_DIR="build/assets"
else
  A_DIR="assets"
fi
"$BT/aapt2" link -o build/base.apk -I "$AJ" --manifest AndroidManifest.xml -A "$A_DIR" \
  --min-sdk-version 24 --target-sdk-version 34 --version-code 8 --version-name 8.0

echo "== упаковка dex (+ .so если есть) =="
( cd build && zip -q base.apk classes.dex && [ -d lib ] && zip -qr base.apk lib || true )

echo "== keystore + sign =="
[ -f build/debug.ks ] || keytool -genkeypair -keystore build/debug.ks -storepass android \
  -keypass android -alias v8 -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=FarmerV8"
"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk
"$BT/apksigner" sign --ks build/debug.ks --ks-pass pass:android --key-pass pass:android \
  --out build/FarmerShoot-v8-LSPatch-module.apk build/aligned.apk
"$BT/apksigner" verify --print-certs build/FarmerShoot-v8-LSPatch-module.apk | head -1
echo "== готово: build/FarmerShoot-v8-LSPatch-module.apk =="
