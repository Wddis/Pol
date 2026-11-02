package dev.matryoshka.vm.core.storage

import dev.matryoshka.vm.core.model.VmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Persists every virtual device in its own directory:
 *
 * ```
 * files/vms/<id>/
 *   config.json        VmConfig
 *   image/             guest image (container rootfs or VM disk images)
 *   data/              guest /data (container) or userdata.img (hypervisor / emulator)
 *   share/             exchange folder, visible in the guest as /mvm/share
 *   logs/              engine + guest console logs
 * ```
 *
 * Each VM is therefore fully independent: deleting the directory deletes the device.
 */
class VmRepository(private val root: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val _vms = MutableStateFlow<List<VmConfig>>(emptyList())
    val vms: StateFlow<List<VmConfig>> = _vms.asStateFlow()

    init {
        root.mkdirs()
        _vms.value = loadAll()
    }

    fun dirOf(id: String) = File(root, id)
    fun imageDir(id: String) = File(dirOf(id), "image")
    fun dataDir(id: String) = File(dirOf(id), "data")
    fun shareDir(id: String) = File(dirOf(id), "share")
    fun logDir(id: String) = File(dirOf(id), "logs")

    fun get(id: String): VmConfig? = _vms.value.firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString()

    /** Smallest free /30 network index so that two VMs never share an address range. */
    fun nextNetIndex(): Int {
        val used = _vms.value.map { it.netIndex }.toSet()
        return (1..250).first { it !in used }
    }

    suspend fun save(config: VmConfig) = withContext(Dispatchers.IO) {
        val dir = dirOf(config.id)
        listOf(dir, imageDir(config.id), dataDir(config.id), shareDir(config.id), logDir(config.id))
            .forEach { it.mkdirs() }
        val tmp = File(dir, "config.json.tmp")
        tmp.writeText(json.encodeToString(VmConfig.serializer(), config))
        tmp.renameTo(File(dir, "config.json"))
        _vms.value = (_vms.value.filterNot { it.id == config.id } + config).sortedBy { it.createdAt }
    }

    suspend fun update(id: String, transform: (VmConfig) -> VmConfig) {
        get(id)?.let { save(transform(it)) }
    }

    /**
     * Removes the VM from the list. Files that belong to root (container rootfs) must be deleted by
     * the engine through the root shell before calling this; whatever is left is deleted here.
     */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dirOf(id).deleteRecursively()
        _vms.value = _vms.value.filterNot { it.id == id }
    }

    private fun loadAll(): List<VmConfig> =
        root.listFiles().orEmpty()
            .mapNotNull { dir ->
                runCatching {
                    json.decodeFromString(VmConfig.serializer(), File(dir, "config.json").readText())
                }.getOrNull()
            }
            .sortedBy { it.createdAt }
}
