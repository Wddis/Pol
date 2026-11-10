package com.atlant.space.engine.proc

import com.atlant.space.engine.log.EventLog
import com.atlant.space.engine.runtime.StubConfig
import com.atlant.space.engine.space.ResourcePolicy
import java.util.concurrent.CopyOnWriteArrayList

/** One live guest host process (`:vprocN`). */
data class GuestProcess(
    val slot: Int,
    val spaceId: Int,
    val processName: String,
    var pid: Int,
    val startedAt: Long,
    val packages: MutableSet<String> = linkedSetOf(),
    var lastUsed: Long = System.currentTimeMillis(),
)

/**
 * Tracks which of the fixed stub process slots are in use and by whom. Lives in the main process;
 * guest processes announce themselves over the engine binder. Slot assignment is what ties a guest
 * process to its space — each space gets its own slots, so two spaces never share a Linux process
 * and therefore never share memory, static state or open files.
 */
class ProcessManager(
    private val hostPackage: String,
    private val log: EventLog,
    private val policyOf: (Int) -> ResourcePolicy,
) {
    private val lock = Any()
    private val bySlot = HashMap<Int, GuestProcess>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = listeners.forEach { runCatching { it() } }

    fun running(): List<GuestProcess> = synchronized(lock) { bySlot.values.sortedBy { it.slot } }
    fun running(spaceId: Int): List<GuestProcess> = running().filter { it.spaceId == spaceId }
    fun slotOf(spaceId: Int, processName: String): Int? =
        synchronized(lock) { bySlot.values.firstOrNull { it.spaceId == spaceId && it.processName == processName }?.slot }

    /**
     * Returns the slot a guest process should run in, reusing the slot already serving this
     * (space, processName) pair, or claiming a free one. Returns null when the space is at its
     * process limit and no reusable slot exists.
     */
    fun acquireSlot(spaceId: Int, processName: String): Int? = synchronized(lock) {
        bySlot.values.firstOrNull { it.spaceId == spaceId && it.processName == processName }?.let {
            it.lastUsed = System.currentTimeMillis(); return it.slot
        }
        val policy = policyOf(spaceId)
        val usedBySpace = bySlot.values.count { it.spaceId == spaceId }
        if (usedBySpace >= policy.maxProcesses) {
            // Evict this space's least-recently-used process to stay within its budget.
            val victim = bySlot.values.filter { it.spaceId == spaceId }.minByOrNull { it.lastUsed }
            if (victim == null) return null
            log.i("Proc", "Space $spaceId at limit ${policy.maxProcesses}; reclaiming slot ${victim.slot}", spaceId)
            bySlot.remove(victim.slot)
        }
        val free = (0 until StubConfig.PROCESS_SLOTS).firstOrNull { it !in bySlot.keys } ?: run {
            val victim = bySlot.values.minByOrNull { it.lastUsed } ?: return null
            bySlot.remove(victim.slot); victim.slot
        }
        bySlot[free] = GuestProcess(free, spaceId, processName, pid = 0, startedAt = System.currentTimeMillis())
        changed()
        free
    }

    fun onAttached(slot: Int, spaceId: Int, processName: String, pid: Int) {
        synchronized(lock) {
            val gp = bySlot[slot] ?: GuestProcess(slot, spaceId, processName, pid, System.currentTimeMillis()).also { bySlot[slot] = it }
            gp.pid = pid; gp.lastUsed = System.currentTimeMillis()
        }
        log.i("Proc", "Guest process attached: slot=$slot pid=$pid '$processName'", spaceId)
        changed()
    }

    fun onPackageHosted(slot: Int, pkg: String) {
        synchronized(lock) { bySlot[slot]?.packages?.add(pkg) }
        changed()
    }

    fun onDied(pid: Int) {
        val gone = synchronized(lock) {
            val e = bySlot.values.firstOrNull { it.pid == pid } ?: return
            bySlot.remove(e.slot); e
        }
        log.i("Proc", "Guest process died: slot=${gone.slot} pid=$pid", gone.spaceId)
        changed()
    }

    fun slotForPid(pid: Int): Int? = synchronized(lock) { bySlot.values.firstOrNull { it.pid == pid }?.slot }

    fun stopSpace(spaceId: Int): List<Int> {
        val pids = synchronized(lock) {
            val victims = bySlot.values.filter { it.spaceId == spaceId }
            victims.forEach { bySlot.remove(it.slot) }
            victims.mapNotNull { it.pid.takeIf { p -> p > 0 } }
        }
        changed()
        return pids
    }
}
