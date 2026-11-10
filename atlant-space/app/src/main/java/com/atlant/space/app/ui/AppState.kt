package com.atlant.space.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atlant.space.engine.AtlantEngine
import com.atlant.space.engine.backup.BackupInfo
import com.atlant.space.engine.diag.DiagnosticsReport
import com.atlant.space.engine.log.Event
import com.atlant.space.engine.pm.InstallProgress
import com.atlant.space.engine.pm.InstalledApp
import com.atlant.space.engine.perm.PermState
import com.atlant.space.engine.proc.DeviceMemory
import com.atlant.space.engine.proc.GuestProcess
import com.atlant.space.engine.proc.MemoryInfo
import com.atlant.space.engine.root.VirtualRoot
import com.atlant.space.engine.space.ResourcePolicy
import com.atlant.space.engine.space.Space
import com.atlant.space.engine.storage.StorageUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Transient feedback shown as a snackbar/toast. */
data class Toast(val id: Long, val message: String, val error: Boolean)

/** State of an install in progress, surfaced as a dialog. */
data class InstallState(val label: String, val progress: InstallProgress?, val done: Boolean, val error: String?)

/**
 * The one state holder the whole UI reads and drives. It owns no virtualization logic of its own —
 * every action calls through to [AtlantEngine] on a background dispatcher and then refreshes the
 * slice of state the UI shows, so the Compose tree stays a pure function of this object.
 */
class AppState(app: Application) : AndroidViewModel(app) {

    val engine: AtlantEngine = AtlantEngine.get(app)

    var spaces by mutableStateOf<List<Space>>(emptyList()); private set
    var selectedSpaceId by mutableStateOf(-1); private set
    var apps by mutableStateOf<List<InstalledApp>>(emptyList()); private set
    var usage by mutableStateOf(StorageUsage(0, 0, 0)); private set
    var processes by mutableStateOf<List<GuestProcess>>(emptyList()); private set
    var memory by mutableStateOf(DeviceMemory(0, 0, false)); private set
    var log by mutableStateOf<List<Event>>(emptyList()); private set
    var installState by mutableStateOf<InstallState?>(null); private set
    var toast by mutableStateOf<Toast?>(null); private set

    val selectedSpace: Space? get() = spaces.firstOrNull { it.id == selectedSpaceId }

    init {
        engine.spaces.addListener { viewModelScope.launch { refreshSpaces() } }
        engine.processes.addListener { viewModelScope.launch { refreshProcesses() } }
        refreshSpaces()
    }

    // ---- spaces ----

    fun refreshSpaces() = viewModelScope.launch {
        val all = withContext(Dispatchers.IO) { engine.spaces.all() }
        spaces = all
        if (selectedSpaceId < 0 || all.none { it.id == selectedSpaceId }) {
            selectedSpaceId = all.firstOrNull { !it.archived }?.id ?: all.firstOrNull()?.id ?: -1
        }
        refreshSelected()
    }

    fun selectSpace(id: Int) { selectedSpaceId = id; refreshSelected() }

    fun refreshSelected() = viewModelScope.launch {
        val s = selectedSpace ?: run { apps = emptyList(); return@launch }
        val (a, u) = withContext(Dispatchers.IO) {
            engine.apps.installed(s.dirName) to engine.storage.usage(s.dirName)
        }
        apps = a; usage = u
    }

    fun createSpace(name: String) = io("Space created") { engine.spaces.create(name).also { selectedSpaceId = it.id } }
    fun renameSpace(id: Int, name: String) = io("Renamed") { engine.spaces.rename(id, name) }
    fun archiveSpace(id: Int, archived: Boolean) = io(if (archived) "Archived" else "Restored") { engine.spaces.setArchived(id, archived) }
    fun regenerateIdentity(id: Int) = io("New identity generated") { engine.spaces.regenerateIdentity(id) }
    fun setPolicy(id: Int, policy: ResourcePolicy) = io("Policy updated") { engine.spaces.setPolicy(id, policy) }
    fun cloneSpace(id: Int, name: String) = io("Cloned") { engine.spaces.clone(id, name) }
    fun deleteSpace(id: Int) = io("Deleted") { engine.stopSpace(id); engine.spaces.delete(id) }

    // ---- apps ----

