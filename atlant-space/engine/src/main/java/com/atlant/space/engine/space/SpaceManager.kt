package com.atlant.space.engine.space

import android.content.Context
import com.atlant.space.engine.device.VirtualDeviceEnvironment
import com.atlant.space.engine.log.EventLog
import com.atlant.space.engine.storage.VirtualStorageManager
import com.atlant.space.engine.util.Files
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the set of spaces and their index file. The index (`spaces.json`) is the single source
 * of truth for which spaces exist, their names and their virtual parameters; the directory on
 * disk is the source of truth for their contents. Both are kept in agreement here.
 */
class SpaceManager(
    private val context: Context,
    private val storage: VirtualStorageManager,
    private val log: EventLog,
) {
    private val indexFile = File(context.filesDir, "spaces.json")
    private val lock = Any()
    private val spaces = LinkedHashMap<Int, Space>()
    private var nextId = 1
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() = listeners.forEach { runCatching { it() } }

    fun load() {
        synchronized(lock) {
            spaces.clear()
            if (indexFile.isFile) {
                try {
                    val root = JSONObject(indexFile.readText())
                    nextId = root.optInt("nextId", 1)
                    root.optJSONArray("spaces")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val s = Space.fromJson(arr.getJSONObject(i))
                            spaces[s.id] = s
                        }
                    }
                } catch (e: Exception) {
                    log.e("SpaceManager", "Index unreadable, rebuilding from disk", e)
                    rebuildFromDisk()
                }
            }
            // Reconcile: adopt orphan directories, drop index entries with no directory.
            val onDisk = storage.listSpaceDirs().toSet()
            val indexed = spaces.values.map { it.dirName }.toSet()
            for (dir in onDisk - indexed) adoptOrphan(dir)
            spaces.values.filter { it.dirName !in onDisk }.forEach {
                log.w("SpaceManager", "Space ${it.id} '${it.name}' has no data directory; recreating it", it.id)
                storage.provision(it.dirName)
            }
            if (spaces.isEmpty()) createInternal("Space 1")
            persist()
        }
        notifyChanged()
    }

    private fun rebuildFromDisk() {
        spaces.clear()
        nextId = 1
        storage.listSpaceDirs().forEach { adoptOrphan(it) }
    }

    private fun adoptOrphan(dir: String) {
        val id = dir.removePrefix("Space_").toIntOrNull() ?: return
        val meta = storage.spaceMetaFile(dir)
        val space = if (meta.isFile) {
            try { Space.fromJson(JSONObject(meta.readText())) } catch (_: Exception) { null }
        } else null
        spaces[id] = space ?: Space(id, "Space $id", System.currentTimeMillis(), env = VirtualDeviceEnvironment.generate())
        nextId = maxOf(nextId, id + 1)
        log.i("SpaceManager", "Adopted existing space directory $dir", id)
    }

    fun all(): List<Space> = synchronized(lock) { spaces.values.sortedBy { it.id } }
    fun active(): List<Space> = all().filter { !it.archived }
    fun get(id: Int): Space? = synchronized(lock) { spaces[id] }

    fun create(name: String): Space = synchronized(lock) {
        val s = createInternal(name)
        persist()
        s
    }.also { notifyChanged() }

    private fun createInternal(name: String): Space {
        val id = nextId++
        val space = Space(id, cleanName(name, id), System.currentTimeMillis(), env = VirtualDeviceEnvironment.generate())
        storage.provision(space.dirName)
        spaces[id] = space
        writeMeta(space)
        log.i("SpaceManager", "Created space '${space.name}'", id)
        return space
    }

    fun rename(id: Int, name: String): Space = update(id) { it.copy(name = cleanName(name, id)) }
    fun setArchived(id: Int, archived: Boolean): Space = update(id) { it.copy(archived = archived) }
    fun setPolicy(id: Int, policy: ResourcePolicy): Space = update(id) { it.copy(policy = policy) }
    fun regenerateIdentity(id: Int): Space = update(id) { it.copy(env = VirtualDeviceEnvironment.generate()) }
    fun setEnvironment(id: Int, env: VirtualDeviceEnvironment): Space = update(id) { it.copy(env = env) }

    private fun update(id: Int, f: (Space) -> Space): Space {
        val updated = synchronized(lock) {
            val cur = spaces[id] ?: throw IllegalArgumentException("No space $id")
            val next = f(cur)
            spaces[id] = next
            writeMeta(next)
            persist()
            next
        }
        notifyChanged()
        return updated
    }

    fun delete(id: Int) {
        synchronized(lock) {
            val s = spaces[id] ?: return
            storage.deleteSpace(s.dirName)
            spaces.remove(id)
            if (spaces.isEmpty()) createInternal("Space 1")
            persist()
            log.i("SpaceManager", "Deleted space '${s.name}'", id)
        }
        notifyChanged()
    }

    /** Creates a new space that is a byte-for-byte copy of [id] (apps, data and files). */
    fun clone(id: Int, name: String): Space {
        val src = get(id) ?: throw IllegalArgumentException("No space $id")
        val dst = create(name)
        try {
            Files.copyTree(storage.privateRoot(src.dirName), storage.privateRoot(dst.dirName))
            Files.copyTree(storage.sharedRoot(src.dirName), storage.sharedRoot(dst.dirName))
            // The clone is a distinct device: give it its own identity but keep everything else.
            val cloned = dst.copy(env = VirtualDeviceEnvironment.generate())
            writeMeta(cloned)
            synchronized(lock) { spaces[dst.id] = cloned; persist() }
            log.i("SpaceManager", "Cloned space ${src.id} into ${dst.id}", dst.id)
            notifyChanged()
            return cloned
        } catch (e: IOException) {
            runCatching { delete(dst.id) }
            throw e
        }
    }

    private fun writeMeta(space: Space) {
        storage.provision(space.dirName)
        Files.writeAtomic(storage.spaceMetaFile(space.dirName), space.toJson().toString())
    }

    private fun persist() {
        val root = JSONObject().apply {
            put("nextId", nextId)
            put("spaces", JSONArray(spaces.values.sortedBy { it.id }.map { it.toJson() }))
        }
        Files.writeAtomic(indexFile, root.toString())
    }

    private fun cleanName(name: String, id: Int): String =
        name.trim().replace(Regex("\\s+"), " ").take(40).ifEmpty { "Space $id" }
}
