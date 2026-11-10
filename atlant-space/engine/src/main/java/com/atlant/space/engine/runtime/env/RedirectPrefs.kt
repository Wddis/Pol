package com.atlant.space.engine.runtime.env

import android.content.Context
import android.content.SharedPreferences
import com.atlant.space.engine.util.Files
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * File-backed SharedPreferences for guests, stored as one JSON file per name under the space.
 *
 * A JSON document is used rather than the platform's XML format so the store does not depend on
 * any private framework class and stays stable across versions. The on-disk file is written
 * atomically; in-memory instances are cached per file so repeated `getSharedPreferences` calls
 * share state exactly as the platform guarantees.
 */
class RedirectPrefs private constructor(private val file: File) : SharedPreferences {
    private val map = LinkedHashMap<String, Any?>()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val lock = Any()

    init { synchronized(lock) { load() } }

    private fun load() {
        map.clear()
        if (!file.isFile) return
        try {
            val o = JSONObject(file.readText())
            for (k in o.keys()) {
                when (val v = o.get(k)) {
                    is JSONArray -> map[k] = HashSet<String>().apply { for (i in 0 until v.length()) add(v.getString(i)) }
                    else -> map[k] = v
                }
            }
        } catch (_: Exception) {
            // Corrupt store is treated as empty rather than crashing the guest.
        }
    }

    override fun getAll(): MutableMap<String, *> = synchronized(lock) { HashMap(map) }
    override fun getString(key: String, defValue: String?) = synchronized(lock) { map[key] as? String ?: defValue }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        synchronized(lock) { (map[key] as? Set<String>)?.toMutableSet() ?: defValues }
    override fun getInt(key: String, defValue: Int) = synchronized(lock) { (map[key] as? Number)?.toInt() ?: defValue }
    override fun getLong(key: String, defValue: Long) = synchronized(lock) { (map[key] as? Number)?.toLong() ?: defValue }
    override fun getFloat(key: String, defValue: Float) = synchronized(lock) { (map[key] as? Number)?.toFloat() ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean) = synchronized(lock) { map[key] as? Boolean ?: defValue }
    override fun contains(key: String) = synchronized(lock) { map.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(l) }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(l) }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, Any?>()
        private val removes = HashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values?.let { HashSet(it) } }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { removes.add(key) }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            val changed = apply(persist = true)
            return changed
        }
        override fun apply() { apply(persist = true) }

        private fun apply(persist: Boolean): Boolean {
            val changedKeys = ArrayList<String>()
            synchronized(lock) {
                if (clear) { changedKeys.addAll(map.keys); map.clear() }
                for (k in removes) if (map.remove(k) != null) changedKeys.add(k)
                for ((k, v) in puts) {
                    if (v == null) { if (map.remove(k) != null) changedKeys.add(k) }
                    else if (map[k] != v) { map[k] = v; changedKeys.add(k) }
                }
                if (persist) save()
            }
            for (l in listeners) for (k in changedKeys) runCatching { l.onSharedPreferenceChanged(this@RedirectPrefs, k) }
            return true
        }

        private fun save() {
            val o = JSONObject()
            for ((k, v) in map) o.put(k, if (v is Set<*>) JSONArray(v.toList()) else v)
            Files.writeAtomic(file, o.toString())
        }
    }

    companion object {
        private val cache = HashMap<String, RedirectPrefs>()

        fun get(base: Context, file: File, name: String, mode: Int): SharedPreferences = synchronized(cache) {
            cache.getOrPut(file.path) { RedirectPrefs(file) }
        }

        fun forget(file: File) = synchronized(cache) { cache.remove(file.path) }
    }
}
