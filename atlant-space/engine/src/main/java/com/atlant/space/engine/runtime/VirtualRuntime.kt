package com.atlant.space.engine.runtime

import android.os.Process
import com.atlant.space.engine.device.VirtualDeviceEnvironment
import com.atlant.space.engine.pm.ParsedManifest
import com.atlant.space.engine.pm.VPackage

/**
 * Per-process state of a guest host process (`:vprocN`). Set once when the process is claimed
 * by a space and then read by every hook. In the main process it stays uninitialised.
 */
object VirtualRuntime {
    @Volatile var initialized = false; private set

    var spaceId: Int = -1; private set
    var slot: Int = -1; private set
    /** Guest process name as the guest declared it, e.g. "com.app" or "com.app:push". */
    var processName: String = ""; private set
    var environment: VirtualDeviceEnvironment = VirtualDeviceEnvironment.generate(); private set

    /** The package whose Application owns this process (the first component launched here). */
    @Volatile var hostingPackage: String? = null

    private val packages = HashMap<String, LoadedGuest>()

    data class LoadedGuest(
        val record: VPackage,
        val manifest: ParsedManifest,
        val apkPaths: List<String>,
        val nativeLibDir: String,
        val dataDir: String,
        val extDataDir: String,
    )

    @Synchronized
    fun init(spaceId: Int, slot: Int, processName: String, env: VirtualDeviceEnvironment) {
        if (initialized) return
        this.spaceId = spaceId
        this.slot = slot
        this.processName = processName
        this.environment = env
        initialized = true
    }

    @Synchronized fun registerPackage(g: LoadedGuest) { packages[g.record.packageName] = g }
    @Synchronized fun guest(pkg: String): LoadedGuest? = packages[pkg]
    @Synchronized fun loadedPackages(): List<String> = packages.keys.toList()

    val hostPid: Int get() = Process.myPid()
}
