package dev.matryoshka.vm.core.engine

import android.content.Context
import android.os.Process
import dev.matryoshka.vm.BuildConfig
import dev.matryoshka.vm.core.adb.AdbKey
import dev.matryoshka.vm.core.guest.EthernetIpConfig
import dev.matryoshka.vm.core.host.RootShell
import dev.matryoshka.vm.core.host.ShellResult
import dev.matryoshka.vm.core.host.shQuote
import dev.matryoshka.vm.core.image.GuestImage
import dev.matryoshka.vm.core.image.ImageManager
import dev.matryoshka.vm.core.model.EngineType
import dev.matryoshka.vm.core.model.VmConfig
import dev.matryoshka.vm.core.storage.VmRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** How the host reaches the guest and how the guest's eth0 is configured. */
data class NetPlan(
    val guestIp: String,
    val prefixLength: Int,
    val gateway: String,
    val dns: List<String>,
    val adbHost: String,
    val adbPort: Int,
)

class EngineException(message: String, val log: String? = null) : IOException(message)

interface VmEngine {
    val type: EngineType

    fun netPlan(vm: VmConfig): NetPlan

    /** One-time work before the first boot (extracting rootfs, creating disks). Idempotent. */
    suspend fun prepare(vm: VmConfig, image: GuestImage, log: (String) -> Unit)

    /** Launches the VM and returns once the engine process is up (the guest is still booting). */
    suspend fun start(vm: VmConfig, image: GuestImage)

    /** Hard stop; the caller asks the guest to shut down cleanly over ADB first. */
    suspend fun stop(vm: VmConfig)

    suspend fun isRunning(vm: VmConfig): Boolean

    /** Deletes engine-owned (possibly root-owned) state of [vm]. */
    suspend fun destroy(vm: VmConfig)

    /** Called when the host's default network changes. */
    suspend fun onHostNetworkChanged(vm: VmConfig) {}
}

/** Shared plumbing for the engines. */
class EngineEnvironment(
    val context: Context,
    val root: RootShell,
    val vms: VmRepository,
    val images: ImageManager,
    val adbKey: AdbKey,
    val hostDns: () -> List<String>,
) {
    val scriptsDir: File get() = File(context.filesDir, "engine")

    fun bootDir(vm: VmConfig) = File(vms.dirOf(vm.id), "boot")

    /** Copies the root scripts out of the APK whenever the app version changes. */
    fun installScripts() {
        val stamp = File(scriptsDir, ".version")
        val current = "${BuildConfig.VERSION_CODE}-${BuildConfig.VERSION_NAME}"
        if (!BuildConfig.DEBUG && stamp.exists() && stamp.readText() == current) return
        scriptsDir.mkdirs()
        context.assets.list("engine").orEmpty().forEach { name ->
            context.assets.open("engine/$name").use { input ->
                File(scriptsDir, name).outputStream().use { input.copyTo(it) }
            }
            File(scriptsDir, name).setExecutable(true, true)
        }
        stamp.writeText(current)
    }

    /**
     * Kernel + ramdisk for this VM. A Magisk-patched ramdisk (per VM) wins over the image's stock one.
     */
    fun kernelFor(vm: VmConfig): File =
        File(bootDir(vm), "kernel.magisk").takeIf { it.exists() } ?: images.file(vm.imageId, "kernel")

    fun ramdiskFor(vm: VmConfig): File =
        File(bootDir(vm), "ramdisk.magisk.img").takeIf { it.exists() } ?: images.file(vm.imageId, "ramdisk.img")

    /**
     * Files the guest picks up from /mvm on every boot (see guest/overlay/mvm-guest-setup.sh):
     * our ADB public key, the static network configuration and display properties.
     */
    fun writeGuestHandoff(vm: VmConfig, plan: NetPlan) {
        val share = vms.shareDir(vm.id).apply { mkdirs() }
        File(share, "adb_keys").writeText(adbKey.adbPublicKey() + "\n")
        File(share, "ipconfig.txt").writeBytes(
            EthernetIpConfig.encode(
                EthernetIpConfig.Plan(address = plan.guestIp, prefixLength = plan.prefixLength, gateway = plan.gateway, dns = plan.dns)
            )
        )
        File(share, "mvm.prop").writeText(
            buildString {
                appendLine("ro.mvm.engine=${vm.engine.name.lowercase()}")
                appendLine("ro.mvm.vm_id=${vm.id}")
                appendLine("ro.sf.lcd_density=${vm.display.dpi}")
                appendLine("ro.mvm.width=${vm.display.width}")
                appendLine("ro.mvm.height=${vm.display.height}")
                appendLine("ro.mvm.gms=${vm.gms.name.lowercase()}")
                appendLine("persist.mvm.shared_dir=/mvm/share")
            }
        )
        File(share, "share").mkdirs()
    }

    fun writeRunEnv(vm: VmConfig, image: GuestImage, extra: Map<String, String> = emptyMap()): File {
        val env = linkedMapOf(
            "MVM_ID" to vm.id,
            "MVM_DIR" to vms.dirOf(vm.id).absolutePath,
            "MVM_IMAGE_DIR" to images.dirOf(image.id).absolutePath,
            "MVM_DATA" to vms.dataDir(vm.id).absolutePath,
            "MVM_SHARE" to vms.shareDir(vm.id).absolutePath,
            "MVM_LOG_DIR" to vms.logDir(vm.id).absolutePath,
            "MVM_ENGINE_DIR" to scriptsDir.absolutePath,
            "MVM_MEM_MB" to vm.memoryMb.toString(),
            "MVM_CPUS" to vm.cpuCores.toString(),
            "MVM_STORAGE_GB" to vm.storageGb.toString(),
            "MVM_NET_INDEX" to vm.netIndex.toString(),
            "MVM_HOST_IP" to vm.hostIp,
            "MVM_GUEST_IP" to vm.guestIp,
            "MVM_APP_UID" to Process.myUid().toString(),
            "MVM_WIDTH" to vm.display.width.toString(),
            "MVM_HEIGHT" to vm.display.height.toString(),
            "MVM_DPI" to vm.display.dpi.toString(),
            "MVM_GPU" to vm.gpu.name,
            "MVM_KERNEL" to kernelFor(vm).absolutePath,
            "MVM_RAMDISK" to ramdiskFor(vm).absolutePath,
            "MVM_CMDLINE" to image.kernelCmdline,
            "MVM_CROSVM" to "",
        )
        env.putAll(extra)
        val file = File(vms.dirOf(vm.id), "run.env")
        file.writeText(env.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k=${v.shQuote()}" })
        return file
    }

    suspend fun script(name: String, action: String, runEnv: File, timeoutMs: Long = 180_000): ShellResult =
        root.run("sh ${File(scriptsDir, name).absolutePath.shQuote()} $action ${runEnv.absolutePath.shQuote()}", timeoutMs)

    suspend fun requireRoot() {
        if (!root.isGranted()) throw EngineException("Root access was not granted. This engine needs root on the host.")
    }

    suspend fun readLogTail(vm: VmConfig, name: String, lines: Int = 40): String = withContext(Dispatchers.IO) {
        val f = File(vms.logDir(vm.id), name)
        if (f.canRead()) f.readLines().takeLast(lines).joinToString("\n")
        else root.run("tail -n $lines ${f.absolutePath.shQuote()}").out
    }
}
