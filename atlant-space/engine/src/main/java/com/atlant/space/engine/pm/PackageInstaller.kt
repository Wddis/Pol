package com.atlant.space.engine.pm

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import com.atlant.space.engine.storage.VirtualStorageManager
import com.atlant.space.engine.util.Files
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

class InstallException(val code: Code, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Code {
        UNREADABLE, NOT_AN_APK, NO_BASE_APK, SPLIT_MISMATCH, UNSUPPORTED_ABI, SDK_TOO_NEW,
        SIGNATURE_MISMATCH, DOWNGRADE, NO_SPACE, IO, CANCELLED,
    }
}

data class InstallProgress(val stage: Stage, val fraction: Float) {
    enum class Stage { COPYING, UNPACKING, VERIFYING, NATIVE_LIBS, COMMITTING }
}

data class InstallResult(val pkg: VPackage, val updated: Boolean, val obbFiles: Int)

/**
 * Installs APK, split-APK sets and bundle archives (.apks/.xapk/.apkm) into a space.
 *
 * Everything is prepared in a staging directory next to the final location and swapped in
 * with renames, so an interrupted install leaves the previous version intact. Input is
 * treated as hostile: archive entry names are never used as paths, sizes are bounded,
 * and each APK must be accepted by the platform parser before anything is committed.
 */
class PackageInstaller(private val context: Context, private val storage: VirtualStorageManager) {

    /** ABI the guest processes run with — the ABI of this process. */
    val runtimeAbi: String = run {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        abis.firstOrNull() ?: Build.SUPPORTED_ABIS[0]
    }

    @Throws(InstallException::class)
    fun install(
        spaceDir: String,
        sources: List<Uri>,
        allowDowngrade: Boolean = false,
        isCancelled: () -> Boolean = { false },
        progress: (InstallProgress) -> Unit = {},
    ): InstallResult {
        val stage = File(storage.stagingDir, "install-${System.nanoTime()}")
        try {
            Files.ensureDir(stage)
            // 1. Copy the input into private staging so it cannot change under us.
            val inputs = ArrayList<File>()
            sources.forEachIndexed { i, uri ->
                val f = File(stage, "in$i.bin")
                copyIn(uri, f, isCancelled) { progress(InstallProgress(InstallProgress.Stage.COPYING, (i + it) / sources.size)) }
                inputs.add(f)
            }
            return installFiles(spaceDir, inputs, stage, allowDowngrade, isCancelled, progress)
        } catch (e: InstallException) {
            throw e
        } catch (e: IOException) {
            throw InstallException(InstallException.Code.IO, e.message ?: "I/O error", e)
        } finally {
            Files.deleteTree(stage)
        }
    }

    /** Installs from files the engine already owns (clone between spaces, restore). */
    @Throws(InstallException::class)
    fun installLocal(spaceDir: String, apks: List<File>, allowDowngrade: Boolean = true): InstallResult {
        val stage = File(storage.stagingDir, "install-${System.nanoTime()}")
        try {
            Files.ensureDir(stage)
            val inputs = apks.mapIndexed { i, f -> File(stage, "in$i.bin").also { Files.copyFile(f, it) } }
            return installFiles(spaceDir, inputs, stage, allowDowngrade, { false }, {})
        } catch (e: IOException) {
            throw InstallException(InstallException.Code.IO, e.message ?: "I/O error", e)
        } finally {
            Files.deleteTree(stage)
        }
    }

