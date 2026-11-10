package com.atlant.space.engine.proc

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.io.File

data class DeviceMemory(val totalKb: Long, val availableKb: Long, val lowMemory: Boolean) {
    val usedKb get() = (totalKb - availableKb).coerceAtLeast(0)
    val usedFraction get() = if (totalKb > 0) usedKb.toFloat() / totalKb else 0f
}

/** Resident memory of one guest process, read from the kernel. */
data class ProcessMemory(val pid: Int, val residentKb: Long)

/**
 * Reads live memory figures for the process monitor. Device-wide numbers come from
 * [ActivityManager]; per-process resident size is read from `/proc/<pid>/statm`, which is cheap
 * and does not require any permission for the host's own processes (all guest processes are the
 * host's own processes).
 */
object MemoryInfo {

    fun device(context: Context): DeviceMemory {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return DeviceMemory(mi.totalMem / 1024, mi.availMem / 1024, mi.lowMemory)
    }

    /** Resident set size of [pid] in KB, or 0 when it cannot be read. */
    fun resident(pid: Int): Long {
        val statm = File("/proc/$pid/statm")
        return try {
            val fields = statm.readText().trim().split(" ")
            val pages = fields.getOrNull(1)?.toLongOrNull() ?: return 0
            pages * (PAGE_SIZE / 1024)
        } catch (_: Exception) {
            // Falls back to the host process's own dalvik PSS when another pid is not readable.
            if (pid == android.os.Process.myPid()) Debug.getPss() else 0
        }
    }

    private const val PAGE_SIZE = 4096L
}
