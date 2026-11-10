package com.atlant.space.engine.ipc

import android.os.IBinder
import com.atlant.space.engine.AtlantEngine
import com.atlant.space.engine.log.Level

/**
 * The engine binder served by the main process to every guest process. It is the only channel a
 * sandboxed guest process has back to the engine, so it is deliberately narrow: a guest can
 * register itself, ask whether a virtual permission is granted, read its space's Android ID, check
 * for virtual root and append to the shared journal. It can do nothing that would let one space
 * observe or affect another.
 */
class AtlantEngineService(private val engine: AtlantEngine) : IAtlantEngine.Stub() {

    private val observers = java.util.concurrent.CopyOnWriteArrayList<IProcessObserver>()

    override fun onProcessAttached(spaceId: Int, processName: String, pid: Int, token: IBinder?) {
        val slot = engine.processes.slotOf(spaceId, processName) ?: -1
        engine.processes.onAttached(slot, spaceId, processName, pid)
        observers.forEach { runCatching { it.onProcessStarted(spaceId, processName, pid) } }
        token?.let {
            runCatching {
                it.linkToDeath({
                    engine.processes.onDied(pid)
                    observers.forEach { o -> runCatching { o.onProcessDied(spaceId, processName, pid) } }
                }, 0)
            }
        }
    }

    override fun isPermissionGranted(spaceId: Int, pkg: String, permission: String, requested: Boolean): Boolean {
        val dir = engine.spaces.get(spaceId)?.dirName ?: return false
        return engine.permissions.isGranted(engine.storage.dataRoot(dir), pkg, permission, requested)
    }

    override fun reportEvent(spaceId: Int, pkg: String?, level: Int, tag: String, message: String) {
        val lvl = when (level) { 3 -> Level.DEBUG; 5 -> Level.WARN; 6 -> Level.ERROR; else -> Level.INFO }
        engine.log.log(lvl, tag, message, spaceId, pkg)
    }

    override fun androidId(spaceId: Int): String =
        engine.spaces.get(spaceId)?.env?.androidId ?: ""

    override fun registerProcessObserver(observer: IProcessObserver?) { observer?.let { observers.add(it) } }
    override fun unregisterProcessObserver(observer: IProcessObserver?) { observer?.let { observers.remove(it) } }

    override fun hasVirtualRoot(spaceId: Int, pkg: String): Boolean =
        engine.root.isGranted(spaceId, pkg)
}
