package com.atlant.space.engine.pm

import org.json.JSONArray
import org.json.JSONObject

/** Per-app launch options chosen by the user. */
data class AppSettings(
    /** Keep the app's processes alive in background instead of applying the space policy. */
    val keepAlive: Boolean = false,
    /** Deliver broadcasts to manifest receivers while the app is not running. */
    val wakeOnBroadcast: Boolean = false,
    /** Redirect file access of native code (needed by apps with hard-coded storage paths). */
    val nativeRedirect: Boolean = true,
) {
    fun toJson() = JSONObject().apply {
        put("keepAlive", keepAlive); put("wakeOnBroadcast", wakeOnBroadcast); put("nativeRedirect", nativeRedirect)
    }

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) AppSettings() else AppSettings(
            o.optBoolean("keepAlive"), o.optBoolean("wakeOnBroadcast"), o.optBoolean("nativeRedirect", true),
        )
    }
}

/** An installed code file and the digest recorded when it was installed. */
data class CodeFile(val name: String, val sha256: String, val size: Long)

/** Record of a package installed in one space (`apps/<pkg>/package.json`). */
data class VPackage(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    /** base.apk first, then splits. */
    val files: List<CodeFile>,
    /** ABI whose libraries were extracted, or "" when the package has no native code. */
    val abi: String,
    /** SHA-256 of the signing certificates; updates must match. */
    val signers: List<String>,
    val permissions: List<String>,
    val installedAt: Long,
    val updatedAt: Long,
    val usesGms: Boolean,
    val settings: AppSettings = AppSettings(),
) {
    fun toJson() = JSONObject().apply {
        put("pkg", packageName); put("label", label); put("vn", versionName); put("vc", versionCode)
        put("min", minSdk); put("tgt", targetSdk)
        put("files", JSONArray(files.map { JSONObject().put("n", it.name).put("h", it.sha256).put("s", it.size) }))
        put("abi", abi); put("signers", JSONArray(signers)); put("perm", JSONArray(permissions))
        put("installedAt", installedAt); put("updatedAt", updatedAt); put("gms", usesGms)
        put("settings", settings.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject) = VPackage(
            o.getString("pkg"), o.optString("label"), o.optString("vn"), o.optLong("vc"),
            o.optInt("min", 1), o.optInt("tgt", 1),
            o.getJSONArray("files").objects().map { CodeFile(it.getString("n"), it.optString("h"), it.optLong("s")) },
            o.optString("abi"), o.getJSONArray("signers").strings(), o.getJSONArray("perm").strings(),
            o.optLong("installedAt"), o.optLong("updatedAt"), o.optBoolean("gms"),
            AppSettings.fromJson(o.optJSONObject("settings")),
        )
    }
}