    fun installApks(uris: List<Uri>) {
        val s = selectedSpace ?: return
        installState = InstallState("Installing…", null, done = false, error = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    engine.installer.install(s.dirName, uris) { p ->
                        installState = InstallState(installState?.label ?: "", p, false, null)
                    }
                }
                installState = InstallState(result.pkg.label, null, done = true, error = null)
                refreshSelected()
                emit("${result.pkg.label} ${if (result.updated) "updated" else "installed"}")
            } catch (e: Exception) {
                installState = InstallState("", null, done = true, error = e.message ?: e.javaClass.simpleName)
                emit(e.message ?: "Install failed", error = true)
            }
        }
    }

    fun dismissInstall() { installState = null }

    fun launch(pkg: String) {
        val s = selectedSpace ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { engine.launchApp(s.id, pkg) }
            refreshProcesses()
            emit(if (ok) "Starting…" else "Could not start the app — see the journal", error = !ok)
        }
    }

    fun uninstall(pkg: String) {
        val s = selectedSpace ?: return
        io("Removed") { engine.apps.uninstall(s.dirName, pkg) }.also { it.invokeOnCompletion { refreshSelected() } }
    }

    fun clearCache(pkg: String) {
        val s = selectedSpace ?: return
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) { engine.storage.clearCache(s.dirName, pkg) }
            refreshSelected()
            emit("Freed ${freed / 1024} KB")
        }
    }

    fun clearData(pkg: String) {
        val s = selectedSpace ?: return
        io("Data cleared") { engine.storage.deletePackageData(s.dirName, pkg); engine.storage.provisionPackage(s.dirName, pkg) }
    }

    fun forceStop() {
        val s = selectedSpace ?: return
        io("Stopped") { engine.stopSpace(s.id) }
    }

    fun app(pkg: String): InstalledApp? = selectedSpace?.let { engine.apps.app(it.dirName, pkg) }

    fun updateAppSettings(pkg: String, settings: com.atlant.space.engine.pm.AppSettings) {
        val s = selectedSpace ?: return
        io("Saved") { engine.apps.updateSettings(s.dirName, pkg, settings) }.also { it.invokeOnCompletion { refreshSelected() } }
    }

    fun packageUsage(pkg: String): StorageUsage =
        selectedSpace?.let { engine.storage.packageUsage(it.dirName, pkg) } ?: StorageUsage(0, 0, 0)

    // ---- file manager ----

    /** The two on-disk roots of the current space the file manager browses. */
    fun fileRoots(): List<Pair<String, java.io.File>> {
        val s = selectedSpace ?: return emptyList()
        return listOf(
            "App data" to engine.storage.dataRoot(s.dirName),
            "Shared storage (/sdcard)" to engine.storage.sharedRoot(s.dirName),
        )
    }

    // ---- permissions ----

    fun permissions(pkg: String): Map<String, PermState> {
        val s = selectedSpace ?: return emptyMap()
        val declared = engine.apps.app(s.dirName, pkg)?.record?.permissions ?: emptyList()
        return engine.permissions.snapshot(engine.storage.dataRoot(s.dirName), pkg, declared)
    }

    fun setPermission(pkg: String, permission: String, state: PermState) {
        val s = selectedSpace ?: return
        engine.permissions.set(engine.storage.dataRoot(s.dirName), pkg, permission, state)
    }

    // ---- virtual root ----

    fun rootGrants(): List<VirtualRoot.Grant> = selectedSpace?.let { engine.root.grants(it.id) } ?: emptyList()
    fun setRoot(pkg: String, enabled: Boolean) {
        val s = selectedSpace ?: return
        engine.root.setEnabled(s.id, pkg, enabled); emit(if (enabled) "Virtual root granted" else "Virtual root revoked")
    }

    // ---- processes / memory ----

    fun refreshProcesses() = viewModelScope.launch {
        processes = engine.processes.running()
        memory = withContext(Dispatchers.IO) { MemoryInfo.device(getApplication()) }
    }

    // ---- journal ----

    fun refreshLog() = viewModelScope.launch {
        log = withContext(Dispatchers.IO) { engine.log.read(500) }
    }
    fun clearLog() { engine.log.clear(); refreshLog() }

    // ---- backup ----

    fun backups(): List<BackupInfo> = engine.backup.list()
    fun exportSpace() {
        val s = selectedSpace ?: return
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { engine.backup.export(s.id, s.name, s.dirName) }
                emit("Backup saved: ${f.name}")
            } catch (e: Exception) { emit(e.message ?: "Backup failed", error = true) }
        }
    }
    fun restoreBackup(info: BackupInfo) {
        viewModelScope.launch {
            try {
                val created = withContext(Dispatchers.IO) {
                    val sp = engine.spaces.create("${info.spaceName} (restored)")
                    engine.backup.restoreInto(info.file, sp.dirName); sp
                }
                selectedSpaceId = created.id; refreshSpaces()
                emit("Restored into ${created.name}")
            } catch (e: Exception) { emit(e.message ?: "Restore failed", error = true) }
        }
    }

    // ---- diagnostics ----

    suspend fun diagnostics(): DiagnosticsReport = withContext(Dispatchers.IO) { engine.diagnostics().run() }

    // ---- helpers ----

    private fun io(success: String, block: () -> Unit) = viewModelScope.launch {
        try { withContext(Dispatchers.IO) { block() }; emit(success) }
        catch (e: Exception) { emit(e.message ?: "Operation failed", error = true) }
    }

    private fun emit(message: String, error: Boolean = false) {
        toast = Toast(System.nanoTime(), message, error)
    }
    fun consumeToast() { toast = null }
}
