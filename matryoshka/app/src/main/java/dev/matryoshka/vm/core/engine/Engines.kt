package dev.matryoshka.vm.core.engine

import android.content.pm.PackageManager
import dev.matryoshka.vm.core.host.HostProbe
import dev.matryoshka.vm.core.image.GuestImage
import dev.matryoshka.vm.core.model.DeviceProfile
import dev.matryoshka.vm.core.model.EngineType
import dev.matryoshka.vm.core.model.VmConfig
import dev.matryoshka.vm.core.nest.NestGuest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Real VM with its own kernel on KVM, through crosvm. */
class HypervisorEngine(private val env: EngineEnvironment) : VmEngine {
    override val type = EngineType.HYPERVISOR

    override fun netPlan(vm: VmConfig) = NetPlan(
        guestIp = vm.guestIp, prefixLength = 30, gateway = vm.hostIp,
        dns = env.hostDns(), adbHost = vm.guestIp, adbPort = 5555,
    )

    private suspend fun crosvmPath(): String {
        val bundled = File(env.context.filesDir, "engines/crosvm/bin/crosvm")
        return when {
            env.root.run("test -x ${HostProbe.CROSVM_APEX}").ok -> HostProbe.CROSVM_APEX
            bundled.exists() -> bundled.absolutePath
            else -> throw EngineException("crosvm not found. Install the crosvm engine pack from Settings → Engines.")
        }
    }

    override suspend fun prepare(vm: VmConfig, image: GuestImage, log: (String) -> Unit) {
        env.requireRoot()
        env.installScripts()
        for (name in listOf("kernel", "ramdisk.img", "system.img", "vendor.img")) {
            if (!env.images.file(image.id, name).exists()) throw EngineException("Image is missing $name")
        }
        log("Disks ready")
    }

    override suspend fun start(vm: VmConfig, image: GuestImage) {
        env.requireRoot()
        env.installScripts()
        env.writeGuestHandoff(vm, netPlan(vm))
        val runEnv = env.writeRunEnv(vm, image, mapOf("MVM_CROSVM" to crosvmPath()))
        val r = env.script("mvm-hypervisor.sh", "start", runEnv)
        if (!r.ok) throw EngineException("Hypervisor failed to start: ${r.err.ifBlank { r.out }}", env.readLogTail(vm, "crosvm.log"))
    }

    override suspend fun stop(vm: VmConfig) {
        val runEnv = File(env.vms.dirOf(vm.id), "run.env")
        if (runEnv.exists()) env.script("mvm-hypervisor.sh", "stop", runEnv)
    }

    override suspend fun isRunning(vm: VmConfig): Boolean {
        val runEnv = File(env.vms.dirOf(vm.id), "run.env")
        return runEnv.exists() && env.script("mvm-hypervisor.sh", "status", runEnv, 15_000).out.trim() == "running"
    }

    override suspend fun destroy(vm: VmConfig) {
        if (isRunning(vm)) stop(vm)
    }

    override suspend fun onHostNetworkChanged(vm: VmConfig) {
        val runEnv = File(env.vms.dirOf(vm.id), "run.env")
        if (runEnv.exists()) env.script("mvm-net.sh", "refresh", runEnv, 20_000)
    }
}

/** Guest userspace in isolated namespaces on the host kernel. */
class ContainerEngine(private val env: EngineEnvironment) : VmEngine {
    override val type = EngineType.CONTAINER

    override fun netPlan(vm: VmConfig) = NetPlan(
        guestIp = vm.guestIp, prefixLength = 30, gateway = vm.hostIp,
        dns = env.hostDns(), adbHost = vm.guestIp, adbPort = 5555,
    )

    override suspend fun prepare(vm: VmConfig, image: GuestImage, log: (String) -> Unit) {
        env.requireRoot()
        env.installScripts()
        log("Extracting system (first boot of this image only)…")
        val r = env.script("mvm-container.sh", "prepare", env.writeRunEnv(vm, image), timeoutMs = 30 * 60_000L)
        if (!r.ok) throw EngineException("Could not prepare rootfs: ${r.err}")
    }

    override suspend fun start(vm: VmConfig, image: GuestImage) {
        env.requireRoot()
        env.installScripts()
        env.writeGuestHandoff(vm, netPlan(vm))
        // Pre-authorise our key: adbd reads /data/misc/adb/adb_keys; the guest setup script also
        // copies it from /mvm, this covers the very first boot before that script ran.
        val adbDir = File(env.vms.dataDir(vm.id), "misc/adb")
        env.root.run("mkdir -p '${adbDir.absolutePath}' && cp '${env.vms.shareDir(vm.id).absolutePath}/adb_keys' '${adbDir.absolutePath}/adb_keys' && chmod 0640 '${adbDir.absolutePath}/adb_keys' && chown 1000:2000 '${adbDir.absolutePath}/adb_keys'")
        val r = env.script("mvm-container.sh", "start", env.writeRunEnv(vm, image))
        if (!r.ok) throw EngineException("Container failed to start: ${r.err.ifBlank { r.out }}", env.readLogTail(vm, "container.log"))
    }

