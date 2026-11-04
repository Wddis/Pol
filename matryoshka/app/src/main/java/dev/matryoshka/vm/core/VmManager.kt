package dev.matryoshka.vm.core

import android.content.Context
import android.net.ConnectivityManager
import dev.matryoshka.vm.core.adb.AdbKey
import dev.matryoshka.vm.core.display.DisplaySession
import dev.matryoshka.vm.core.engine.EngineEnvironment
import dev.matryoshka.vm.core.engine.EngineException
import dev.matryoshka.vm.core.engine.EngineRegistry
import dev.matryoshka.vm.core.guest.GuestClient
import dev.matryoshka.vm.core.guest.Provisioner
import dev.matryoshka.vm.core.host.HostProbe
import dev.matryoshka.vm.core.host.RootShell
import dev.matryoshka.vm.core.image.GuestImage
import dev.matryoshka.vm.core.image.ImageManager
import dev.matryoshka.vm.core.model.EngineType
import dev.matryoshka.vm.core.model.GmsMode
import dev.matryoshka.vm.core.model.VmConfig
import dev.matryoshka.vm.core.model.VmState
import dev.matryoshka.vm.core.nest.NestGuest
import dev.matryoshka.vm.core.storage.VmRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Central coordinator: owns VM lifecycles, guest connections and display sessions. */
class VmManager(private val context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val root = RootShell()
    val repo = VmRepository(File(context.filesDir, "vms"))
    val images = ImageManager(File(context.filesDir, "images"))
    val adbKey: AdbKey by lazy { AdbKey.loadOrCreate(File(context.filesDir, "keys")) }
    val probe = HostProbe(context, root)
    val provisioner = Provisioner(context.cacheDir)

    private val env by lazy { EngineEnvironment(context, root, repo, images, adbKey, ::hostDns) }
    private val engines by lazy { EngineRegistry(env) }

    private val _states = MutableStateFlow<Map<String, VmState>>(emptyMap())
    val states: StateFlow<Map<String, VmState>> = _states.asStateFlow()

    private val _logs = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val logs: StateFlow<Map<String, List<String>>> = _logs.asStateFlow()

    private val guests = ConcurrentHashMap<String, GuestClient>()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun state(id: String): VmState = _states.value[id] ?: VmState.Stopped
    val runningIds: Set<String> get() = _states.value.filterValues { it.isActive }.keys

    @androidx.annotation.VisibleForTesting
    internal fun setState(id: String, s: VmState) = _states.update { it + (id to s) }

    fun log(id: String, line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _logs.update { m -> m + (id to ((m[id] ?: emptyList()) + "$ts  $line").takeLast(300)) }
    }

    fun guest(id: String): GuestClient? {
        val vm = repo.get(id) ?: return null
        return guests.getOrPut(id) {
            val plan = engines[vm.engine].netPlan(vm)
            GuestClient(plan.adbHost, plan.adbPort, adbKey)
        }
    }

    fun start(id: String): Job? {
        if (jobs[id]?.isActive == true) return jobs[id]
        val job = scope.launch { boot(id) }
        jobs[id] = job
        return job
    }

    private suspend fun boot(id: String) {
        val vm = repo.get(id) ?: return
        val engine = engines[vm.engine]
        try {
            setState(id, VmState.Preparing("Checking system image"))
            val image = if (vm.engine == EngineType.NEST) {
                // Nest's guest image is downloaded and unpacked by the engine itself (prepare).
                GuestImage(id = NestGuest.IMAGE_ID, name = "Android 11", engines = listOf(EngineType.NEST), files = emptyList())
            } else {
                val img = images.get(vm.imageId) ?: throw EngineException("System image ${vm.imageId} is not installed")
                if (!images.isComplete(img, vm.engine)) throw EngineException("System image is incomplete — download it again")
                img
            }

            setState(id, VmState.Preparing("Preparing virtual hardware"))
            engine.prepare(vm, image) { log(id, it); setState(id, VmState.Preparing(it)) }

            setState(id, VmState.Booting("Starting ${vm.engine.label}"))
            log(id, "Starting ${vm.engine.label} engine")
            engine.start(vm, image)

            val guest = guest(id)!!
            guest.close()
            setState(id, VmState.Booting("Booting Android 11"))
            val adbTimeout = when (vm.engine) {
                EngineType.EMULATOR -> 20 * 60_000L
                EngineType.NEST -> 12 * 60_000L
                else -> 4 * 60_000L
            }
            if (vm.engine == EngineType.NEST) watchNest(id)
            if (!guest.awaitAdb(adbTimeout)) throw EngineException("The virtual device did not come up", failureLog(vm))
            log(id, "Guest connected: ${guest.connection().bannerProps["ro.product.model"] ?: "Android"}")
            setState(id, VmState.Booting("Starting system services"))
            if (!guest.awaitBootCompleted(adbTimeout)) throw EngineException("Android did not finish booting")

            setState(id, VmState.Running(System.currentTimeMillis()))
            log(id, "Android 11 is running")
            postBoot(vm, guest)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log(id, "Error: ${e.message}")
            runCatching { engine.stop(vm) }
            setState(id, VmState.Failed(e.message ?: e.javaClass.simpleName, (e as? EngineException)?.log))
        }
    }

    private suspend fun failureLog(vm: VmConfig): String? = runCatching {
        if (vm.engine == EngineType.NEST) NestGuest.tail(NestGuest.engineOut(context), 40).joinToString("\n")
        else env.readLogTail(vm, logNameFor(vm.engine))
    }.getOrNull()

    /** A Nest guest is a child process of this app: notice when it ends on its own. */
    private fun watchNest(id: String) = scope.launch {
        while (true) {
            kotlinx.coroutines.delay(2000)
            val s = state(id)
            if (s is VmState.Stopping || s is VmState.Stopped || s is VmState.Failed) return@launch
            if (!NestGuest.isRunning) {
                jobs.remove(id)?.cancel()
                guests[id]?.close()
                val st = NestGuest.state.value
                val code = (st as? NestGuest.State.Stopped)?.exitCode
                log(id, "The Nest engine ended" + (code?.let { " (code $it)" } ?: ""))
                setState(id, if (code == 0 || code == 143) VmState.Stopped
                    else VmState.Failed("Android stopped unexpectedly", failureLog(repo.get(id) ?: return@launch)))
                return@launch
            }
        }
    }

    private suspend fun postBoot(vm: VmConfig, guest: GuestClient) {
        val firstBoot = vm.lastBootAt == null
        repo.update(vm.id) { it.copy(lastBootAt = System.currentTimeMillis()) }
        if (vm.engine == EngineType.NEST) {
            // The Nest engine sets the guest's screen itself (the phone's aspect, 240 dpi): an
            // override here would make the guest draw far more pixels than are shown. Undo the one
            // earlier versions left in the guest's settings; short animations; few cached apps
            // (the guest shares the phone's memory).
            runCatching {
                guest.shell(
                    "wm size reset; wm density reset; " +
                        // the phone keeps the screen on; the guest's own sleep only blanked it
                        // (black screen until a touch) and showed the lock screen
                        "svc power stayon true; settings put system screen_off_timeout 2147483647; " +
                        "settings put global window_animation_scale 0.5; " +
                        "settings put global transition_animation_scale 0.5; " +
                        "settings put global animator_duration_scale 0.5; " +
                        "settings put global activity_manager_constants max_cached_processes=8"
                )
                // Once per device (the marker lives in the guest's settings): no lock screen, dark
                // theme, and system parts a VM never uses switched off (they start at boot and
                // take memory). They stay installed and can be enabled again under Apps.
                if (guest.shell("settings get global matryoshka_tuned").second.trim() != TUNING_LEVEL) {
                    log(vm.id, "Tuning the guest system…")
                    guest.shell(
                        "locksettings set-disabled true; cmd uimode night yes; " +
                            NEST_UNUSED_PACKAGES.joinToString("; ") { "pm disable-user --user 0 $it >/dev/null 2>&1" } +
                            "; settings put global matryoshka_tuned $TUNING_LEVEL"
                    )
                }
            }.onFailure { log(vm.id, "Guest settings: ${it.message}") }
        } else {
            runCatching { guest.applyDisplay(vm.display.width, vm.display.height, vm.display.dpi) }
        }
        if (firstBoot && vm.gms == GmsMode.MICROG) {
            runCatching { provisioner.installMicroG(guest) { log(vm.id, it) } }
                .onFailure { log(vm.id, "microG setup failed: ${it.message}") }
        }
    }

    fun stop(id: String): Job = scope.launch {
        val vm = repo.get(id) ?: return@launch
        jobs.remove(id)?.cancel()
        setState(id, VmState.Stopping)
        log(id, "Shutting down")
        guests[id]?.let { g ->
            if (g.isConnected) {
                g.powerOff()
                // Give Android time to flush and unmount /data cleanly.
                var waited = 0
                while (waited++ < 20 && engines[vm.engine].isRunning(vm)) kotlinx.coroutines.delay(1000)
            }
            g.close()
        }
        runCatching { engines[vm.engine].stop(vm) }
        setState(id, VmState.Stopped)
        log(id, "Stopped")
    }

    /** An Android key event in the guest (over adb), e.g. Recent apps. */
    fun guestKeyEvent(id: String, keyCode: Int) = scope.launch {
        runCatching { guest(id)?.shell("input keyevent $keyCode") }
    }

    fun restart(id: String) = scope.launch {
        stop(id).join()
        start(id)
    }

    suspend fun create(config: VmConfig) = repo.save(config)

    suspend fun delete(id: String) {
        val vm = repo.get(id) ?: return
        if (state(id).isActive) stop(id).join()
        runCatching { engines[vm.engine].destroy(vm) }
        guests.remove(id)?.close()
        // Data written by a container guest belongs to guest UIDs; remove it as root.
        if (vm.engine == EngineType.CONTAINER) root.run("rm -rf '${repo.dirOf(id).absolutePath}'")
        repo.delete(id)
        _states.update { it - id }
        _logs.update { it - id }
    }

    suspend fun openDisplay(id: String): DisplaySession? {
        val vm = repo.get(id) ?: return null
        val g = guests[id] ?: return null
        return runCatching {
            val conn = g.connection()
            DisplaySession(context, conn, maxSize = maxOf(vm.display.width, vm.display.height),
                bitRate = vm.display.bitRate, maxFps = vm.display.maxFps)
        }.getOrNull()
    }

    suspend fun installMagisk(id: String) = withContext(Dispatchers.IO) {
        val vm = repo.get(id) ?: return@withContext
        if (vm.engine == EngineType.CONTAINER) {
            throw EngineException("Magisk needs a boot image; use the Hypervisor or Emulator engine for Magisk root.")
        }
        if (vm.engine == EngineType.NEST) {
            // No boot image: the engine starts Magisk itself from the next boot on.
            val g = guest(id) ?: throw EngineException("Start the virtual device first")
            provisioner.prepareNestMagisk(g, NestGuest.magiskTmpDir(context, vm.id), NestGuest.vmDataDir(context, vm.id)) { log(id, it) }
            repo.update(id) { it.copy(magisk = true) }
            return@withContext
        }
        val g = guest(id) ?: throw EngineException("Start the virtual device first")
        provisioner.installMagisk(g, images.file(vm.imageId, "boot.img"), env.bootDir(vm)) { log(id, it) }
    }

    suspend fun installMicroG(id: String) {
        val g = guest(id) ?: throw EngineException("Start the virtual device first")
        provisioner.installMicroG(g) { log(id, it) }
    }

    suspend fun installAppStore(id: String) {
        val g = guest(id) ?: throw EngineException("Start the virtual device first")
        provisioner.installApp(g, dev.matryoshka.vm.core.guest.Components.FDROID) { log(id, it) }
    }

    fun removeMagisk(id: String) {
        val vm = repo.get(id) ?: return
        if (vm.engine == EngineType.NEST) {
            NestGuest.magiskTmpDir(context, vm.id).deleteRecursively()
            scope.launch { repo.update(id) { it.copy(magisk = false) } }
            return
        }
        env.bootDir(vm).deleteRecursively()
    }

    fun hasMagiskBoot(id: String): Boolean = repo.get(id)?.let {
        if (it.engine == EngineType.NEST) it.magisk && File(NestGuest.magiskTmpDir(context, it.id), "magisk").exists()
        else File(env.bootDir(it), "ramdisk.magisk.img").exists()
    } == true

    fun onHostNetworkChanged() = scope.launch {
        runningIds.mapNotNull { repo.get(it) }.forEach { vm -> runCatching { engines[vm.engine].onHostNetworkChanged(vm) } }
    }

    fun hostDns(): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val dns = cm.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers.orEmpty()
            .filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        return (dns + listOf("1.1.1.1", "8.8.8.8")).distinct().take(3)
    }

    suspend fun hostReport(askRoot: Boolean) = probe.probe(askRoot)

    private fun logNameFor(engine: EngineType) = when (engine) {
        EngineType.NEST -> "engine.txt"
        EngineType.HYPERVISOR -> "console.log"
        EngineType.CONTAINER -> "container.log"
        EngineType.EMULATOR -> "qemu.log"
    }
}

private const val TUNING_LEVEL = "1"

/** Guest system parts a Nest VM has no use for (no NFC, camera, printers, secure element...). */
private val NEST_UNUSED_PACKAGES = listOf(
    "com.android.dreams.basic", "com.android.dreams.phototable", "com.android.egg", "com.android.traceur",
    "com.android.nfc", "com.android.printspooler", "com.android.printservice.recommendation", "com.android.se",
    "com.android.simappdialog", "org.lineageos.recorder", "com.android.camera2", "com.android.wallpaper.livepicker",
    "com.boringdroid.systemui", "org.lineageos.waydroidupdater", "com.android.systemui.plugin.globalactions.wallet",
)

val EngineType.label: String
    get() = when (this) {
        EngineType.NEST -> "Nest"
        EngineType.HYPERVISOR -> "Hypervisor"
        EngineType.CONTAINER -> "Container"
        EngineType.EMULATOR -> "Emulator"
    }
