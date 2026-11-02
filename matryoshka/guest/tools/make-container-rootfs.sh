#!/usr/bin/env bash
# Turns system.img + vendor.img into the directory tree used by the CONTAINER engine:
#   /            <- root of system.img (system-as-root layout)
#   /vendor      <- vendor.img
#   /mvm         <- empty mount point for the host hand-off share
# Ownership and modes are preserved (tar --numeric-owner); SELinux labels are not needed because
# the container runs the guest with SELinux "disabled" from its own point of view.
#
# usage: make-container-rootfs.sh <system.img> <vendor.img> <out.tar.gz>   (needs root for mount)
set -euo pipefail
SYSTEM="$1"; VENDOR="$2"; OUT="$3"
WORK="$(mktemp -d)"
cleanup() { umount "$WORK/vendor-mnt" 2>/dev/null || true; umount "$WORK/system-mnt" 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT

unsparse() { # images may be Android sparse
    if file "$1" | grep -q "Android sparse"; then simg2img "$1" "$2"; else cp --reflink=auto "$1" "$2"; fi
}
unsparse "$SYSTEM" "$WORK/system.raw"
unsparse "$VENDOR" "$WORK/vendor.raw"
mkdir -p "$WORK/system-mnt" "$WORK/vendor-mnt" "$WORK/root"
mount -o loop,ro "$WORK/system.raw" "$WORK/system-mnt"
mount -o loop,ro "$WORK/vendor.raw" "$WORK/vendor-mnt"

cp -a "$WORK/system-mnt/." "$WORK/root/"
rm -rf "$WORK/root/vendor"; mkdir -p "$WORK/root/vendor"
cp -a "$WORK/vendor-mnt/." "$WORK/root/vendor/"
mkdir -p "$WORK/root/mvm" "$WORK/root/data" "$WORK/root/dev" "$WORK/root/proc" "$WORK/root/sys" "$WORK/root/mnt"
# Container fstab: nothing to mount, the host prepared everything.
: > "$WORK/root/vendor/etc/fstab.mvm"

tar --numeric-owner -C "$WORK/root" -czpf "$OUT" .
echo "rootfs: $OUT ($(du -h "$OUT" | cut -f1))"
