package com.atlant.space.engine.runtime.env

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.CursorFactory
import android.os.Build
import com.atlant.space.engine.runtime.VirtualRuntime
import com.atlant.space.engine.util.Files
import java.io.File

/**
 * Context whose storage accessors point into a space's private data directory instead of the
 * host app's. Everything a guest reads or writes through the standard Context API —
 * SharedPreferences, databases, `getFilesDir()`, `getCacheDir()`, `getDir()` — lands under
 * `vspaces/Space_N/data/<pkg>/`, so two spaces (or a space and the host) never share state.
 *
 * External storage is redirected too, to the space's virtual `/sdcard`. This covers the large
 * majority of real apps, which reach storage through Context; raw `/data/data/<pkg>` string
 * paths in native code are handled separately by the IO redirector.
 */
class RedirectContext(
    base: Context,
    private val packageName: String,
    private val dataDir: File,
    private val extFilesDir: File,
    private val obbDir: File,
) : ContextWrapper(base) {

    private val filesDir get() = ensure(File(dataDir, "files"))
    private val cacheDir get() = ensure(File(dataDir, "cache"))
    private val codeCacheDir get() = ensure(File(dataDir, "code_cache"))
    private val prefsDir get() = ensure(File(dataDir, "shared_prefs"))
    private val dbDir get() = ensure(File(dataDir, "databases"))
    private val noBackupDir get() = ensure(File(dataDir, "no_backup"))

    private fun ensure(f: File) = f.also { Files.ensureDir(it) }

    override fun getPackageName(): String = packageName
    override fun getDataDir(): File = ensure(dataDir)
    override fun getFilesDir(): File = filesDir
    override fun getCacheDir(): File = cacheDir
    override fun getCodeCacheDir(): File = codeCacheDir
    override fun getNoBackupFilesDir(): File = noBackupDir
    override fun getDir(name: String, mode: Int): File = ensure(File(dataDir, "app_$name"))

    override fun getFileStreamPath(name: String): File = File(filesDir, name)
    override fun fileList(): Array<String> = filesDir.list() ?: emptyArray()
    override fun openFileInput(name: String) = File(filesDir, name).inputStream()
    override fun openFileOutput(name: String, mode: Int) =
        java.io.FileOutputStream(File(filesDir, name), mode and Context.MODE_APPEND != 0)

    override fun getDatabasePath(name: String): File =
        if (File(name).isAbsolute) File(name) else File(dbDir, name)

    override fun databaseList(): Array<String> =
        dbDir.list { _, n -> !n.endsWith("-journal") && !n.endsWith("-wal") && !n.endsWith("-shm") } ?: emptyArray()

    override fun deleteDatabase(name: String): Boolean {
        val db = getDatabasePath(name)
        var ok = db.delete()
        listOf("-journal", "-wal", "-shm").forEach { File(db.path + it).delete() }
        return ok
    }

    override fun openOrCreateDatabase(name: String, mode: Int, factory: CursorFactory?): SQLiteDatabase =
        openOrCreateDatabase(name, mode, factory, null)

    override fun openOrCreateDatabase(name: String, mode: Int, factory: CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?): SQLiteDatabase {
        val path = getDatabasePath(name)
        ensure(path.parentFile!!)
        var flags = SQLiteDatabase.CREATE_IF_NECESSARY
        if (mode and Context.MODE_ENABLE_WRITE_AHEAD_LOGGING != 0) flags = flags or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        return SQLiteDatabase.openDatabase(path.path, factory, flags, errorHandler)
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        // Keep SharedPreferences files under the space so they are isolated and backed up with it.
        val file = File(prefsDir, "$name.xml")
        return RedirectPrefs.get(baseContext, file, name, mode)
    }

    override fun moveSharedPreferencesFrom(sourceContext: Context, name: String): Boolean = false
    override fun deleteSharedPreferences(name: String): Boolean {
        RedirectPrefs.forget(File(prefsDir, "$name.xml"))
        return File(prefsDir, "$name.xml").delete()
    }

    override fun getExternalFilesDir(type: String?): File =
        ensure(if (type == null) File(extFilesDir, "files") else File(extFilesDir, type))

    override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(getExternalFilesDir(type))

    override fun getExternalCacheDir(): File = ensure(File(extFilesDir, "cache"))
    override fun getExternalCacheDirs(): Array<File> = arrayOf(externalCacheDir)

    override fun getObbDir(): File = ensure(obbDir)
    override fun getObbDirs(): Array<File> = arrayOf(ensure(obbDir))

    override fun getApplicationContext(): Context = this

    override fun createDeviceProtectedStorageContext(): Context = this
    override fun isDeviceProtectedStorage(): Boolean = false

    companion object {
        /** Wraps the guest's real [base] context (built by the platform from the guest LoadedApk). */
        fun wrap(base: Context): RedirectContext {
            val g = VirtualRuntime.guest(base.packageName)
                ?: VirtualRuntime.hostingPackage?.let { VirtualRuntime.guest(it) }
                ?: error("No loaded guest for ${base.packageName}")
            return RedirectContext(
                base, g.record.packageName, File(g.dataDir),
                File(g.extDataDir), File(g.extDataDir, "obb"),
            )
        }
    }
}
