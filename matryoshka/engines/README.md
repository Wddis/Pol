# Engine packs

Optional executables that are **not** part of the main APK, either for licence reasons (GPL) or
because most devices already have them.

## QEMU engine pack (`dev.matryoshka.engine.qemu`) — for the Emulator engine

* Licence: GPL-2.0, so it is a separate APK that must ship (or offer) its complete corresponding source.
* Android only lets apps execute code that comes from an APK, so the pack contains
  `lib/arm64-v8a/libqemu-system-aarch64.so` (the QEMU executable renamed) and its shared-library
  dependencies (glib, pixman, libslirp), with `android:extractNativeLibs="true"`.
* Build: cross-compile QEMU (`--target-list=aarch64-softmmu --disable-sdl --disable-gtk --enable-slirp
  --enable-virtfs`) with the Android NDK (API 26, arm64). The Termux package recipes for `qemu-system-*`
  are a good reference for the needed patches.
* Status: **not automated yet** (roadmap).

## crosvm engine pack — for the Hypervisor engine on devices without AVF

* Licence: BSD-3-Clause.
* Most pKVM devices (Pixel 6+, Android 13+) already ship `/apex/com.android.virt/bin/crosvm`, which
  Matryoshka uses directly. For other KVM-capable devices, place a static arm64 `crosvm` build at
  `files/engines/crosvm/bin/crosvm` (it is executed via root, so app-data W^X does not apply).