    private fun installFiles(
        spaceDir: String, inputs: List<File>, stage: File, allowDowngrade: Boolean,
        isCancelled: () -> Boolean, progress: (InstallProgress) -> Unit,
    ): InstallResult {
        // 2. Expand bundle archives into individual APKs (+ OBB files for XAPK).
        progress(InstallProgress(InstallProgress.Stage.UNPACKING, 0f))
        val apks = ArrayList<File>()
        val obbs = ArrayList<File>()
        val unpack = Files.ensureDir(File(stage, "unpacked"))
        for (input in inputs) {
            when (classify(input)) {
                Kind.APK -> apks.add(input)
                Kind.BUNDLE -> unpackBundle(input, unpack, apks, obbs, isCancelled)
                Kind.UNKNOWN -> throw InstallException(InstallException.Code.NOT_AN_APK, "The file is not an APK or an APK bundle")
            }
        }
        if (apks.isEmpty()) throw InstallException(InstallException.Code.NOT_AN_APK, "The archive contains no APK files")

        // 3. Parse and cross-check.
        progress(InstallProgress(InstallProgress.Stage.VERIFYING, 0f))
        val parsed = apks.map { apk ->
            val m = try {
                ManifestParser.parseApk(apk)
            } catch (e: IOException) {
                throw InstallException(InstallException.Code.NOT_AN_APK, "Unreadable manifest: ${e.message}", e)
            }
            apk to m
        }
        val bases = parsed.filter { it.second.split == null }
        if (bases.isEmpty()) throw InstallException(InstallException.Code.NO_BASE_APK, "Only split APKs were given; the base APK is missing")
        if (bases.size > 1) throw InstallException(InstallException.Code.SPLIT_MISMATCH, "More than one base APK was given")
        val (baseFile, base) = bases[0]
        val splits = parsed.filter { it.second.split != null }
        for ((_, s) in splits) {
            if (s.packageName != base.packageName || s.versionCode != base.versionCode) {
                throw InstallException(
                    InstallException.Code.SPLIT_MISMATCH,
                    "Split ${s.split} (${s.packageName} ${s.versionCode}) does not belong to ${base.packageName} ${base.versionCode}",
                )
            }
        }
        if (splits.map { it.second.split }.toSet().size != splits.size) {
            throw InstallException(InstallException.Code.SPLIT_MISMATCH, "Duplicate split names")
        }
        if (!VirtualStorageManager.isValidPackageName(base.packageName)) {
            throw InstallException(InstallException.Code.NOT_AN_APK, "Invalid package name")
        }
        if (base.packageName == context.packageName) {
            throw InstallException(InstallException.Code.NOT_AN_APK, "ATLANT SPACE cannot be installed inside itself")
        }
        if (base.minSdk > Build.VERSION.SDK_INT) {
            throw InstallException(InstallException.Code.SDK_TOO_NEW, "Requires Android API ${base.minSdk}, this device has ${Build.VERSION.SDK_INT}")
        }
        // The platform parser must accept the base APK; it is what will load it later.
        val info = archiveInfo(baseFile)
            ?: throw InstallException(InstallException.Code.NOT_AN_APK, "Android rejected the APK (corrupt or unsigned)")
        val signers = signerDigests(info)

        // 4. Native code: the guest runs inside our processes, so it must match their ABI.
        val allFiles = listOf(baseFile) + splits.map { it.first }
        val abisPresent = allFiles.flatMap { nativeAbis(it) }.toSet()
        val abi = when {
            abisPresent.isEmpty() -> ""
            runtimeAbi in abisPresent -> runtimeAbi
            else -> throw InstallException(
                InstallException.Code.UNSUPPORTED_ABI,
                "The app only ships native code for ${abisPresent.sorted().joinToString()}; this device runs guests as $runtimeAbi",
            )
        }

        // 5. Update rules.
        val appDir = storage.appDir(spaceDir, base.packageName)
        val existing = readRecord(appDir)
        if (existing != null) {
            if (existing.signers.isNotEmpty() && signers.isNotEmpty() && existing.signers.toSet() != signers.toSet()) {
                throw InstallException(InstallException.Code.SIGNATURE_MISMATCH, "The update is signed with a different certificate than the installed app")
            }
            if (!allowDowngrade && base.versionCode < existing.versionCode) {
                throw InstallException(InstallException.Code.DOWNGRADE, "Version ${base.versionCode} is older than the installed ${existing.versionCode}")
            }
        }
        val needed = allFiles.sumOf { it.length() } * 2 + obbs.sumOf { it.length() }
        if (storage.appsDir(spaceDir).apply { mkdirs() }.usableSpace < needed) {
            throw InstallException(InstallException.Code.NO_SPACE, "Not enough free storage (${needed / (1024 * 1024)} MB needed)")
        }
        if (isCancelled()) throw InstallException(InstallException.Code.CANCELLED, "Cancelled")

        // 6. Build the new package directory.
        val fresh = File(appDir.parentFile, appDir.name + NEW_SUFFIX)
        Files.deleteTree(fresh)
        Files.ensureDir(fresh)
        try {
            val code = ArrayList<CodeFile>()
            fun place(src: File, name: String) {
                val dst = File(fresh, name)
                if (!src.renameTo(dst)) Files.copyFile(src, dst)
                // Loaded code must not be writable (also required by Android 14+ for dynamic code).
                dst.setReadOnly()
                code.add(CodeFile(name, Files.sha256(dst), dst.length()))
            }
            place(baseFile, BASE_APK)
            splits.sortedBy { it.second.split }.forEachIndexed { i, (f, m) ->
                place(f, "split_${sanitize(m.split!!)}_$i.apk")
                progress(InstallProgress(InstallProgress.Stage.VERIFYING, (i + 1f) / splits.size))
            }
            if (abi.isNotEmpty()) {
                val libDir = Files.ensureDir(File(fresh, "lib/$abi"))
                code.forEachIndexed { i, c ->
                    extractLibs(File(fresh, c.name), abi, libDir, isCancelled)
                    progress(InstallProgress(InstallProgress.Stage.NATIVE_LIBS, (i + 1f) / code.size))
                }
            }
            Files.writeAtomic(File(fresh, MANIFEST_CACHE), base.toJson().toString())

            val now = System.currentTimeMillis()
            val label = try {
                val ai = info.applicationInfo!!
                ai.sourceDir = File(fresh, BASE_APK).path
                ai.publicSourceDir = ai.sourceDir
                context.packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                base.packageName
            }
            val record = VPackage(
                base.packageName, label, base.versionName.ifEmpty { info.versionName ?: "" }, base.versionCode,
                base.minSdk, base.targetSdk, code, abi, signers, base.permissions,
                existing?.installedAt ?: now, now,
                usesGms = "com.google.android.gms.version" in base.metaDataKeys ||
                    base.components.any { it.name.startsWith("com.google.firebase.") || it.name.startsWith("com.google.android.gms.") },
                settings = existing?.settings ?: AppSettings(),
            )
            Files.writeAtomic(File(fresh, RECORD), record.toJson().toString())

            // 7. Swap in.
            progress(InstallProgress(InstallProgress.Stage.COMMITTING, 0f))
            val old = File(appDir.parentFile, appDir.name + OLD_SUFFIX)
            Files.deleteTree(old)
            if (appDir.exists() && !appDir.renameTo(old)) throw IOException("Cannot move the previous version aside")
            if (!fresh.renameTo(appDir)) {
                old.renameTo(appDir)
                throw IOException("Cannot activate the new version")
            }
            Files.deleteTree(old)

            storage.provisionPackage(spaceDir, base.packageName)
            var placedObb = 0
            for (obb in obbs) {
                val dst = File(storage.obbDir(spaceDir, base.packageName), sanitize(obb.name.removePrefix("obb_")))
                if (obb.renameTo(dst) || runCatching { Files.copyFile(obb, dst) }.isSuccess) placedObb++
            }
            progress(InstallProgress(InstallProgress.Stage.COMMITTING, 1f))
            return InstallResult(record, existing != null, placedObb)
        } catch (e: Exception) {
            Files.deleteTree(fresh)
            throw e
        }
    }

