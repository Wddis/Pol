#!/usr/bin/env bash
# Клиент Psiphon (psiphon-tunnel-core, ConsoleClient) под Android и список его серверов.
# Результат:
#   app/src/main/jniLibs/<abi>/libpsiphon.so — программа, приложение запускает её само
#     (только из папки библиотек Android разрешает запуск программ);
#   app/src/main/assets/psiphon/server_list.txt — свежий публичный список серверов Psiphon
#     (подпись проверяется ключом Psiphon), чтобы первое подключение не зависело от загрузки.
# Без этих файлов приложение работает только через Tor.
#
# Нужны git, Go (нужная версия скачается сама, GOTOOLCHAIN=auto) и Android NDK
# (ANDROID_NDK_HOME или ANDROID_NDK_LATEST_HOME, на раннерах GitHub они уже заданы).
set -euo pipefail

COMMIT="${PSIPHON_COMMIT:-b35028d7fde45fc2e42e66040c31ae582989b602}"
REPO="https://github.com/Psiphon-Labs/psiphon-tunnel-core.git"
# Публичный список серверов Psiphon и ключ его подписи (те же значения в PsiphonController)
SERVER_LIST_URL="https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed"
SERVER_LIST_KEY="MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="
API=24

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs"
ASSETS="$ROOT/app/src/main/assets/psiphon"
SRC="${PSIPHON_SRC:-$ROOT/build/psiphon-tunnel-core}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "Android NDK не найден: задайте ANDROID_NDK_HOME" >&2
    exit 1
fi
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

export GOTOOLCHAIN=auto
export GOFLAGS=-modcacherw

# Исходники: точный коммит (зависимости лежат в репозитории, в vendor/)
if [ ! -d "$SRC/.git" ]; then
    git init -q "$SRC"
    git -C "$SRC" remote add origin "$REPO"
fi
if [ "$(git -C "$SRC" rev-parse -q --verify HEAD 2>/dev/null || true)" != "$COMMIT" ]; then
    git -C "$SRC" fetch -q --depth 1 origin "$COMMIT"
    git -C "$SRC" checkout -q --detach FETCH_HEAD
fi
echo "Psiphon $(git -C "$SRC" rev-parse --short=10 HEAD): $SRC"

build() {
    local abi="$1" goarch="$2" cc="$3"
    mkdir -p "$OUT/$abi"
    # netcgo: DNS через системный резолвер Android (у чистого Go на Android нет /etc/resolv.conf)
    # -checklinkname=0: нужно библиотеке wlynxg/anet (так же собирается Psiphon для Android)
    (cd "$SRC" && CGO_ENABLED=1 GOOS=android GOARCH="$goarch" GOARM=7 CC="$CLANG/$cc" \
        go build -trimpath -buildvcs=false -tags netcgo \
        -ldflags="-s -w -checklinkname=0 \
            -X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.buildRev=$COMMIT" \
        -o "$OUT/$abi/libpsiphon.so" ./ConsoleClient)
    echo "$abi: $(du -h "$OUT/$abi/libpsiphon.so" | cut -f1)"
}

# ODA_ABIS — какие ABI собрать (по умолчанию ARM для телефонов; x86_64 — только для эмулятора в CI)
for abi in ${ODA_ABIS:-arm64-v8a armeabi-v7a}; do
    case "$abi" in
        arm64-v8a) build arm64-v8a arm64 "aarch64-linux-android$API-clang" ;;
        armeabi-v7a) build armeabi-v7a arm "armv7a-linux-androideabi$API-clang" ;;
        x86_64) build x86_64 amd64 "x86_64-linux-android$API-clang" ;;
    esac
done

# Список серверов: подпись проверяется тем же кодом, что в самом Psiphon
TOOL="$SRC/oda-serverlist"
mkdir -p "$TOOL"
cat > "$TOOL/main.go" <<'EOF'
// Проверка подписи публичного списка серверов Psiphon; на выход — сам список.
package main

import (
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common"
)

func main() {
	data, err := io.ReadAll(os.Stdin)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	list, err := common.ReadAuthenticatedDataPackage(data, true, os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, "signature:", err)
		os.Exit(1)
	}
	list = strings.TrimSpace(list)
	if list == "" {
		fmt.Fprintln(os.Stderr, "empty server list")
		os.Exit(1)
	}
	fmt.Println(list)
}
EOF
mkdir -p "$ASSETS"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
curl -fsSL --retry 4 --retry-delay 2 -o "$TMP" "$SERVER_LIST_URL"
(cd "$SRC" && go run ./oda-serverlist "$SERVER_LIST_KEY") < "$TMP" > "$ASSETS/server_list.txt"
echo "server_list.txt: $(wc -l < "$ASSETS/server_list.txt") серверов"
