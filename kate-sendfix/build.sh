#!/usr/bin/env bash
# Rebuilds Kate Mobile 133.7 lite with the send-token patch.
# Usage: ./build.sh <Kate_Mobile_133.7_lite.apk> [work_dir]
# Needs: java 11+, curl, patch, python3 (for tests: javac).
set -euo pipefail

APK=${1:?usage: build.sh <Kate_Mobile_133.7_lite.apk> [work_dir]}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(realpath -m "${2:-$HERE/work}")
EXPECTED_SHA=1abe2b86fd0955596ecd15447946b285a60ead91f3dd8765f45425a29bcd49b4
mkdir -p "$WORK/tools"

sha=$(sha256sum "$APK" | cut -d' ' -f1)
if [ "$sha" != "$EXPECTED_SHA" ]; then
    echo "WARNING: $APK is not the 133.7 lite build the patch was made for (sha256 $sha)" >&2
fi

fetch() { [ -s "$WORK/tools/$1" ] || curl -sSL -o "$WORK/tools/$1" "$2"; }
fetch apktool.jar https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar
fetch uber-apk-signer.jar https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar

echo "== logic tests"
mkdir -p "$WORK/test-classes"
javac -nowarn -encoding UTF-8 -d "$WORK/test-classes" $(find "$HERE/test" -name '*.java') "$HERE/src/KateSendFix.java"
java -Dfile.encoding=UTF-8 -cp "$WORK/test-classes" SendFixTest

echo "== decode"
java -jar "$WORK/tools/apktool.jar" d -f -o "$WORK/decoded" "$APK"

echo "== patch"
(cd "$WORK/decoded" && patch -p1 --forward < "$HERE/patches/kate-133.7-lite-sendfix.diff")

echo "== build"
java -jar "$WORK/tools/apktool.jar" b -o "$WORK/kate-sendfix-unsigned.apk" "$WORK/decoded"

echo "== sign"
KS="$WORK/sendfix.jks"
if [ ! -s "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias sendfix -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass sendfix123 -keypass sendfix123 -dname "CN=Kate sendfix"
fi
java -jar "$WORK/tools/uber-apk-signer.jar" -a "$WORK/kate-sendfix-unsigned.apk" \
    --ks "$KS" --ksAlias sendfix --ksPass sendfix123 --ksKeyPass sendfix123 -o "$WORK/out"
ls -la "$WORK/out"
