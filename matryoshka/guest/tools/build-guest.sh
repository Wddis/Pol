#!/usr/bin/env bash
# Builds the Matryoshka Android 11 guest image from AOSP source.
#
# Requirements (AOSP's own): Linux x86_64 host, ~400 GB free disk, 32+ GB RAM, `repo`.
# Output: guest/out/<image-id>/ with kernel, ramdisk.img, boot.img, system.img, vendor.img,
#         rootfs.tar.gz and manifest.json, plus a .zip bundle the app can import.
#
# usage: guest/tools/build-guest.sh <aosp-dir> <kernel-Image>
set -euo pipefail

AOSP="${1:?usage: $0 <aosp-dir> <kernel-Image>}"
KERNEL="${2:?path to an arm64 kernel Image built with guest/kernel/mvm.config}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${AOSP_TAG:-android-11.0.0_r48}"
IMAGE_ID="${IMAGE_ID:-mvm-a11-arm64-$(date +%Y%m%d)}"

if [ ! -d "$AOSP/.repo" ]; then
    mkdir -p "$AOSP"
    (cd "$AOSP" && repo init -u https://android.googlesource.com/platform/manifest -b "$TAG" --depth=1 \
        && repo sync -c -j"$(nproc)" --no-tags)
fi

# Device tree lives in this repository; link it into the tree.
mkdir -p "$AOSP/device/matryoshka"
ln -sfn "$HERE/device/mvm" "$AOSP/device/matryoshka/mvm"
mkdir -p "$HERE/device/mvm/prebuilt"
cp "$KERNEL" "$HERE/device/mvm/prebuilt/Image"

python3 "$HERE/patches/apply_sigspoof.py" "$AOSP"

(
    cd "$AOSP"
    # shellcheck disable=SC1091
    source build/envsetup.sh
    lunch mvm_arm64-userdebug
    m -j"$(nproc)" bootimage systemimage vendorimage
)

PRODUCT_OUT="$AOSP/out/target/product/mvm"
OUT="$HERE/out/$IMAGE_ID"
mkdir -p "$OUT"
cp "$KERNEL" "$OUT/kernel"
cp "$PRODUCT_OUT/ramdisk.img" "$OUT/ramdisk.img"
cp "$PRODUCT_OUT/boot.img" "$OUT/boot.img"
cp "$PRODUCT_OUT/system.img" "$OUT/system.img"
cp "$PRODUCT_OUT/vendor.img" "$OUT/vendor.img"

"$HERE/tools/make-container-rootfs.sh" "$OUT/system.img" "$OUT/vendor.img" "$OUT/rootfs.tar.gz"

python3 "$HERE/tools/mkbundle.py" "$OUT" --id "$IMAGE_ID" \
    --name "Android 11 · Matryoshka build $(date +%Y-%m-%d)" --zip "$HERE/out/$IMAGE_ID.zip"

echo "Done: $OUT  and  $HERE/out/$IMAGE_ID.zip"