    /** Finishes or rolls back installs interrupted by a crash. Called once at engine start. */
    fun recover(spaceDir: String): List<String> {
        val notes = ArrayList<String>()
        val apps = storage.appsDir(spaceDir)
        apps.listFiles()?.forEach { f ->
            when {
                f.name.endsWith(NEW_SUFFIX) -> { Files.deleteTree(f); notes.add("discarded incomplete install ${f.name}") }
                f.name.endsWith(OLD_SUFFIX) -> {
                    val live = File(apps, f.name.removeSuffix(OLD_SUFFIX))
                    if (live.exists()) Files.deleteTree(f) else { f.renameTo(live); notes.add("rolled back ${live.name}") }
                }
            }
        }
        return notes
    }

    fun readRecord(appDir: File): VPackage? {
        val f = File(appDir, RECORD)
        if (!f.isFile) return null
        return try { VPackage.fromJson(org.json.JSONObject(f.readText())) } catch (_: Exception) { null }
    }

    fun archiveInfo(apk: File): PackageInfo? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES or PackageManager.GET_PERMISSIONS)
    } catch (_: Exception) {
        null
    }

    private fun signerDigests(info: PackageInfo): List<String> {
        @Suppress("DEPRECATION")
        val sigs = info.signatures ?: return emptyList()
        return sigs.map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.sorted()
    }

    private enum class Kind { APK, BUNDLE, UNKNOWN }

    private fun classify(f: File): Kind = try {
        ZipFile(f).use { z ->
            when {
                z.getEntry("AndroidManifest.xml") != null -> Kind.APK
                z.entries().asSequence().any { !it.isDirectory && it.name.endsWith(".apk", true) } -> Kind.BUNDLE
                else -> Kind.UNKNOWN
            }
        }
    } catch (_: Exception) {
        Kind.UNKNOWN
    }

    private fun unpackBundle(bundle: File, outDir: File, apks: MutableList<File>, obbs: MutableList<File>, isCancelled: () -> Boolean) {
        ZipFile(bundle).use { z ->
            var n = 0
            var total = 0L
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val lower = e.name.lowercase()
                val isApk = lower.endsWith(".apk")
                val isObb = lower.endsWith(".obb")
                if (!isApk && !isObb) continue
                if (++n > MAX_BUNDLE_ENTRIES) throw InstallException(InstallException.Code.NOT_AN_APK, "Too many files in the bundle")
                // Entry names are untrusted: only the sanitized leaf name is kept, under our own prefix.
                val leaf = sanitize(e.name.substringAfterLast('/').substringAfterLast('\\'))
                val out = File(outDir, (if (isApk) "apk_${n}_" else "obb_") + leaf)
                z.getInputStream(e).use { input ->
                    out.outputStream().use { output ->
                        Files.copy(input, output) { written ->
                            if (total + written > MAX_BUNDLE_BYTES) throw InstallException(InstallException.Code.NO_SPACE, "The bundle expands beyond the supported size")
                            if (isCancelled()) throw InstallException(InstallException.Code.CANCELLED, "Cancelled")
                        }
                    }
                }
                total += out.length()
                if (isApk) apks.add(out) else obbs.add(out)
            }
        }
    }

    private fun nativeAbis(apk: File): Set<String> = ZipFile(apk).use { z ->
        z.entries().asSequence().mapNotNull { e ->
            val p = e.name.split('/')
            if (p.size == 3 && p[0] == "lib" && p[2].endsWith(".so")) p[1] else null
        }.toSet()
    }

    private fun extractLibs(apk: File, abi: String, libDir: File, isCancelled: () -> Boolean) {
        ZipFile(apk).use { z ->
            val prefix = "lib/$abi/"
            for (e in z.entries()) {
                if (e.isDirectory || !e.name.startsWith(prefix)) continue
                val leaf = e.name.substring(prefix.length)
                if (leaf.isEmpty() || '/' in leaf || leaf != sanitize(leaf)) continue
                if (isCancelled()) throw InstallException(InstallException.Code.CANCELLED, "Cancelled")
                val out = File(libDir, leaf)
                z.getInputStream(e).use { i -> out.outputStream().use { o -> Files.copy(i, o) } }
                out.setReadOnly()
            }
        }
    }

    private fun copyIn(uri: Uri, dst: File, isCancelled: () -> Boolean, onFraction: (Float) -> Unit) {
        val length = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) {
            -1L
        }
        val input = try {
            if (uri.scheme == "file") File(uri.path!!).inputStream() else context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw InstallException(InstallException.Code.UNREADABLE, "Cannot open the selected file: ${e.message}", e)
        } ?: throw InstallException(InstallException.Code.UNREADABLE, "Cannot open the selected file")
        input.use { i ->
            dst.outputStream().use { o ->
                Files.copy(i, o) { written ->
                    if (isCancelled()) throw InstallException(InstallException.Code.CANCELLED, "Cancelled")
                    if (length > 0) onFraction((written.toFloat() / length).coerceAtMost(1f))
                }
            }
        }
        if (dst.length() == 0L) throw InstallException(InstallException.Code.UNREADABLE, "The selected file is empty")
    }

    companion object {
        const val BASE_APK = "base.apk"
        const val RECORD = "package.json"
        const val MANIFEST_CACHE = "manifest.json"
        private const val NEW_SUFFIX = ".installing"
        private const val OLD_SUFFIX = ".previous"
        private const val MAX_BUNDLE_ENTRIES = 512
        private const val MAX_BUNDLE_BYTES = 8L * 1024 * 1024 * 1024

        fun sanitize(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').take(120).ifEmpty { "file" }
    }
}
