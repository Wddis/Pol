#!/usr/bin/env bash
# Лицензии ключей на компьютере: Ed25519 и License.java против кода подписи сервера ключей
# (bot/worker/src/license.ts) и векторов RFC 8032. Нужны JDK 17+ и Node 20+ (npm ci в bot/worker).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
W="$ROOT/bot/worker"
[ -d "$W/node_modules" ] || (cd "$W" && npm ci --no-audit --no-fund >/dev/null)
"$W/node_modules/.bin/esbuild" "$W/src/license.ts" --bundle --format=esm --platform=node --outfile="$TMP/license.mjs" --log-level=error
INPUT=$(node "$ROOT/scripts/license/sign.mjs" "$TMP/license.mjs")
SRC="$ROOT/app/src/main/java/com/example/vpnapp"
javac -encoding UTF-8 -d "$TMP/classes" "$SRC/Ed25519.java" "$SRC/License.java" "$ROOT/scripts/license/LicenseTest.java"
java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$TMP/classes" com.example.vpnapp.LicenseTest "$INPUT"
