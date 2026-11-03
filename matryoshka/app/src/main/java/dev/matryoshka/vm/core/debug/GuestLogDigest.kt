package dev.matryoshka.vm.core.debug

import android.content.Context
import dev.matryoshka.vm.core.nest.NestGuest
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Condenses a guest boot for the tester log: a summary first (what died, how, how far the boot
 * got), then errors from the whole logcat and the guest's own crash reports (tombstones, ANR
 * traces, DropBox entries), which the plain log tails would miss.
 */
object GuestLogDigest {
    val EXEC = Regex("""^\[\d+\] exec """)
    private val INIT_DEATH = Regex("""Service '([^']+)' \(pid \d+\) (received signal \d+|exited with status \d+)""")
    private val ENGINE_DEATH = Regex("""^\[\d+\] (died|exit) (.+?): (signal \d+.*|status \d+)$""")
    private val ENGINE_KILL = Regex("""^\[\d+\] (.+?) sends signal (\d+) to \d+ \((.+)\)$""")
    private val LOG_LEVEL = Regex("""^([VDIWEF])/([^(]+)\(\s*(\d+)\): ?(.*)$""")
    private val MILESTONES = listOf(
        "ZygoteInit took to complete", "Entered the Android system server", "startOtherServices took",
        "Enter boot animation", "SystemServer init took", "Boot is finished", "BOOT_COMPLETED", "Displayed ",
        "Exit zygote", "WATCHDOG KILLING", "FATAL EXCEPTION", "Abort message",
    )

    /** Summary lines (cheap: one pass over each file). */
    fun summary(context: Context): List<String> {
        val out = mutableListOf("-- summary")
        val initDeaths = linkedMapOf<String, Int>()
        NestGuest.kmsg(context).forEachLineSafe { line ->
            INIT_DEATH.find(line)?.let { m -> initDeaths.merge("${m.groupValues[1]}: ${m.groupValues[2]}", 1, Int::plus) }
        }
        val engineDeaths = linkedMapOf<String, Int>()
        val kills = linkedMapOf<String, Int>()
        val memory = mutableListOf<String>()
        NestGuest.engineOut(context).forEachLineSafe { line ->
            ENGINE_DEATH.find(line)?.let { m ->
                if (!m.groupValues[3].startsWith("status 0")) engineDeaths.merge("${m.groupValues[2]}: ${m.groupValues[3]}", 1, Int::plus)
            }
            ENGINE_KILL.find(line)?.let { m -> kills.merge("${m.groupValues[1]} -> ${m.groupValues[3]}: signal ${m.groupValues[2]}", 1, Int::plus) }
            if (line.startsWith("nest: t=") || line.startsWith("nest: ps")) memory += line
        }
        val milestones = linkedMapOf<String, String>()
        val lastTiming = arrayOf("")
        NestGuest.logcat(context).forEachLineSafe { line ->
            for (m in MILESTONES) if (m !in milestones && line.contains(m)) milestones[m] = line.take(200)
            if (line.contains("SystemServerTiming(")) lastTiming[0] = line.take(160)
        }
        out += "service deaths seen by guest init (count):"
        initDeaths.entries.sortedByDescending { it.value }.take(40).forEach { out += "  ${it.value}x ${it.key}" }
        out += "guest process ends seen by the engine, non-zero (count):"
        engineDeaths.entries.sortedByDescending { it.value }.take(60).forEach { out += "  ${it.value}x ${it.key}" }
        out += "signals sent between guest processes (count):"
        kills.entries.sortedByDescending { it.value }.take(40).forEach { out += "  ${it.value}x ${it.key}" }
        out += "boot milestones (first occurrence):"
        milestones.forEach { (k, v) -> out += "  [$k] $v" }
        out += "last SystemServerTiming: ${lastTiming[0]}"
        out += "memory over time: " + if (memory.isEmpty()) "-" else ""
        memory.takeLast(40).forEach { out += "  $it" }
        return out
    }

    /** Lines of [f] matching [keep], at most [max] (the last ones when there are more). */
    fun grep(f: File, max: Int, keep: (String) -> Boolean): List<String> {
        val buf = ArrayDeque<String>()
        f.forEachLineSafe { if (keep(it)) { buf.addLast(it.take(400)); if (buf.size > max) buf.removeFirst() } }
        return buf.toList()
    }

    /** E/F lines and crash blocks from the whole logcat, identical messages folded into counts. */
    fun errors(f: File, max: Int): List<String> {
        val out = ArrayList<String>()
        val seen = HashMap<String, Int>()  // tag+message (pid stripped) -> index in out
        val counts = HashMap<Int, Int>()
        var stackLines = 0
        f.forEachLineSafe { line ->
            val m = LOG_LEVEL.find(line)
            if (m == null) {
                // continuation lines (stack traces) right after a kept error
                if (stackLines in 1..40 && out.size < max) { out += "    " + line.take(300); stackLines++ }
                return@forEachLineSafe
            }
            stackLines = 0
            val level = m.groupValues[1]
            if (level != "E" && level != "F" && !line.contains("WATCHDOG") && !line.contains("FATAL")) return@forEachLineSafe
            val key = level + m.groupValues[2] + m.groupValues[4].replace(Regex("""\d+"""), "#")
            val at = seen[key]
            if (at != null) { counts.merge(at, 1, Int::plus); return@forEachLineSafe }
            if (out.size >= max) return@forEachLineSafe
            seen[key] = out.size
            out += line.take(400)
            stackLines = 1
        }
        return out.mapIndexed { i, l -> counts[i]?.let { "$l   [x${it + 1}]" } ?: l }
    }

    /** The guest's own reports: tombstones, ANR traces (watchdog dumps), DropBox entries. */
    fun guestReports(data: File): List<String> {
        val out = mutableListOf<String>()
        fun dump(title: String, dir: File, files: Int, lines: Int, filter: (File) -> Boolean = { true }) {
            val list = dir.listFiles()?.filter { it.isFile && filter(it) }?.sortedByDescending { it.lastModified() }?.take(files)
            if (list.isNullOrEmpty()) return
            for (f in list) {
                out += "-- $title ${f.name} (${f.length()} bytes)"
                runCatching {
                    open(f).bufferedReader().useLines { seq -> seq.take(lines).forEach { out += it.take(300) } }
                }.onFailure { out += "  unreadable: ${it.message}" }
            }
        }
        dump("tombstone", File(data, "tombstones"), 6, 120)
        dump("anr", File(data, "anr"), 2, 400)
        dump("dropbox", File(data, "system/dropbox"), 12, 120) { f ->
            val n = f.name
            (n.contains("crash") || n.contains("watchdog") || n.contains("anr") || n.contains("native") ||
                n.contains("tombstone") || n.contains("wtf")) && !n.endsWith(".lost")
        }
        return out
    }

    private fun open(f: File): InputStream = f.inputStream().let { if (f.name.endsWith(".gz")) GZIPInputStream(it) else it }

    private fun File.forEachLineSafe(action: (String) -> Unit) {
        if (!exists()) return
        runCatching { bufferedReader().useLines { seq -> seq.forEach(action) } }
    }
}
