#!/bin/bash
# Builds libnest_preload.so for arm64-v8a and armeabi-v7a with the Android NDK (for the bench).
# usage: BENCH=/path NDK=/path/to/ndk/27.2.12479018 CMAKE_BIN=/path/to/sdk/cmake/3.22.1/bin build-preloads.sh
set -eu
SRC=$(cd "$(dirname "$0")/../.." && pwd)
for abi in arm64-v8a armeabi-v7a; do
  extra=""; [ $abi = armeabi-v7a ] && extra="-DNEST_PRELOAD_ONLY=ON"
  "$CMAKE_BIN/cmake" -S "$SRC" -B "$BENCH/ndk-$abi" -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=$abi \
    -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release $extra
  "$CMAKE_BIN/ninja" -C "$BENCH/ndk-$abi" nest_preload
done
