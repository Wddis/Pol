#!/usr/bin/env bash
# Клиент Snowflake (Tor Project) с маскировкой DTLS-рукопожатия (covert-dtls) под Android.
# Результат: app/src/main/jniLibs/<abi>/libsnowflake.so — Tor запускает его сам
# (ClientTransportPlugin snowflake exec). Без этого файла приложение берёт Snowflake из IPtProxy.
#
# Нужны Go (нужная версия скачается сама, GOTOOLCHAIN=auto) и Android NDK
# (ANDROID_NDK_HOME или ANDROID_NDK_LATEST_HOME, на раннерах GitHub они уже заданы).
set -euo pipefail

VERSION="${SNOWFLAKE_VERSION:-v2.15.2}"
MODULE="gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2"
API=24

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "Android NDK не найден: задайте ANDROID_NDK_HOME" >&2
    exit 1
fi
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

export GOTOOLCHAIN=auto
export GOFLAGS=-modcacherw

# Исходники берутся из прокси модулей Go, контрольная сумма сверяется с sum.golang.org
SRC="$(go mod download -json "$MODULE@$VERSION" | sed -n 's/^[[:space:]]*"Dir": "\(.*\)",$/\1/p')"
if [ -z "$SRC" ] || [ ! -f "$SRC/client/snowflake.go" ]; then
    echo "Не удалось скачать $MODULE@$VERSION" >&2
    exit 1
fi
echo "Snowflake $VERSION: $SRC"

build() {
    local abi="$1" goarch="$2" cc="$3"
    mkdir -p "$OUT/$abi"
    # netcgo: DNS через системный резолвер Android (у чистого Go на Android нет /etc/resolv.conf)
    # -checklinkname=0: нужно библиотеке wlynxg/anet (так же собирается IPtProxy)
    (cd "$SRC" && CGO_ENABLED=1 GOOS=android GOARCH="$goarch" GOARM=7 CC="$CLANG/$cc" \
        go build -trimpath -buildvcs=false -tags netcgo \
        -ldflags="-s -w -checklinkname=0" \
        -o "$OUT/$abi/libsnowflake.so" ./client)
    echo "$abi: $(du -h "$OUT/$abi/libsnowflake.so" | cut -f1)"
}

build arm64-v8a arm64 "aarch64-linux-android$API-clang"
build armeabi-v7a arm "armv7a-linux-androideabi$API-clang"
