package com.atlant.space.engine.storage

import android.content.Context
import com.atlant.space.engine.util.Files
import java.io.File
import java.io.IOException

/** Result of [VirtualStorageManager.verify]. */
data class IntegrityReport(val checked: Int, val repaired: List<String>, val problems: List<String>) {
    val ok: Boolean get() = problems.isEmpty()
}

data class StorageUsage(val apps: Long, val data: Long, val shared: Long) {
    val total: Long get() = apps + data + shared
}

/**
 * Layout of a space on disk.
 *
 * Two roots exist per space, mirroring how Android itself separates storage:
 *
 *  - **shared root** — the space's virtual `/sdcard`:
 *    `<external files>/Spaces/Space_N/system/{Android/data,Android/obb,Android/media,Download,...}`.
 *    Guest calls for external storage are redirected here.
 *  - **private root** — the equivalent of `/data`: installed packages (`apps/<pkg>`) and
 *    app-private data (`data/<pkg>`: shared_prefs, databases, files, cache). It is kept in the
 *    host's internal storage because that is the only place where native code can be loaded
 *    from, file locking and SQLite WAL behave correctly, and other apps cannot read credentials.
 */
class VirtualStorageManager(private val context: Context) {

    private val privateBase: File = File(context.filesDir, "vspaces")

    /** External app-specific storage; falls back to internal storage when no volume is mounted. */
    val sharedBase: File
        get() = File(context.getExternalFilesDir(null) ?: File(context.filesDir, "external"), "Spaces")

