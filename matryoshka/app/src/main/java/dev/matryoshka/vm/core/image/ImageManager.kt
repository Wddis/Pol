package dev.matryoshka.vm.core.image

import android.content.ContentResolver
import android.net.Uri
import dev.matryoshka.vm.core.model.EngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

@Serializable
data class ImageFile(
    val name: String,
    val sha256: String,
    val size: Long,
    val url: String? = null,
)

/**
 * A guest system image. One image can back any number of VMs: its files are read-only, every VM
 * keeps its writable state (userdata) in its own directory.
 *
 * Files by engine:
 *  - HYPERVISOR / EMULATOR: `kernel`, `ramdisk.img`, `boot.img` (for Magisk patching), `system.img`,
 *    `vendor.img`.
 *  - CONTAINER: `rootfs.tar.gz` (merged system + vendor tree, see guest/container/).
 */
@Serializable
data class GuestImage(
    val id: String,
    val name: String,
    val androidVersion: String = "11",
    val abi: String = "arm64-v8a",
    val engines: List<EngineType>,
    val files: List<ImageFile>,
    val kernelCmdline: String = "",
    val description: String = "",
    val licenseNotice: String = "AOSP (Apache-2.0) and Linux kernel (GPL-2.0). Sources: see guest/ in the Matryoshka repository.",
) {
    val totalSize: Long get() = files.sumOf { it.size }
    fun filesFor(engine: EngineType): List<ImageFile> = when (engine) {
        EngineType.CONTAINER -> files.filter { it.name == "rootfs.tar.gz" }
        else -> files.filter { it.name != "rootfs.tar.gz" }
    }
}

@Serializable
data class ImageCatalog(val version: Int = 1, val images: List<GuestImage> = emptyList())

data class DownloadProgress(val imageId: String, val file: String, val done: Long, val total: Long) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