    override suspend fun stop(vm: VmConfig) {
        val runEnv = File(env.vms.dirOf(vm.id), "run.env")
        if (runEnv.exists()) env.script("mvm-container.sh", "stop", runEnv)
    }

    override suspend fun isRunning(vm: VmConfig): Boolean {
        val runEnv = File(env.vms.dirOf(vm.id), "run.env")
        return runEnv.exists() && env.script("mvm-container.sh", "status", runEnv, 15_000).out.trim() == "running"
    }

    override suspend fun destroy(vm: VmConfig) {
        val runEnv = File(env.vms.dirOf(vm.id), "run.env")
        if (runEnv.exists()) env.script("mvm-container.sh", "destroy", runEnv, 10 * 60_000L)
    }

    override suspend fun onHostNetworkChanged(vm: VmConfig) {
        val runEnv = File(env.vms.dirOf(vm.id), "run.env")
        if (runEnv.exists()) env.script("mvm-net.sh", "refresh", runEnv, 20_000)
    }
}

/**
 * Full-system emulation with QEMU (TCG), no root required. QEMU is GPL-2.0 and ships as a separate
 * "engine pack" APK (package [PACKAGE]) whose native library directory contains
 * `libqemu-system-aarch64.so` — Android only allows executing binaries that come from an APK.
 */
class EmulatorEngine(private val env: EngineEnvironment) : VmEngine {
    override val type = EngineType.EMULATOR
    private val processes = ConcurrentHashMap<String, Process>()

    override fun netPlan(vm: VmConfig) = NetPlan(
        // QEMU user-mode networking (slirp) defaults.
        guestIp = "10.0.2.15", prefixLength = 24, gateway = "10.0.2.2",
        dns = listOf("10.0.2.3"), adbHost = "127.0.0.1", adbPort = 15_500 + vm.netIndex,
    )

    fun qemuBinary(): File? = runCatching {
        qemuBinaryIn(env.context.packageManager)
    }.getOrNull()

    override suspend fun prepare(vm: VmConfig, image: GuestImage, log: (String) -> Unit) = withContext(Dispatchers.IO) {
        qemuBinary() ?: throw EngineException("QEMU engine pack is not installed")
        val userdata = File(env.vms.dataDir(vm.id), "userdata.img")
        if (!userdata.exists()) {
            java.io.RandomAccessFile(userdata, "rw").use { it.setLength(vm.storageGb * 1024L * 1024 * 1024) }
        }
        log("Disks ready")
    }

    override suspend fun start(vm: VmConfig, image: GuestImage) = withContext(Dispatchers.IO) {
        if (processes[vm.id]?.isAlive == true) return@withContext
        val qemu = qemuBinary() ?: throw EngineException("QEMU engine pack is not installed")
        val plan = netPlan(vm)
        env.writeGuestHandoff(vm, plan)
        val img = env.images.dirOf(image.id)
        val data = env.vms.dataDir(vm.id)
        val cmdline = "console=ttyAMA0 androidboot.hardware=mvm androidboot.mvm.engine=emulator ${image.kernelCmdline}".trim()
        val args = listOf(
            qemu.absolutePath,
            "-M", "virt,gic-version=max", "-cpu", "max", "-accel", "tcg,thread=multi,tb-size=512",
            "-smp", vm.cpuCores.toString(), "-m", vm.memoryMb.toString(),
            "-kernel", env.kernelFor(vm).absolutePath, "-initrd", env.ramdiskFor(vm).absolutePath, "-append", cmdline,
            "-drive", "if=none,id=sys,format=raw,readonly=on,file=${File(img, "system.img").absolutePath}",
            "-device", "virtio-blk-pci,drive=sys",
            "-drive", "if=none,id=ven,format=raw,readonly=on,file=${File(img, "vendor.img").absolutePath}",
            "-device", "virtio-blk-pci,drive=ven",
            "-drive", "if=none,id=dat,format=raw,discard=unmap,file=${File(data, "userdata.img").absolutePath}",
            "-device", "virtio-blk-pci,drive=dat",
            "-netdev", "user,id=n0,hostfwd=tcp:127.0.0.1:${plan.adbPort}-:5555",
            "-device", "virtio-net-pci,netdev=n0",
            "-device", "virtio-gpu-pci,xres=${vm.display.width},yres=${vm.display.height}",
            "-virtfs", "local,path=${env.vms.shareDir(vm.id).absolutePath},mount_tag=mvmcfg,security_model=none",
            "-device", "virtio-balloon-pci",
            "-display", "none", "-serial", "file:${File(env.vms.logDir(vm.id), "console.log").absolutePath}",
            "-monitor", "none", "-nodefaults",
        )
        val log = File(env.vms.logDir(vm.id), "qemu.log").apply { parentFile?.mkdirs() }
        val p = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
        Thread.sleep(800)
        if (!p.isAlive) throw EngineException("QEMU exited with code ${p.exitValue()}", log.readLines().takeLast(30).joinToString("\n"))
        processes[vm.id] = p
    }

