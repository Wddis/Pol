package com.atlant.space.engine.root

import com.atlant.space.engine.storage.VirtualStorageManager
import com.atlant.space.engine.util.Files
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Root Compatibility Engine — the *virtual* half.
 *
 * Some apps refuse to run, or hide features, unless a `su` binary is on `PATH` and returns
 * success. ATLANT SPACE never grants real UID 0 (that would require a rooted host and defeats the
 * whole point of a no-root sandbox). Instead each app can be given a **virtual** superuser: a `su`
 * that lives only inside the app's virtual filesystem, backed by a per-app toggle and an audit
 * log. Commands are serviced against the space's virtual root, never the host's real root, so an
 * app sees a consistent "rooted" environment while the host stays untouched.
 *
 * This is the engine-side bookkeeping: which (space, package) pairs hold virtual root, and the
 * command journal. The actual `su` shim that an app's native code execs is installed into the
 * guest's virtual `/system/xbin` by the runtime when the app is launched with root enabled.
 */
class VirtualRoot(private val storage: VirtualStorageManager) {

    data class Grant(val pkg: String, val enabled: Boolean, val grantedAt: Long)

    private fun file(spaceDir: String) = File(storage.dataRoot(spaceDir), ".root/grants.json")
    private fun logFile(spaceDir: String) = File(storage.dataRoot(spaceDir), ".root/su.log")

    private fun dir(spaceId: Int): String = "Space_$spaceId"

    fun grants(spaceId: Int): List<Grant> {
        val f = file(dir(spaceId))
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Grant(o.getString("pkg"), o.optBoolean("on"), o.optLong("at"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun isGranted(spaceId: Int, pkg: String): Boolean = grants(spaceId).any { it.pkg == pkg && it.enabled }

    fun setEnabled(spaceId: Int, pkg: String, enabled: Boolean) {
        val current = grants(spaceId).associateBy { it.pkg }.toMutableMap()
        current[pkg] = Grant(pkg, enabled, System.currentTimeMillis())
        val arr = JSONArray()
        current.values.forEach { arr.put(JSONObject().put("pkg", it.pkg).put("on", it.enabled).put("at", it.grantedAt)) }
        Files.writeAtomic(file(dir(spaceId)), arr.toString())
        logCommand(spaceId, pkg, if (enabled) "GRANT virtual root" else "REVOKE virtual root")
    }

    /** Appends a line to the per-space su audit journal. */
    fun logCommand(spaceId: Int, pkg: String, command: String) {
        val f = logFile(dir(spaceId))
        f.parentFile?.let { Files.ensureDir(it) }
        runCatching { f.appendText("${System.currentTimeMillis()}\t$pkg\t$command\n") }
    }

    fun auditLog(spaceId: Int, limit: Int = 200): List<String> {
        val f = logFile(dir(spaceId))
        if (!f.isFile) return emptyList()
        return try { f.readLines().takeLast(limit).reversed() } catch (_: Exception) { emptyList() }
    }
}