    val archivesDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "Archives")
    val backupsDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "Backups")
    val stagingDir: File get() = File(context.cacheDir, "staging")

    fun privateRoot(dirName: String) = File(privateBase, dirName)
    fun spaceMetaFile(dirName: String) = File(privateRoot(dirName), "space.json")
    fun appsDir(dirName: String) = File(privateRoot(dirName), "apps")
    fun appDir(dirName: String, pkg: String) = File(appsDir(dirName), checkPkg(pkg))
    fun dataRoot(dirName: String) = File(privateRoot(dirName), "data")
    fun dataDir(dirName: String, pkg: String) = File(dataRoot(dirName), checkPkg(pkg))

    fun spaceShared(dirName: String) = File(sharedBase, dirName)
    /** The virtual `/sdcard` of a space. */
    fun sharedRoot(dirName: String) = File(spaceShared(dirName), "system")
    fun extDataDir(dirName: String, pkg: String) = File(sharedRoot(dirName), "Android/data/${checkPkg(pkg)}")
    fun obbDir(dirName: String, pkg: String) = File(sharedRoot(dirName), "Android/obb/${checkPkg(pkg)}")
    fun mediaDir(dirName: String, pkg: String) = File(sharedRoot(dirName), "Android/media/${checkPkg(pkg)}")

    fun listSpaceDirs(): List<String> =
        privateBase.listFiles { f -> f.isDirectory && f.name.startsWith("Space_") }?.map { it.name }.orEmpty()

    /** Creates the directory skeleton of a space. Idempotent. */
    fun provision(dirName: String) {
        Files.ensureDir(appsDir(dirName))
        Files.ensureDir(dataRoot(dirName))
        val root = sharedRoot(dirName)
        for (d in STANDARD_DIRS) Files.ensureDir(File(root, d))
    }

    /** Creates the data directories of one package. Called on install and before each launch. */
    fun provisionPackage(dirName: String, pkg: String) {
        val data = dataDir(dirName, pkg)
        for (d in listOf("files", "cache", "code_cache", "databases", "shared_prefs")) Files.ensureDir(File(data, d))
        Files.ensureDir(File(extDataDir(dirName, pkg), "files"))
        Files.ensureDir(File(extDataDir(dirName, pkg), "cache"))
        Files.ensureDir(obbDir(dirName, pkg))
    }

    fun deleteSpace(dirName: String) {
        val a = Files.deleteTree(privateRoot(dirName))
        val b = Files.deleteTree(spaceShared(dirName))
        if (!a || !b) throw IOException("Some files of $dirName could not be deleted")
    }

    fun deletePackageData(dirName: String, pkg: String, keepObb: Boolean = false) {
        var ok = Files.deleteTree(dataDir(dirName, pkg))
        ok = Files.deleteTree(extDataDir(dirName, pkg)) && ok
        ok = Files.deleteTree(mediaDir(dirName, pkg)) && ok
        if (!keepObb) ok = Files.deleteTree(obbDir(dirName, pkg)) && ok
        if (!ok) throw IOException("Some data of $pkg could not be deleted")
    }

    fun clearCache(dirName: String, pkg: String): Long {
        val dirs = listOf(
            File(dataDir(dirName, pkg), "cache"), File(dataDir(dirName, pkg), "code_cache"),
            File(extDataDir(dirName, pkg), "cache"),
        )
        val freed = dirs.sumOf { Files.sizeOf(it) }
        var ok = true
        dirs.forEach { ok = Files.clearDir(it) && ok }
        if (!ok) throw IOException("Cache of $pkg could not be cleared completely")
        return freed
    }

    fun usage(dirName: String) = StorageUsage(
        Files.sizeOf(appsDir(dirName)), Files.sizeOf(dataRoot(dirName)), Files.sizeOf(spaceShared(dirName)),
    )

    fun packageUsage(dirName: String, pkg: String) = StorageUsage(
        Files.sizeOf(appDir(dirName, pkg)),
        Files.sizeOf(dataDir(dirName, pkg)),
        Files.sizeOf(extDataDir(dirName, pkg)) + Files.sizeOf(obbDir(dirName, pkg)) + Files.sizeOf(mediaDir(dirName, pkg)),
    )

    fun cacheSize(dirName: String, pkg: String): Long =
        Files.sizeOf(File(dataDir(dirName, pkg), "cache")) + Files.sizeOf(File(dataDir(dirName, pkg), "code_cache")) +
            Files.sizeOf(File(extDataDir(dirName, pkg), "cache"))

    /**
     * Verifies the storage of a space: required directories exist and are writable, every
     * installed package still has its code, and no stale temp files are left from an
     * interrupted write. Missing directories are recreated; everything else is reported.
     *
     * @param packages package name -> (file, expected SHA-256) of each installed code file.
     */
    fun verify(dirName: String, packages: Map<String, List<Pair<File, String>>>, deep: Boolean): IntegrityReport {
        val repaired = ArrayList<String>()
        val problems = ArrayList<String>()
        var checked = 0

        fun need(dir: File) {
            checked++
            if (!dir.isDirectory) {
                if (dir.mkdirs()) repaired.add("recreated ${dir.path}") else problems.add("missing ${dir.path}")
            }
        }
        need(appsDir(dirName)); need(dataRoot(dirName))
        val root = sharedRoot(dirName)
        STANDARD_DIRS.forEach { need(File(root, it)) }

        for (base in listOf(privateRoot(dirName), root)) {
            checked++
            val probe = File(base, ".atlant_probe")
            try {
                probe.writeText("ok")
                if (probe.readText() != "ok") problems.add("read-back mismatch in ${base.path}")
            } catch (e: Exception) {
                problems.add("not writable: ${base.path} (${e.message})")
            } finally {
                probe.delete()
            }
        }

        privateRoot(dirName).walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".tmp") }.forEach {
            if (it.delete()) repaired.add("removed stale ${it.name}")
        }

        for ((pkg, files) in packages) {
            for ((file, sha) in files) {
                checked++
                when {
                    !file.isFile -> problems.add("$pkg: missing ${file.name}")
                    file.length() == 0L -> problems.add("$pkg: empty ${file.name}")
                    deep && sha.isNotEmpty() && Files.sha256(file) != sha -> problems.add("$pkg: ${file.name} was modified")
                }
            }
            need(dataDir(dirName, pkg))
        }
        return IntegrityReport(checked, repaired, problems)
    }

    companion object {
        val STANDARD_DIRS = listOf(
            "Android/data", "Android/obb", "Android/media",
            "Download", "Documents", "Pictures", "Movies", "Music", "DCIM",
        )
        private val PKG = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        fun isValidPackageName(pkg: String) = pkg.length <= 223 && PKG.matches(pkg)

        /** Package names become directory names, so they are validated before touching disk. */
        fun checkPkg(pkg: String): String {
            require(isValidPackageName(pkg)) { "Invalid package name: $pkg" }
            return pkg
        }
    }
}
