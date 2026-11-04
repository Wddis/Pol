package dev.matryoshka.vm.core.host

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import dev.matryoshka.vm.core.model.EngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class Support { YES, NO, UNKNOWN }

data class Check(val title: String, val support: Support, val detail: String)

data class EngineAvailability(val engine: EngineType, val usable: Boolean, val reason: String)

data class HostReport(
    val device: String,
    val androidVersion: String,
    val kernel: String,
    val abi: String,
    val totalRamMb: Long,
    val freeStorageGb: Double,
    val cpuCores: Int,
    val checks: List<Check>,
    val engines: List<EngineAvailability>,
) {
    val recommended: EngineType?
        get() = engines.firstOrNull { it.usable }?.engine
}

/**
 * Detects which virtualization primitives this phone offers. Everything here is read-only;
 * nothing is changed on the host.
 */
class HostProbe(private val context: Context, private val root: RootShell) {

    suspend fun probe(askRoot: Boolean): HostReport = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val is64ArmHost = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")
        val kernel = runCatching { File("/proc/version").readText().trim() }.getOrDefault(System.getProperty("os.version").orEmpty())
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(context.filesDir.absolutePath)

        val suPresent = root.suBinaryPresent()
        val rootGranted = if (askRoot && suPresent) root.isGranted() else false

        val filesystems = runCatching { File("/proc/filesystems").readText() }.getOrDefault("")
        val binderfs = when {
            filesystems.contains("binder") -> Support.YES
            rootGranted && root.run("grep -q binder /proc/filesystems").ok -> Support.YES
            filesystems.isNotEmpty() -> Support.NO
            else -> Support.UNKNOWN
        }

        // /dev/kvm is not world-readable on most phones; ask root for the real answer.
        val kvm = when {
            File("/dev/kvm").exists() -> Support.YES
            rootGranted -> if (root.run("test -c /dev/kvm").ok) Support.YES else Support.NO
            else -> Support.UNKNOWN
        }
        val systemCrosvm = File(CROSVM_APEX).exists() ||
            (rootGranted && root.run("test -x $CROSVM_APEX").ok)
        val avfFeature = Build.VERSION.SDK_INT >= 34 &&
            context.packageManager.hasSystemFeature("android.software.virtualization_framework")
        val bundledCrosvm = File(context.filesDir, "engines/crosvm/bin/crosvm").exists()
        val qemuPack = dev.matryoshka.vm.core.engine.EmulatorEngine.qemuBinaryIn(context.packageManager) != null
        val namespaces = File("/proc/self/ns/pid").exists() && File("/proc/self/ns/mnt").exists()
        val selinux = runCatching { File("/sys/fs/selinux/enforce").readText().trim() }.getOrNull()

        val checks = listOf(
            Check("64-bit ARM host", if (is64ArmHost) Support.YES else Support.NO, abi),
            Check("Root access", if (rootGranted) Support.YES else if (suPresent) Support.UNKNOWN else Support.NO,
                when {
                    rootGranted -> "su granted"
                    suPresent -> "su found, permission not granted yet"
                    else -> "no su binary (Magisk / KernelSU / APatch not installed)"
                }),
            Check("Hardware virtualization (/dev/kvm)", kvm,
                if (kvm == Support.YES) "KVM available (pKVM/Gunyah/EL2)" else "No KVM device exposed by the host kernel"),
            Check("Android Virtualization Framework", if (avfFeature) Support.YES else Support.NO,
                if (avfFeature) "android.software.virtualization_framework" else "Not declared (needs Android 14+ with pKVM)"),
            Check("crosvm", if (systemCrosvm || bundledCrosvm) Support.YES else Support.NO,
                when {
                    systemCrosvm -> CROSVM_APEX
                    bundledCrosvm -> "engine pack"
                    else -> "not found — install the crosvm engine pack"
                }),
            Check("binderfs (for container engine)", binderfs,
                if (binderfs == Support.YES) "CONFIG_ANDROID_BINDERFS=y" else "Kernel lacks binderfs"),
            Check("Linux namespaces", if (namespaces) Support.YES else Support.NO, "mnt / pid / net / ipc / uts"),
            Check("SELinux", Support.UNKNOWN, selinux?.let { if (it == "1") "enforcing" else "permissive" } ?: "status hidden"),
            Check("QEMU engine pack", if (qemuPack) Support.YES else Support.NO,
                if (qemuPack) "installed" else "optional download"),
        )

        val engines = listOf(
            EngineAvailability(
                EngineType.NEST,
                usable = is64ArmHost && Build.VERSION.SDK_INT >= 26,
                reason = when {
                    !is64ArmHost -> "Requires an arm64 phone"
                    Build.VERSION.SDK_INT < 26 -> "Requires Android 8 or newer"
                    else -> "No root needed, native speed"
                },
            ),
            EngineAvailability(
                EngineType.HYPERVISOR,
                usable = is64ArmHost && rootGranted && kvm == Support.YES && (systemCrosvm || bundledCrosvm),
                reason = when {
                    !is64ArmHost -> "Requires an arm64 host"
                    !rootGranted -> "Requires root (or privileged install for AVF)"
                    kvm != Support.YES -> "Host kernel does not expose /dev/kvm"
                    !(systemCrosvm || bundledCrosvm) -> "crosvm not found"
                    else -> "Real VM with its own kernel, hardware-accelerated"
                },
            ),
            EngineAvailability(
                EngineType.CONTAINER,
                usable = is64ArmHost && rootGranted && binderfs == Support.YES && namespaces,
                reason = when {
                    !is64ArmHost -> "Requires an arm64 host"
                    !rootGranted -> "Requires root"
                    binderfs != Support.YES -> "Host kernel lacks binderfs"
                    else -> "Isolated namespaces on the host kernel, near-native speed"
                },
            ),
            EngineAvailability(
                EngineType.EMULATOR,
                usable = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && qemuPack,
                reason = if (qemuPack) "Software emulation, works without root (slow)"
                else "Download the QEMU engine pack to enable (no root needed)",
            ),
        )

        HostReport(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            kernel = kernel.substringBefore(" (").removePrefix("Linux version "),
            abi = abi,
            totalRamMb = mem.totalMem / (1024 * 1024),
            freeStorageGb = stat.availableBytes / 1e9,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            checks = checks,
            engines = engines,
        )
    }

    companion object {
        const val CROSVM_APEX = "/apex/com.android.virt/bin/crosvm"
    }
}
