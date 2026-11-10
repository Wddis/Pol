package com.atlant.space.engine.backup

import com.atlant.space.engine.log.EventLog
import com.atlant.space.engine.storage.VirtualStorageManager
import com.atlant.space.engine.util.Files
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Progress callback stages for a backup or restore. */
data class BackupProgress(val done: Long, val total: Long)

data class BackupInfo(val file: File, val spaceId: Int, val spaceName: String, val createdAt: Long, val size: Long)

/**
 * Exports and imports a whole space as a single compressed archive.
 *
 * The archive is an ordinary ZIP (deflated), portable and openable with any tool, containing the
 * space's private tree (installed apps + per-app data) and its virtual `/sdcard`, plus a small
 * `atlant-backup.json` manifest with the space's identity. Restoring recreates the space under a
 * fresh id, so importing a backup never overwrites a live space by accident. Entry names are
 * sanitised on the way out and validated on the way in, so a hand-edited archive cannot write
 * outside the restore directory.
 */
class BackupManager(private val storage: VirtualStorageManager, private val log: EventLog) {

    /** Writes a backup of [spaceDir] to the Backups directory and returns the file. */
    @Throws(IOException::class)
    fun export(spaceId: Int, spaceName: String, spaceDir: String, progress: (BackupProgress) -> Unit = {}): File {
        Files.ensureDir(storage.backupsDir)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val out = File(storage.backupsDir, "${sanitize(spaceName)}-$stamp.atlant")
        val roots = listOf(storage.privateRoot(spaceDir) to "private", storage.spaceShared(spaceDir) to "shared")
        val total = roots.sumOf { Files.sizeOf(it.first) }.coerceAtLeast(1)
        var done = 0L

        try {
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                val manifest = JSONObject()
                    .put("version", 1).put("spaceId", spaceId).put("spaceName", spaceName)
                    .put("createdAt", System.currentTimeMillis())
                zip.putNextEntry(ZipEntry("atlant-backup.json"))
                zip.write(manifest.toString().toByteArray()); zip.closeEntry()

                for ((root, prefix) in roots) {
                    if (!root.isDirectory) continue
                    root.walkTopDown().filter { it.isFile && !Files.isSymlink(it) }.forEach { f ->
                        val rel = "$prefix/" + root.toURI().relativize(f.toURI()).path
                        zip.putNextEntry(ZipEntry(rel))
                        f.inputStream().use { done += Files.copy(it, zip); progress(BackupProgress(done, total)) }
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw if (e is IOException) e else IOException(e.message, e)
        }
        log.i("Backup", "Exported space $spaceId to ${out.name} (${out.length() / 1024} KB)", spaceId)
        return out
    }

    /** Reads just the manifest of an archive without extracting it. */
    fun inspect(archive: File): BackupInfo? = try {
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null && e.name != "atlant-backup.json") { e = zip.nextEntry }
            if (e == null) null else {
                val o = JSONObject(zip.readBytes().decodeToString())
                BackupInfo(archive, o.optInt("spaceId", -1), o.optString("spaceName", archive.name), o.optLong("createdAt"), archive.length())
            }
        }
    } catch (_: Exception) { null }

    fun list(): List<BackupInfo> =
        storage.backupsDir.listFiles { f -> f.isFile && f.name.endsWith(".atlant") }
            ?.mapNotNull { inspect(it) }?.sortedByDescending { it.createdAt }.orEmpty()

    /** Extracts an archive into [targetSpaceDir], which must already be provisioned and empty. */
    @Throws(IOException::class)
    fun restoreInto(archive: File, targetSpaceDir: String, progress: (BackupProgress) -> Unit = {}) {
        val privateRoot = storage.privateRoot(targetSpaceDir)
        val sharedRoot = storage.spaceShared(targetSpaceDir)
        val total = archive.length().coerceAtLeast(1)
        var done = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val name = e.name
                when {
                    name == "atlant-backup.json" -> {}
                    name.startsWith("private/") -> extract(zip, privateRoot, name.removePrefix("private/"))
                    name.startsWith("shared/") -> extract(zip, sharedRoot, name.removePrefix("shared/"))
                }
                done += e.compressedSize.coerceAtLeast(0); progress(BackupProgress(done.coerceAtMost(total), total))
                zip.closeEntry(); e = zip.nextEntry
            }
        }
        log.i("Backup", "Restored ${archive.name} into $targetSpaceDir")
    }

    private fun extract(zip: ZipInputStream, root: File, relative: String) {
        if (relative.isEmpty() || relative.endsWith("/")) return
        val dst = Files.resolveInside(root, relative)   // refuses paths that escape the root
        dst.parentFile?.let { Files.ensureDir(it) }
        dst.outputStream().use { Files.copy(zip, it) }
    }

    private fun sanitize(name: String) =
        name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifEmpty { "space" }
}
