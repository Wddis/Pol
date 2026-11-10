package com.atlant.space.engine.space

import com.atlant.space.engine.device.VirtualDeviceEnvironment
import org.json.JSONObject

/** Per-space resource policy applied by the process manager. */
data class ResourcePolicy(
    /** Maximum number of guest processes this space may keep alive at once. */
    val maxProcesses: Int = 4,
    /** Kill guest processes of this space when its last activity goes to background. */
    val killInBackground: Boolean = false,
) {
    fun toJson() = JSONObject().apply { put("maxProcesses", maxProcesses); put("killInBackground", killInBackground) }

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) ResourcePolicy() else ResourcePolicy(
            o.optInt("maxProcesses", 4).coerceIn(1, 8), o.optBoolean("killInBackground", false),
        )
    }
}

data class Space(
    val id: Int,
    val name: String,
    val createdAt: Long,
    val archived: Boolean = false,
    val env: VirtualDeviceEnvironment,
    val policy: ResourcePolicy = ResourcePolicy(),
) {
    /** Directory name on disk. Derived from the id, so renaming never moves data. */
    val dirName: String get() = "Space_$id"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("createdAt", createdAt); put("archived", archived)
        put("env", env.toJson()); put("policy", policy.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject) = Space(
            o.getInt("id"), o.getString("name"), o.optLong("createdAt"), o.optBoolean("archived"),
            VirtualDeviceEnvironment.fromJson(o.optJSONObject("env") ?: JSONObject()),
            ResourcePolicy.fromJson(o.optJSONObject("policy")),
        )
    }
}
