package com.atlant.space.engine.log

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

enum class Level { DEBUG, INFO, WARN, ERROR }

data class Event(
    val time: Long,
    val level: Level,
    val tag: String,
    val message: String,
    val spaceId: Int = -1,
    val pkg: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("t", time); put("l", level.name); put("g", tag); put("m", message)
        if (spaceId >= 0) put("s", spaceId)
        if (pkg != null) put("p", pkg)
    }.toString()

    companion object {
        fun parse(line: String): Event? = try {
            val o = JSONObject(line)
            Event(
                o.getLong("t"), Level.valueOf(o.getString("l")), o.getString("g"), o.getString("m"),
                o.optInt("s", -1), if (o.has("p")) o.getString("p") else null,
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Persistent event journal of the engine (one JSON object per line).
 * Lives in the main process; guest processes report through the server binder.
 * The file is rotated once it exceeds [maxBytes], keeping one previous generation.
 */
class EventLog(private val dir: File, private val maxBytes: Long = 512 * 1024) {
    private val file = File(dir, "events.log")
    private val old = File(dir, "events.1.log")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "atlant-log").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()

    init { dir.mkdirs() }

    fun addListener(l: (Event) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Event) -> Unit) { listeners.remove(l) }

    fun log(level: Level, tag: String, message: String, spaceId: Int = -1, pkg: String? = null) {
        val e = Event(System.currentTimeMillis(), level, tag, message, spaceId, pkg)
        android.util.Log.println(
            when (level) { Level.DEBUG -> 3; Level.INFO -> 4; Level.WARN -> 5; Level.ERROR -> 6 },
            "Atlant/$tag", message,
        )
        writer.execute {
            try {
                if (file.length() > maxBytes) {
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText(e.toJson() + "\n")
            } catch (_: Exception) {
                // The journal must never take the engine down.
            }
            listeners.forEach { runCatching { it(e) } }
        }
    }

    fun i(tag: String, msg: String, spaceId: Int = -1, pkg: String? = null) = log(Level.INFO, tag, msg, spaceId, pkg)
    fun w(tag: String, msg: String, spaceId: Int = -1, pkg: String? = null) = log(Level.WARN, tag, msg, spaceId, pkg)
    fun e(tag: String, msg: String, spaceId: Int = -1, pkg: String? = null) = log(Level.ERROR, tag, msg, spaceId, pkg)
    fun e(tag: String, msg: String, t: Throwable, spaceId: Int = -1, pkg: String? = null) =
        log(Level.ERROR, tag, "$msg: ${t.javaClass.simpleName}: ${t.message}", spaceId, pkg)

    /** Newest first. */
    fun read(limit: Int = 500): List<Event> {
        val out = ArrayList<Event>()
        for (f in listOf(file, old)) {
            if (!f.isFile || out.size >= limit) continue
            val lines = try { f.readLines() } catch (_: Exception) { emptyList() }
            for (i in lines.indices.reversed()) {
                Event.parse(lines[i])?.let { out.add(it) }
                if (out.size >= limit) break
            }
        }
        return out
    }

    fun clear() {
        writer.execute {
            runCatching { RandomAccessFile(file, "rw").use { it.setLength(0) } }
            old.delete()
        }
    }
}
