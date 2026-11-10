package com.atlant.space.engine.perm

import android.content.Context
import android.content.pm.PackageManager
import com.atlant.space.engine.util.Files
import org.json.JSONObject
import java.io.File

enum class PermState { GRANTED, DENIED, ASK }

/**
 * Virtual permission layer.
 *
 * A guest app has no real Android identity, so every runtime permission it checks is answered
 * from this store. The user's decision here is clamped by what ATLANT SPACE itself holds: a
 * permission the host app was not granted can never be reported as granted to a guest, because
 * the underlying operation would fail anyway. That keeps the environment consistent — the guest
 * is never told "granted" for something that cannot work.
 *
 * Persisted per space at `data/.perms/<pkg>.json`.
 */
class VirtualPermissionManager(private val context: Context) {

    fun stateFor(dataRoot: File, pkg: String, permission: String, requested: Boolean): PermState {
        if (permission !in RUNTIME_PERMISSIONS) return PermState.GRANTED
        if (!hostHolds(permission)) return PermState.DENIED
        val stored = read(dataRoot, pkg).optString(permission, "")
        return when (stored) {
            "granted" -> PermState.GRANTED
            "denied" -> PermState.DENIED
            else -> if (requested) PermState.ASK else PermState.DENIED
        }
    }

    fun isGranted(dataRoot: File, pkg: String, permission: String, requested: Boolean): Boolean =
        stateFor(dataRoot, pkg, permission, requested) == PermState.GRANTED

    fun set(dataRoot: File, pkg: String, permission: String, state: PermState) {
        val o = read(dataRoot, pkg)
        when (state) {
            PermState.GRANTED -> o.put(permission, "granted")
            PermState.DENIED -> o.put(permission, "denied")
            PermState.ASK -> o.remove(permission)
        }
        Files.writeAtomic(file(dataRoot, pkg), o.toString())
    }

    /** Current state of every runtime permission the package declares. */
    fun snapshot(dataRoot: File, pkg: String, declared: List<String>): Map<String, PermState> {
        val runtime = declared.filter { it in RUNTIME_PERMISSIONS }
        return runtime.associateWith { stateFor(dataRoot, pkg, it, requested = true) }
    }

    fun hostHolds(permission: String): Boolean {
        // Permissions with no host-level backing are always effectively available to a guest.
        if (permission !in HOST_BACKED) return true
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun file(dataRoot: File, pkg: String) = File(File(dataRoot, ".perms"), "$pkg.json")

    private fun read(dataRoot: File, pkg: String): JSONObject {
        val f = file(dataRoot, pkg)
        return if (f.isFile) try { JSONObject(f.readText()) } catch (_: Exception) { JSONObject() } else JSONObject()
    }

    companion object {
        /** Dangerous permissions a guest requests at runtime. */
        val RUNTIME_PERMISSIONS = setOf(
            "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS", "android.permission.GET_ACCOUNTS",
            "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR",
            "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_PHONE_STATE", "android.permission.CALL_PHONE", "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG", "android.permission.ADD_VOICEMAIL", "android.permission.USE_SIP",
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_SMS",
            "android.permission.RECEIVE_MMS", "android.permission.RECEIVE_WAP_PUSH",
            "android.permission.BODY_SENSORS", "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.POST_NOTIFICATIONS", "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE",
        )

        /**
         * Permissions whose underlying operation goes through the host app's real grant. If the
         * host does not hold these, a guest grant cannot make the operation succeed.
         */
        val HOST_BACKED = setOf(
            "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS", "android.permission.GET_ACCOUNTS",
            "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR",
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO",
            "android.permission.BODY_SENSORS", "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.POST_NOTIFICATIONS",
        )
    }
}
