package dev.matryoshka.vm.core.model

import kotlinx.serialization.Serializable

/**
 * How the guest Android 11 system is executed on the host phone.
 *
 * All three engines boot the *same* guest system (an AOSP-11 based image built from
 * `guest/`), they only differ in the isolation primitive used:
 *
 *  - [HYPERVISOR]: a real virtual machine with its own kernel, using KVM (pKVM / Gunyah)
 *    through crosvm. Strongest isolation, needs root (or a privileged install) and a host
 *    kernel that exposes /dev/kvm.
 *  - [CONTAINER]: the guest userspace runs on the host kernel inside dedicated Linux
 *    namespaces with its own binderfs instance. Near-native speed, needs root and binderfs.
 *  - [EMULATOR]: full-system emulation (QEMU TCG). Works without root on any 64-bit device
 *    but is an order of magnitude slower. Fallback only.
 */
@Serializable
enum class EngineType { HYPERVISOR, CONTAINER, EMULATOR }

/** Which Google-services layer is provisioned in the guest. */
@Serializable
enum class GmsMode {
    /** Plain AOSP, no Google services. */
    NONE,

    /** microG (Apache-2.0): free re-implementation of Play Services APIs. Installed by the app. */
    MICROG,

    /**
     * The user provides a Google Apps package themselves. Google's apps are proprietary and are
     * never downloaded or redistributed by Matryoshka; the user is responsible for their licence.
     */
    USER_PROVIDED_GAPPS,
}

@Serializable
enum class GpuMode {
    /** Pick the fastest working mode for this host. */
    AUTO,

    /** virtio-gpu with host-side rendering (virglrenderer/gfxstream) when crosvm supports it. */
    HOST_ACCELERATED,

    /** Guest-side software rendering (SwiftShader). Slowest but always available. */
    SOFTWARE,
}

@Serializable
data class DisplayConfig(
    val width: Int = 1080,
    val height: Int = 2340,
    val dpi: Int = 420,
    val maxFps: Int = 60,
    /** H.264 bit rate of the guest->host screen stream, in bits per second. */
    val bitRate: Int = 8_000_000,
)

@Serializable
data class VmConfig(
    val id: String,
    val name: String,
    val engine: EngineType,
    val imageId: String,
    val memoryMb: Int = 3072,
    val cpuCores: Int = 4,
    val storageGb: Int = 16,
    val display: DisplayConfig = DisplayConfig(),
    val gpu: GpuMode = GpuMode.AUTO,
    val gms: GmsMode = GmsMode.MICROG,
    /** Install Magisk into the guest boot image on first boot (user-initiated download). */
    val magisk: Boolean = true,
    /** Index of this VM's private /30 network (10.77.<netIndex>.0/30). */
    val netIndex: Int = 1,
    /** Keep running when the UI is closed (foreground service). */
    val keepAliveInBackground: Boolean = true,
    val accentColor: Long = 0xFF7C5CFF,
    val createdAt: Long = System.currentTimeMillis(),
    val lastBootAt: Long? = null,
) {
    val hostIp: String get() = "10.77.$netIndex.1"
    val guestIp: String get() = "10.77.$netIndex.2"
    val shortId: String get() = id.take(8)
}

sealed interface VmState {
    data object Stopped : VmState
    data class Preparing(val step: String, val progress: Float? = null) : VmState
    data class Booting(val step: String) : VmState
    data class Running(val since: Long) : VmState
    data object Stopping : VmState
    data class Failed(val message: String, val log: String? = null) : VmState

    val isActive: Boolean get() = this is Preparing || this is Booting || this is Running || this is Stopping
}