class ImageManager(private val root: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val _installed = MutableStateFlow<List<GuestImage>>(emptyList())
    val installed: StateFlow<List<GuestImage>> = _installed.asStateFlow()

    init {
        root.mkdirs()
        _installed.value = scan()
    }

    /** Re-reads the images directory (e.g. after files were placed there by other means). */
    fun refresh() { _installed.value = scan() }

    fun dirOf(imageId: String) = File(root, imageId)
    fun file(imageId: String, name: String) = File(dirOf(imageId), name)
    fun get(imageId: String) = _installed.value.firstOrNull { it.id == imageId }

    fun isComplete(image: GuestImage, engine: EngineType): Boolean =
        image.filesFor(engine).all { file(image.id, it.name).length() == it.size }

    suspend fun fetchCatalog(url: String): ImageCatalog = withContext(Dispatchers.IO) {
        val conn = open(url)
        conn.inputStream.use { json.decodeFromString(ImageCatalog.serializer(), it.readBytes().decodeToString()) }
    }

    /** Downloads (resumably) and verifies every file of [image] needed by [engine]. */
    suspend fun download(image: GuestImage, engine: EngineType, onProgress: (DownloadProgress) -> Unit) =
        withContext(Dispatchers.IO) {
            val dir = dirOf(image.id).apply { mkdirs() }
            writeManifest(image)
            for (f in image.filesFor(engine)) {
                val target = File(dir, f.name)
                if (target.length() == f.size && sha256(target) == f.sha256.lowercase()) continue
                val url = f.url ?: throw IOException("No download URL for ${f.name}")
                val part = File(dir, f.name + ".part")
                if (part.length() > f.size) part.delete()
                var attempt = 0
                while (true) {
                    try {
                        downloadResumable(url, part, f, image.id, onProgress)
                        break
                    } catch (e: IOException) {
                        if (++attempt >= 4) throw e
                        Thread.sleep(2000L shl attempt)
                    }
                }
                val digest = sha256(part)
                if (digest != f.sha256.lowercase()) {
                    part.delete()
                    throw IOException("Checksum mismatch for ${f.name}")
                }
                if (!part.renameTo(target)) throw IOException("Cannot finalize ${f.name}")
            }
            _installed.value = scan()
        }

    /**
     * Imports a local image bundle: a zip with `manifest.json` (a [GuestImage]) and the files it
     * lists. Lets users build their own image (guest/ scripts) and side-load it without hosting.
     */
    suspend fun importBundle(resolver: ContentResolver, uri: Uri, onProgress: (DownloadProgress) -> Unit): GuestImage =
        withContext(Dispatchers.IO) {
            val staging = File(root, ".import-${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                var copied = 0L
                resolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(raw.buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val name = entry.name.substringAfterLast('/')
                            if (entry.isDirectory || name.isEmpty() || name.startsWith(".")) continue
                            File(staging, name).outputStream().use { out ->
                                copyWithProgress(zip, out) { n ->
                                    copied += n
                                    onProgress(DownloadProgress("import", name, copied, -1))
                                }
                            }
                        }
                    }
                } ?: throw IOException("Cannot open bundle")
                val manifestFile = File(staging, "manifest.json")
                if (!manifestFile.exists()) throw IOException("manifest.json missing in bundle")
                val image = json.decodeFromString(GuestImage.serializer(), manifestFile.readText())
                for (f in image.files) {
                    val local = File(staging, f.name)
                    if (!local.exists()) continue // engine-specific files may be omitted
                    if (sha256(local) != f.sha256.lowercase()) throw IOException("Checksum mismatch for ${f.name}")
                }
                val target = dirOf(image.id)
                target.deleteRecursively()
                if (!staging.renameTo(target)) throw IOException("Cannot install image")
                _installed.value = scan()
                image
            } finally {
                staging.deleteRecursively()
            }
        }

    /** Deletes an image's non-root files. Root-owned extracted rootfs is removed by the container engine. */
    suspend fun delete(imageId: String) = withContext(Dispatchers.IO) {
        dirOf(imageId).deleteRecursively()
        _installed.value = scan()
    }

    private fun writeManifest(image: GuestImage) {
        file(image.id, "manifest.json").writeText(json.encodeToString(GuestImage.serializer(), image))
    }

    private fun scan(): List<GuestImage> = root.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith(".") }
        .mapNotNull { dir ->
            runCatching { json.decodeFromString(GuestImage.serializer(), File(dir, "manifest.json").readText()) }.getOrNull()
        }

    private suspend fun downloadResumable(
        url: String, part: File, f: ImageFile, imageId: String, onProgress: (DownloadProgress) -> Unit,
    ) {
        val start = part.length()
        if (start == f.size) return
        val conn = open(url, rangeFrom = start)
        val append = start > 0 && conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        var done = if (append) start else 0L
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, append).use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(DownloadProgress(imageId, f.name, done, f.size))
                }
            }
        }
        if (part.length() != f.size) throw IOException("Incomplete download of ${f.name}")
    }

    private fun open(url: String, rangeFrom: Long = 0): HttpURLConnection {
        var current = URL(url)
        repeat(8) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Matryoshka-VM")
            if (rangeFrom > 0) conn.setRequestProperty("Range", "bytes=$rangeFrom-")
            when (conn.responseCode) {
                in 300..399 -> {
                    current = URL(current, conn.getHeaderField("Location") ?: throw IOException("Bad redirect"))
                    conn.disconnect()
                }
                in 200..299 -> return conn
                else -> throw IOException("HTTP ${conn.responseCode} for $current")
            }
        }
        throw IOException("Too many redirects")
    }

    companion object {
        fun sha256(file: File): String = file.inputStream().use { sha256(it) }

        fun sha256(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun copyWithProgress(input: InputStream, out: java.io.OutputStream, onChunk: (Int) -> Unit) {
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                onChunk(n)
            }
        }
    }
}
