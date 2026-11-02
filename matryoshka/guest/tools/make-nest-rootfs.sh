#!/usr/bin/env bash
# Builds the guest root filesystem for the Nest (userspace) engine from the open-source
# LineageOS 18.1 (Android 11) arm64 images published by the Waydroid project.
#
# No root needed: ext4 images are unpacked with debugfs. Output: a .tar.gz the app downloads
# and unpacks into its private storage, plus manifest.json (sizes, sha256).
#
# Trimming is deliberately conservative (no functionality removed for apps):
#   - VNDK v28/v29 snapshots: the bundled vendor uses VNDK 30 (ro.vndk.version=30)   ~210 MB
#   - Waydroid desktop-integration packages that make no sense inside Matryoshka
#   - Developer/diagnostic extras and apps for hardware a virtual phone does not have (NFC)
# WebView, fonts, telephony/contacts providers, keyboard, settings etc. stay untouched.
#
# usage: make-nest-rootfs.sh [--from-dirs SYSTEM_ROOT VENDOR_ROOT] OUT_DIR
#        make-nest-rootfs.sh OUT_DIR            (downloads the images itself)
set -euo pipefail

SYSTEM_URL="${SYSTEM_URL:-https://sourceforge.net/projects/waydroid/files/images/system/lineage/waydroid_arm64/lineage-18.1-20250628-VANILLA-waydroid_arm64-system.zip/download}"
VENDOR_URL="${VENDOR_URL:-https://sourceforge.net/projects/waydroid/files/images/vendor/waydroid_arm64/lineage-18.1-20260402-HALIUM_11-waydroid_arm64-vendor.zip/download}"
IMAGE_ID="${IMAGE_ID:-nest-a11-lineage18.1-$(date +%Y%m%d)}"

SYS_DIR=""; VEN_DIR=""
if [ "${1:-}" = "--from-dirs" ]; then SYS_DIR="$2"; VEN_DIR="$3"; shift 3; fi
OUT="${1:?usage: $0 [--from-dirs SYSTEM_ROOT VENDOR_ROOT] OUT_DIR}"
mkdir -p "$OUT"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ -z "$SYS_DIR" ]; then
    echo "== downloading images"
    curl -fsSL -o "$WORK/system.zip" "$SYSTEM_URL"
    curl -fsSL -o "$WORK/vendor.zip" "$VENDOR_URL"
    (cd "$WORK" && unzip -q system.zip && unzip -q vendor.zip)
    mkdir -p "$WORK/sys" "$WORK/ven"
    debugfs -R "rdump / $WORK/sys" "$WORK/system.img" >/dev/null 2>&1
    debugfs -R "rdump / $WORK/ven" "$WORK/vendor.img" >/dev/null 2>&1
    rm -f "$WORK"/*.img "$WORK"/*.zip
    SYS_DIR="$WORK/sys"; VEN_DIR="$WORK/ven"
fi

ROOT="$WORK/rootfs"
echo "== assembling rootfs"
mkdir -p "$ROOT"
cp -a "$SYS_DIR/." "$ROOT/"
rm -rf "$ROOT/vendor" "$ROOT/lost+found"
mkdir -p "$ROOT/vendor"
cp -a "$VEN_DIR/." "$ROOT/vendor/"
rm -rf "$ROOT/vendor/lost+found"
BEFORE=$(du -sb "$ROOT" | cut -f1)

echo "== trimming"
S="$ROOT/system"
remove() { for p in "$@"; do [ -e "$S/$p" ] && { echo "   - $p ($(du -sh "$S/$p" | cut -f1))"; rm -rf "${S:?}/${p:?}"; }; done; return 0; }
remove system_ext/apex/com.android.vndk.v28 system_ext/apex/com.android.vndk.v29
remove system_ext/priv-app/WaydroidUpdater app/BoringdroidSystemUIApk
remove app/EasterEgg app/Traceur app/NfcNci app/SecureElement app/BasicDreams product/app/PhotoTable
remove priv-app/Seedvault priv-app/DynamicSystemInstallationService

# Mount points the Nest engine provides; /mvm is the host hand-off directory.
mkdir -p "$ROOT/mvm" "$ROOT/data" "$ROOT/dev" "$ROOT/proc" "$ROOT/sys" "$ROOT/apex" "$ROOT/linkerconfig"
AFTER=$(du -sb "$ROOT" | cut -f1)

echo "== packing"
TAR="$OUT/$IMAGE_ID.tar.gz"
tar --numeric-owner --owner=0 --group=0 -C "$ROOT" -czf "$TAR" .
SHA=$(sha256sum "$TAR" | cut -d' ' -f1)
SIZE=$(stat -c %s "$TAR")
cat > "$OUT/manifest.json" <<JSON
{
  "id": "$IMAGE_ID",
  "name": "Android 11 (LineageOS 18.1) for Nest",
  "androidVersion": "11",
  "abi": "arm64-v8a",
  "engines": ["NEST"],
  "files": [{"name": "rootfs.tar.gz", "sha256": "$SHA", "size": $SIZE}],
  "installedSize": $AFTER,
  "description": "Unmodified LineageOS 18.1 userspace (Waydroid arm64 build), trimmed of host-integration and diagnostic extras.",
  "licenseNotice": "LineageOS / AOSP: Apache-2.0 and others. Built from the Waydroid project's public images; source: github.com/LineageOS, github.com/waydroid."
}
JSON
printf '== done\n   unpacked: %s MB -> %s MB\n   archive:  %s MB  %s\n' \
    $((BEFORE / 1048576)) $((AFTER / 1048576)) $((SIZE / 1048576)) "$TAR"