    override suspend fun stop(vm: VmConfig) = withContext(Dispatchers.IO) {
        processes.remove(vm.id)?.let { p ->
            p.destroy()
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        Unit
    }

    override suspend fun isRunning(vm: VmConfig) = processes[vm.id]?.isAlive == true

    override suspend fun destroy(vm: VmConfig) = stop(vm)

    companion object {
        const val PACKAGE = "dev.matryoshka.engine.qemu"

        @Suppress("DEPRECATION")
        fun qemuBinaryIn(pm: PackageManager): File? = runCatching {
            val info = pm.getApplicationInfo(PACKAGE, 0)
            File(info.nativeLibraryDir, "libqemu-system-aarch64.so").takeIf { it.exists() }
        }.getOrNull()
    }
}

/**
 * Android as ordinary processes of this app under the Nest engine (no root). One Nest guest runs
 * at a time; its screen is the engine's own viewer, its adbd listens on the phone's loopback.
 */
class NestEngine(private val env: EngineEnvironment) : VmEngine {
    override val type = EngineType.NEST

    override fun netPlan(vm: VmConfig) = NetPlan(
        // The guest shares the phone's network stack (sockets are the host's).
        guestIp = "127.0.0.1", prefixLength = 8, gateway = "127.0.0.1",
        dns = env.hostDns(), adbHost = "127.0.0.1", adbPort = adbPort(vm),
    )

    private fun adbPort(vm: VmConfig) = NestGuest.DEFAULT_ADB_PORT + vm.netIndex

    override suspend fun prepare(vm: VmConfig, image: GuestImage, log: (String) -> Unit) = withContext(Dispatchers.IO) {
        NestGuest.ensureInstalled(env.context, log)
        log("Android 11 ready")
    }

    override suspend fun start(vm: VmConfig, image: GuestImage) = withContext(Dispatchers.IO) {
        if (NestGuest.isRunning) throw EngineException("Another Nest device is running: stop it first")
        NestGuest.boot(
            env.context,
            NestGuest.BootOptions(
                dataDir = NestGuest.vmDataDir(env.context, vm.id),
                props = listOf(NestGuest.adbListen(adbPort(vm))) + identityProps(vm),
                adbPublicKey = env.adbKey.adbPublicKey(),
            ),
        )
        Thread.sleep(500)
        if (!NestGuest.isRunning) {
            throw EngineException("The Nest engine did not start", NestGuest.tail(NestGuest.engineOut(env.context), 30).joinToString("\n"))
        }
    }

    /** The device name apps see (VmConfig.deviceProfile); partitions' copies agree with it. */
    private fun identityProps(vm: VmConfig): List<String> {
        fun clean(v: String?) = (v ?: "").replace(Regex("[^A-Za-z0-9 ._+-]"), "").trim().take(60)
        val (manufacturer, brand, model, device, product) = when (vm.deviceProfile) {
            DeviceProfile.HOST -> listOf(
                clean(android.os.Build.MANUFACTURER), clean(android.os.Build.BRAND), clean(android.os.Build.MODEL),
                clean(android.os.Build.DEVICE), clean(android.os.Build.PRODUCT),
            )
            DeviceProfile.GENERIC -> listOf("Matryoshka", "Matryoshka", "Matryoshka Virtual Device", "matryoshka", "matryoshka")
        }
        if (model.isEmpty()) return emptyList()
        val out = mutableListOf("ro.product.device=$device", "ro.product.name=$product")
        for (part in listOf("", "system.", "vendor.", "odm.", "product.", "system_ext.")) {
            out += "ro.product.${part}manufacturer=$manufacturer"
            out += "ro.product.${part}brand=$brand"
            out += "ro.product.${part}model=$model"
        }
        return out
    }

    override suspend fun stop(vm: VmConfig) = withContext(Dispatchers.IO) {
        NestGuest.stop()
        var waited = 0
        while (NestGuest.isRunning && waited++ < 60) Thread.sleep(100)
    }

    override suspend fun isRunning(vm: VmConfig) = NestGuest.isRunning

    override suspend fun onHostNetworkChanged(vm: VmConfig) {
        if (NestGuest.isRunning) NestGuest.updateDns(env.context)
    }

    override suspend fun destroy(vm: VmConfig) = withContext(Dispatchers.IO) {
        if (NestGuest.isRunning) stop(vm)
        NestGuest.vmDataDir(env.context, vm.id).deleteRecursively()
        Unit
    }
}

class EngineRegistry(env: EngineEnvironment) {
    private val engines: Map<EngineType, VmEngine> = listOf(
        NestEngine(env), HypervisorEngine(env), ContainerEngine(env), EmulatorEngine(env),
    ).associateBy { it.type }

    operator fun get(type: EngineType): VmEngine = engines.getValue(type)
}
