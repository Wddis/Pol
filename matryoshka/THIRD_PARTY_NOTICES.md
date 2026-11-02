# Third-party components

Every component was checked for licence compatibility with Matryoshka's Apache-2.0 licence
*before* integration. "How" states whether it is shipped in the APK.

| Component | Licence | How it is used | Obligations met |
|---|---|---|---|
| scrcpy server 2.7 (Genymobile) | Apache-2.0 | **Bundled unmodified** as `assets/scrcpy-server.jar`; pushed into the guest to capture its screen | Licence + notice reproduced here and in the app's About screen; no trademark use |
| AndroidX / Jetpack Compose / Material 3 / Material Icons | Apache-2.0 | Linked libraries | Notices in About screen |
| Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 | Linked libraries | Notices in About screen |
| Robolectric, Roborazzi, JUnit | MIT / Apache-2.0 / EPL-1.0 | **Tests only**, not in the APK | — |
| AOSP android-11 (guest) | Apache-2.0 (+ various per-project) | Built from source by `guest/`; distributed only as a separate image | Source location documented; our changes are in this repo |
| Linux kernel (guest) | GPL-2.0 | Separate guest image | Config fragment in `guest/kernel/`; corresponding source must accompany any published image |
| crosvm | BSD-3-Clause | Host's own AVF copy is executed; optional engine pack | Notice in About screen |
| QEMU | GPL-2.0 | **Not in this APK.** Optional separate engine-pack APK | Engine pack must ship/offer its complete source |
| Magisk | GPL-3.0 | **Not in this APK.** Downloaded by the user, on request, from the official GitHub release, installed into the guest | Licence shown before download; no modification, no redistribution by us |
| microG GmsCore | Apache-2.0 | **Not in this APK.** Downloaded on request from the official release | Licence shown before download |
| Google Play Services / Play Store | Proprietary | **Never downloaded or distributed** by Matryoshka | User-provided only |

ADB protocol client, display client, engine scripts, UI and the guest device configuration are
original Matryoshka code. The signature-spoofing patch is an independent implementation of the
approach used by microG (no code copied).

"Android" is a trademark of Google LLC; "scrcpy", "Magisk", "microG", "QEMU" belong to their
respective owners and are used only to name the components.
