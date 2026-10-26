#!/usr/bin/env bash
# Ядро ByeDPI (обход DPI без сервера) под Android.
# Результат: app/src/main/jniLibs/<abi>/libbyedpi.so — программа, приложение запускает её
# отдельным процессом (только из папки библиотек Android разрешает запуск программ).
# Это локальный SOCKS-прокси: соединение идёт напрямую к сайту, но форма пакетов меняется,
# чтобы DPI (ТСПУ) не узнал протокол. Своего сервера и ключей не нужно.
# Без этого файла приложение работает через Psiphon и Tor.
#
# Исходники — git-подмодуль app/src/main/jni/byedpi (hufrea/byedpi, MIT). Нужен Android NDK
# (ANDROID_NDK_HOME или ANDROID_NDK_LATEST_HOME; на раннерах GitHub они уже заданы).
set -euo pipefail

API=24
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/jni/byedpi"
OUT="$ROOT/app/src/main/jniLibs"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "Android NDK не найден: задайте ANDROID_NDK_HOME" >&2
    exit 1
fi
if [ ! -f "$SRC/main.c" ]; then
    echo "Нет исходников byedpi в $SRC — проверьте, что подмодуль выгружен (git submodule update --init)" >&2
    exit 1
fi
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

# Копия подмодуля с правками ODA (scripts/byedpi.patch: --udp-drop, ::ffff: в списке сетей)
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SRCS="$("$ROOT/scripts/byedpi-src.sh" "$WORK")"
SRC="$WORK"

build() {
    local abi="$1" cc="$2"
    mkdir -p "$OUT/$abi"
    # PIE-исполняемый файл; выравнивание страниц 16 КБ для новых устройств
    "$CLANG/$cc" -std=c99 -O2 -D_DEFAULT_SOURCE -I"$SRC" \
        -Wno-unused -Wno-unused-parameter \
        -fPIE -pie -s \
        -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
        -o "$OUT/$abi/libbyedpi.so" $SRCS
    echo "$abi: $(du -h "$OUT/$abi/libbyedpi.so" | cut -f1)"
}

build arm64-v8a "aarch64-linux-android$API-clang"
build armeabi-v7a "armv7a-linux-androideabi$API-clang"
