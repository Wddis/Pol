#!/bin/bash
# Local bench: boots the real Android 11 guest init under Nest on an x86_64 Linux host, with
# qemu-user-static (binfmt) standing in for the phone's arm64 CPU. Used to find engine bugs
# without a phone. See docs/CONTEXT.md, section "Стенд".
#
# usage: BENCH=/path/to/workdir T=300 [V_FLAGS="-v -v"] [NEST_BINDER_LOG=/tmp/binder.txt] boot-guest.sh
#
# BENCH must contain:
#   rootfs/            guest root (unpacked release asset nest-a11-lineage18.1.tar.gz, it has /vendor)
#   nestbuild/nest_run host (x86_64) build of the engine:  cmake -S nest -B $BENCH/nestbuild && make -C ... nest_run
#   ndk-arm64-v8a/libnest_preload.so, ndk-armeabi-v7a/libnest_preload.so   (see build-preloads.sh)
#
# Output: $ST/kmsg (init boot log), $ST/logcat.txt (guest logcat copy), /tmp/nst-out.txt (engine).
#
# The identity binds (-b X=X) absorb qemu-user artefacts: qemu opens host paths itself
# (/proc/self/exe, its own realpath of the program), so those host paths must resolve to themselves.
set -u
BENCH=${BENCH:?set BENCH}
R=$BENCH/rootfs; B=$BENCH/nestbuild; DATA=$BENCH/vmdata; ST=${ST:-/tmp/nst}
rm -rf "$ST" "$DATA"; mkdir -p "$ST" "$DATA"
cd /tmp
timeout "${T:-90}" "$B/nest_run" \
  -b /="$R" -b "$R=$R" -b /data="$DATA" -b "$DATA=$DATA" -b "$ST=$ST" -b "$BENCH=$BENCH" \
  --android "$ST" \
  -P "$BENCH/ndk-arm64-v8a/libnest_preload.so" -P32 "$BENCH/ndk-armeabi-v7a/libnest_preload.so" \
  -B "$ST/binder.sock" ${V_FLAGS:--v} -s -- /system/bin/init second_stage > /tmp/nst-out.txt 2>&1
echo "exit $?"; grep "^nest:" /tmp/nst-out.txt
echo "kmsg: $ST/kmsg ($(wc -l < "$ST/kmsg") lines), logcat: $ST/logcat.txt ($(wc -l < "$ST/logcat.txt") lines)"
