package com.atlant.space.engine.device

import org.json.JSONObject
import java.security.SecureRandom

/**
 * Virtual parameters of one space. Every component of the engine that answers a guest
 * question about the environment reads it from here, so answers cannot disagree.
 *
 * The scope is deliberately small: values that Android itself scopes per app/profile.
 * Hardware identity (model, fingerprint, serial) is always the real device's.
 */
data class VirtualDeviceEnvironment(
    /** 16 hex digits, the same shape Settings.Secure.ANDROID_ID has. */
    val androidId: String,
    /** BCP-47 tag, or empty to follow the host locale. */
    val locale: String = "",
    /** Olson id, or empty to follow the host time zone. */
    val timeZone: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("androidId", androidId); put("locale", locale); put("timeZone", timeZone)
    }

    companion object {
        private val HEX = Regex("[0-9a-f]{16}")

        fun generate(): VirtualDeviceEnvironment = VirtualDeviceEnvironment(newAndroidId())

        fun newAndroidId(): String {
            val b = ByteArray(8)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        fun isValidAndroidId(s: String) = HEX.matches(s)

        fun fromJson(o: JSONObject): VirtualDeviceEnvironment {
            val id = o.optString("androidId")
            return VirtualDeviceEnvironment(
                if (isValidAndroidId(id)) id else newAndroidId(),
                o.optString("locale"), o.optString("timeZone"),
            )
        }
    }
}
