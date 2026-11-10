package com.atlant.space.engine.pm

import com.atlant.space.engine.storage.VirtualStorageManager
import java.io.File

/** An installed guest together with the space it lives in and its on-disk code files. */
data class InstalledApp(
    val spaceId: Int,
    val spaceDir: String,
    val record: VPackage,
    val baseApk: File,
    val apkPaths: List<File>,
    val nativeLibDir: File?,
) {
    val packageName get() = record.packageName
    val label get() = record.label.ifEmpty { record.packageName }
}

/**
 * Read-only view over the packages installed in every space. The installer is the only writer of
 * these directories; this class turns what is on disk into [InstalledApp] records the UI and the
 * runtime consume, so neither has to know the storage layout.
 */
class AppRepository(private val storage: VirtualStorageManager, private val installer: PackageInstaller) {

    /** Every app installed in [spaceDir] (directory name, e.g. "Space_1"), sorted by label. */
    fun installed(spaceDir: String): List<InstalledApp> {
        val apps = storage.appsDir(spaceDir)
        val dirs = apps.listFiles { f -> f.isDirectory && !f.name.endsWith(".installing") && !f.name.endsWith(".previous") }
            ?: return emptyList()
        val spaceId = spaceDir.removePrefix("Space_").toIntOrNull() ?: -1
        return dirs.mapNotNull { dir -> toApp(spaceId, spaceDir, dir) }.sortedBy { it.label.lowercase() }
    }

    fun app(spaceDir: String, pkg: String): InstalledApp? {
        val dir = storage.appDir(spaceDir, pkg)
        if (!dir.isDirectory) return null
        val spaceId = spaceDir.removePrefix("Space_").toIntOrNull() ?: -1
        return toApp(spaceId, spaceDir, dir)
    }

    fun isInstalled(spaceDir: String, pkg: String): Boolean = storage.appDir(spaceDir, pkg).isDirectory

    /** Reads the cached binary manifest an install wrote next to the code. */
    fun manifest(spaceDir: String, pkg: String): ParsedManifest? {
        val f = File(storage.appDir(spaceDir, pkg), PackageInstaller.MANIFEST_CACHE)
        if (!f.isFile) return null
        return try { ParsedManifest.fromJson(org.json.JSONObject(f.readText())) } catch (_: Exception) { null }
    }

    /** Persists new per-app launch options, keeping the rest of the record unchanged. */
    fun updateSettings(spaceDir: String, pkg: String, settings: AppSettings): VPackage? {
        val dir = storage.appDir(spaceDir, pkg)
        val record = installer.readRecord(dir) ?: return null
        val updated = record.copy(settings = settings)
        com.atlant.space.engine.util.Files.writeAtomic(File(dir, PackageInstaller.RECORD), updated.toJson().toString())
        return updated
    }

    /** Removes an app from a space: its code and all of its data. */
    fun uninstall(spaceDir: String, pkg: String) {
        storage.deletePackageData(spaceDir, pkg)
        com.atlant.space.engine.util.Files.deleteTree(storage.appDir(spaceDir, pkg))
    }

    private fun toApp(spaceId: Int, spaceDir: String, dir: File): InstalledApp? {
        val record = installer.readRecord(dir) ?: return null
        val base = File(dir, PackageInstaller.BASE_APK)
        if (!base.isFile) return null
        val apks = record.files.map { File(dir, it.name) }.filter { it.isFile }
        val libDir = if (record.abi.isNotEmpty()) File(dir, "lib/${record.abi}").takeIf { it.isDirectory } else null
        return InstalledApp(spaceId, spaceDir, record, base, apks.ifEmpty { listOf(base) }, libDir)
    }
}
