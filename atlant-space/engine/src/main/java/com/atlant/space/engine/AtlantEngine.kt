package com.atlant.space.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.atlant.space.engine.backup.BackupManager
import com.atlant.space.engine.diag.Diagnostics
import com.atlant.space.engine.gms.GmsSupport
import com.atlant.space.engine.ipc.AtlantEngineService
import com.atlant.space.engine.log.EventLog
import com.atlant.space.engine.pm.AppRepository
import com.atlant.space.engine.pm.InstalledApp
import com.atlant.space.engine.pm.PackageInstaller
import com.atlant.space.engine.perm.VirtualPermissionManager
import com.atlant.space.engine.proc.ProcessManager
import com.atlant.space.engine.root.VirtualRoot
import com.atlant.space.engine.runtime.StubConfig
import com.atlant.space.engine.runtime.VirtualRuntime
import com.atlant.space.engine.space.ResourcePolicy
import com.atlant.space.engine.space.SpaceManager
import com.atlant.space.engine.storage.VirtualStorageManager

/**
 * The single entry point to the virtualization engine from the host app and its UI.
 *
 * It wires the pieces together and keeps one instance per process. In the **main** process it owns
 * the full set of managers and serves the engine binder. In a **guest** process (`:vprocN`) only
 * the thin client half is used; the guest never touches another space's managers because it only
 * ever holds its own [VirtualRuntime] state and the binder back to the main process.
 */
class AtlantEngine private constructor(val context: Context) {

    val log = EventLog(context.getDir("engine-log", Context.MODE_PRIVATE))
    val storage = VirtualStorageManager(context)
    val installer = PackageInstaller(context, storage)
    val apps = AppRepository(storage, installer)
    val permissions = VirtualPermissionManager(context)
    val root = VirtualRoot(storage)
    val gms = GmsSupport(context)
    val backup = BackupManager(storage, log)
    val spaces = SpaceManager(context, storage, log)
    val processes = ProcessManager(context.packageName, log) { spaces.get(it)?.policy ?: ResourcePolicy() }

    /** The binder a guest process connects to. Exposed through the host's engine service component. */
    val service by lazy { AtlantEngineService(this) }

    @Volatile private var loaded = false

    /** Called once in the main process. Loads the space index and finishes any interrupted installs. */
    @Synchronized
    fun bootstrapMainProcess() {
        if (loaded) return
        spaces.load()
        spaces.all().forEach { s ->
            runCatching { installer.recover(s.dirName) }.onSuccess { notes ->
                notes.forEach { log.w("Engine", it, s.id) }
            }
        }
        loaded = true
        log.i("Engine", "Main process ready: ${spaces.active().size} active space(s)")
    }

    fun installedApps(spaceId: Int): List<InstalledApp> =
        spaces.get(spaceId)?.let { apps.installed(it.dirName) } ?: emptyList()

    /**
     * Launches [pkg] inside space [spaceId]. The real launcher component is resolved from the
     * guest manifest, a process slot is claimed for the guest's process, and a stub activity bound
     * to that slot's host process is started carrying the real target. The hook layer inside the
     * target process then swaps the stub for the real component.
     *
     * Returns false (and logs) when the app has no launchable activity or the space is out of
     * process slots; it never throws into the caller's UI.
     */
    fun launchApp(spaceId: Int, pkg: String, aliasActivity: String? = null): Boolean {
        val space = spaces.get(spaceId) ?: return fail("No space $spaceId")
        val app = apps.app(space.dirName, pkg) ?: return fail("$pkg is not installed in ${space.name}")
        val manifest = apps.manifest(space.dirName, pkg) ?: return fail("$pkg has no cached manifest")
        val target = aliasActivity?.let { manifest.component(it) } ?: manifest.launcher()
        if (target == null) return fail("$pkg has no launchable activity")
        val processName = manifest.processOf(target)
        val slot = processes.acquireSlot(spaceId, processName) ?: return fail("${space.name} is out of process slots")
        processes.onPackageHosted(slot, pkg)
        storage.provisionPackage(space.dirName, pkg)

        val stub = ComponentName(context.packageName, StubConfig.stubActivity(slot, 0))
        val intent = Intent().apply {
            component = stub
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(EXTRA_SPACE, spaceId)
            putExtra(EXTRA_SLOT, slot)
            putExtra(EXTRA_PACKAGE, pkg)
            putExtra(EXTRA_ACTIVITY, target.name)
            putExtra(EXTRA_PROCESS, processName)
        }
        return try {
            context.startActivity(intent)
            log.i("Engine", "Launching ${app.label} (${target.name}) in slot $slot", spaceId, pkg)
            true
        } catch (t: Throwable) {
            fail("Could not start ${app.label}: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Stops every guest process of a space. Used by the policy, the UI "force stop" and delete. */
    fun stopSpace(spaceId: Int) {
        val pids = processes.stopSpace(spaceId)
        pids.forEach { runCatching { android.os.Process.killProcess(it) } }
        if (pids.isNotEmpty()) log.i("Engine", "Stopped ${pids.size} process(es)", spaceId)
    }

    /** A fresh diagnostics run over the current environment. */
    fun diagnostics() = Diagnostics(context, this)

    private fun fail(reason: String): Boolean {
        log.w("Engine", reason)
        return false
    }

    companion object {
        const val EXTRA_SPACE = "atlant.space"
        const val EXTRA_SLOT = "atlant.slot"
        const val EXTRA_PACKAGE = "atlant.pkg"
        const val EXTRA_ACTIVITY = "atlant.activity"
        const val EXTRA_PROCESS = "atlant.process"

        @Volatile private var instance: AtlantEngine? = null

        fun get(context: Context): AtlantEngine =
            instance ?: synchronized(this) {
                instance ?: AtlantEngine(context.applicationContext).also { instance = it }
            }

        fun peek(): AtlantEngine? = instance
    }
}
