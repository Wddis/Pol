package dev.matryoshka.vm.core.nest

import android.content.Context
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/** release manifest.json of a Nest guest image (guest/tools/make-nest-rootfs.sh). */
@Serializable
data class NestImageManifest(
    val id: String,
    val name: String,
    val files: List<NestImageFile>,
    val installedSize: Long = 0,
    val description: String = "",
    val licenseNotice: String = "",
)

@Serializable
data class NestImageFile(val name: String, val sha256: String, val size: Long)

/**
 * Tester build: boots the real Android 11 guest under the Nest engine on this phone and shows the
 * boot log, so the engine can be validated on real hardware before it becomes a regular VM type.
 *
 * Layout (short paths: unix socket names are limited to 108 bytes):
 *   <dataDir>/n/r   guest root filesystem (read-mostly, unpacked once)
 *   <dataDir>/n/d   guest /data (kept between boots)
 *   <dataDir>/n/s   engine state of one boot (/dev, sockets, tmpfs mounts, kmsg, logcat.txt)
 */
object NestGuest {
    const val RELEASE = "https://github.com/Wddis/Pol/releases/download/nest-guest-a11"
    /** Pseudo image id of Nest VMs: the guest image is managed here, not by ImageManager. */
    const val IMAGE_ID = "nest-a11"
    const val DEFAULT_ADB_PORT = 15_500

    fun adbListen(port: Int) = "service.adb.listen_addrs=tcp:$port"  // the engine keeps it on loopback

    sealed interface State {
        data object NotInstalled : State
        data class Downloading(val done: Long, val total: Long) : State
        data object Verifying : State
        data class Extracting(val done: Long, val total: Long) : State
        data object Ready : State
        data class Running(val sinceMs: Long) : State
        data class Stopped(val exitCode: Int, val seconds: Long) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var process: Process? = null
    @Volatile var verboseBinder = false
    /** /data of the guest booted last (the tester log reads its crash reports). */
    @Volatile var activeDataDir: File? = null
    @Volatile var useGpu = true
    /** Width / height of the guest's display (the viewer keeps it). */
    @Volatile var displayAspect = 0.5f
    @Volatile var displayWidth = 540
    @Volatile var displayHeight = 1080
    private val json = Json { ignoreUnknownKeys = true }

    private fun base(c: Context) = File(c.dataDir, "n")
    fun rootfs(c: Context) = File(base(c), "r")
    fun dataDir(c: Context) = File(base(c), "d")
    /** /data of one Nest VM (short: guest sockets under /data live below this path). */
    fun magiskTmpDir(c: Context, vmId: String) = File(base(c), "m" + vmId.filter { it.isLetterOrDigit() }.take(8))
    fun vmDataDir(c: Context, vmId: String) = File(base(c), "v" + vmId.filter { it.isLetterOrDigit() }.take(8))
    fun stateDir(c: Context) = File(base(c), "s")
    private fun archive(c: Context) = File(c.filesDir, "images/nest-guest.tar.gz")
    private fun stamp(c: Context) = File(rootfs(c), ".nest-image")
    fun kmsg(c: Context) = File(stateDir(c), "kmsg")
    fun logcat(c: Context) = File(stateDir(c), "logcat.txt")
    fun engineOut(c: Context) = File(base(c), "engine.txt")
    fun binderLog(c: Context) = File(base(c), "binder.txt")

    fun installedImage(c: Context): String? = stamp(c).takeIf { it.exists() }?.readText()?.trim()

    fun refresh(c: Context) {
        if (job?.isActive == true || process != null) return
        _state.value = if (installedImage(c) != null) State.Ready else State.NotInstalled
    }

    /** Downloads (resumable, sha256-checked) and unpacks the guest image. */
    fun install(c: Context) {
        if (job?.isActive == true) return
        val app = c.applicationContext
        job = scope.launch {
            try {
                val manifest = json.decodeFromString(NestImageManifest.serializer(), httpText("$RELEASE/manifest.json"))
                val f = manifest.files.first()
                val tar = archive(app).apply { parentFile?.mkdirs() }
                val free = app.filesDir.usableSpace
                val need = (f.size - tar.length()).coerceAtLeast(0) + manifest.installedSize
                if (free < need + 300_000_000L) throw IOException("Not enough free space: need %.1f GB".format(need / 1e9))
                if (tar.length() != f.size) download("$RELEASE/${manifest.id}.tar.gz", tar, f.size)
                _state.value = State.Verifying
                if (sha256(tar) != f.sha256.lowercase()) {
                    tar.delete()
                    throw IOException("Checksum mismatch: the download was damaged, please retry")
                }
                val root = rootfs(app)
                root.deleteRecursively()
                root.mkdirs()
                extractTarGz(tar, root) { done, total -> _state.value = State.Extracting(done, total) }
                stamp(app).writeText(manifest.id + "\n")
                tar.delete()
                _state.value = State.Ready
            } catch (e: Throwable) {
                _state.value = State.Failed(e.message ?: e.toString())
            }
        }
    }

    /**
     * How one VM boots: its own /data, extra guest properties (--prop) and the host's ADB key the
     * guest's adbd accepts (written to /data/misc/adb/adb_keys).
     */
    data class BootOptions(
        val dataDir: File? = null,
        val props: List<String> = emptyList(),
        val adbPublicKey: String? = null,
        /** Magisk's prepared /debug_ramdisk (Magisk without a boot image), or null. */
        val magiskDir: File? = null,
    )

    /** Boots the guest's init (second stage) under the engine. */
    fun boot(c: Context, opts: BootOptions = BootOptions()) {
        if (process != null || installedImage(c) == null) return
        val app = c.applicationContext
        val lib = app.applicationInfo.nativeLibraryDir
        val st = stateDir(app)
        st.deleteRecursively()  // per-boot state: /dev, sockets, tmpfs mounts
        st.mkdirs()
        val data = opts.dataDir ?: dataDir(app)
        data.mkdirs()
        activeDataDir = data
        opts.adbPublicKey?.let { key ->
            File(data, "misc/adb").apply { mkdirs() }.resolve("adb_keys").writeText(key.trim() + "\n")
        }
        val args = mutableListOf(
            "$lib/libnest_run.so",
            "-b", "/=${rootfs(app).absolutePath}",
            "-b", "/data=${data.absolutePath}",
            "--android", st.absolutePath,
            "-P", "$lib/libnest_preload.so",
        )
        if (File(lib, "libnest_preload32.so").exists()) args += listOf("-P32", "$lib/libnest_preload32.so")
        // Guest screen: 540 px wide at 240 dpi (= the 360 dp layout of 720 px @ 320, with 44 % fewer
        // pixels for the software renderer), the phone's aspect ratio.
        val dm = app.resources.displayMetrics
        val (pw, ph) = minOf(dm.widthPixels, dm.heightPixels) to maxOf(dm.widthPixels, dm.heightPixels)
        val gh = (540L * ph / pw.coerceAtLeast(1)).toInt() and 1.inv()
        args += listOf("--display", "540x${gh}@240")
        displayAspect = 540f / gh.coerceAtLeast(1)
        displayWidth = 540
        displayHeight = gh
        if (useGpu) args += "--gpu"  // the phone's GPU driver for the guest (falls back to software)
        // The guest's adbd: key authentication, loopback only (never reachable from the phone's
        // Wi-Fi), so only this app can talk to it.
        val props = linkedMapOf("ro.adb.secure" to "1", "service.adb.listen_addrs" to "tcp:$DEFAULT_ADB_PORT")
        // The guest's network (nest/guest-agent): one always-up network with the phone's DNS.
        netAgent(app)?.let { args += listOf("--net-agent", it.absolutePath) }
        props["nest.net.dns"] = hostDns(app).joinToString(",")
        props["nest.net.dns_file"] = File(st, "net-dns").absolutePath
        updateDns(app)
        opts.props.forEach { kv -> kv.split('=', limit = 2).let { if (it.size == 2) props[it[0]] = it[1] } }
        props.forEach { (k, v) -> args += listOf("--prop", "$k=$v") }
        opts.magiskDir?.let { args += listOf("--magisk", it.absolutePath) }
        args += listOf("-B", File(st, "b.sock").absolutePath, "-v", "-s", "--", "/system/bin/init", "second_stage")
        val pb = ProcessBuilder(args)
            .redirectErrorStream(true)
            .redirectOutput(engineOut(app))
        pb.environment()["NEST_STATE_DIR"] = st.absolutePath
        binderLog(app).delete()
        if (verboseBinder) pb.environment()["NEST_BINDER_LOG"] = binderLog(app).absolutePath
        val started = System.currentTimeMillis()
        val p = try {
            pb.start()
        } catch (e: IOException) {
            _state.value = State.Failed("Engine did not start: ${e.message}")
            return
        }
        process = p
        _state.value = State.Running(started)
        scope.launch {
            val code = p.waitFor()
            process = null
            _state.value = State.Stopped(code, (System.currentTimeMillis() - started) / 1000)
        }
    }

    val isRunning: Boolean get() = process?.isAlive == true

    /** assets/nest-agent.jar (built from nest/guest-agent) as a file the guest can read. */
    private fun netAgent(c: Context): File? = runCatching {
        val f = File(c.filesDir, "nest/nest-agent.jar")
        f.parentFile?.mkdirs()
        val bytes = c.assets.open("nest-agent.jar").use { it.readBytes() }
        if (!f.exists() || f.length() != bytes.size.toLong() || !f.readBytes().contentEquals(bytes)) {
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
        }
        f
    }.getOrNull()

    /** Tells a running guest the phone's current DNS servers (after a network change). */
    fun updateDns(c: Context) {
        runCatching { File(stateDir(c.applicationContext), "net-dns").writeText(hostDns(c).joinToString(",") + "\n") }
    }

    /** The phone's DNS servers (numeric), public resolvers as the fallback. */
    fun hostDns(c: Context): List<String> {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        val own = runCatching {
            cm?.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers.orEmpty().mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
        return (own + listOf("1.1.1.1", "8.8.8.8")).map { it.substringBefore('%') }.distinct().take(4)
    }

    /** Downloads/unpacks the guest image if needed and waits for it (VM preparation). */
    suspend fun ensureInstalled(c: Context, progress: (String) -> Unit) {
        refresh(c)
        if (installedImage(c) != null) return
        install(c)
        while (true) {
            when (val s = state.value) {
                is State.Downloading -> progress("Downloading Android 11: ${s.done / 1_000_000} / ${s.total / 1_000_000} MB")
                State.Verifying -> progress("Verifying the download")
                is State.Extracting -> progress("Unpacking: ${100 * s.done / s.total.coerceAtLeast(1)} %")
                State.Ready -> return
                is State.Failed -> throw IOException(s.message)
                else -> Unit
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    /** Ends the guest: the engine kills every guest process when it exits (PTRACE_O_EXITKILL). */
    fun stop() {
        val p = process ?: return
        p.destroy()
        scope.launch {
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    fun deleteImage(c: Context) {
        if (process != null) return
        job?.cancel()
        scope.launch {
            base(c.applicationContext).deleteRecursively()
            archive(c.applicationContext).delete()
            _state.value = State.NotInstalled
        }
    }

    /** Last [lines] lines of a (possibly large) text file, read from its end. */
    fun tail(file: File, lines: Int, maxBytes: Int = 256 * 1024): List<String> {
        if (!file.exists()) return emptyList()
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val start = (len - maxBytes).coerceAtLeast(0)
                val buf = ByteArray((len - start).toInt())
                raf.seek(start)
                raf.readFully(buf)
                buf.decodeToString().lines().dropLastWhile { it.isEmpty() }.let { if (start > 0) it.drop(1) else it }
                    .takeLast(lines)
            }
        }.getOrDefault(emptyList())
    }

    /** Where init is: the last "processing action (...)" of the boot log. */
    fun bootStage(c: Context): String? = tail(kmsg(c), 400)
        .lastOrNull { it.contains("processing action (") }
        ?.substringAfter("processing action (")?.substringBefore(")")

    /** Rough progress watcher for the UI: polls until the job ends. */
    fun watch(onTick: () -> Unit): Job = scope.launch {
        while (isActive) {
            onTick()
            delay(1500)
        }
    }

    // ------------------------------------------------------------------ download & unpack

    private fun open(url: String, from: Long = 0): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Matryoshka")
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                u = URL(u, conn.getHeaderField("Location"))
                conn.disconnect()
                return@repeat
            }
            if (code !in 200..299) throw IOException("HTTP $code for ${u.host}")
            return conn
        }
        throw IOException("Too many redirects")
    }

    private suspend fun httpText(url: String): String {
        var attempt = 0
        while (true) {
            try {
                return open(url).inputStream.use { it.readBytes().decodeToString() }
            } catch (e: IOException) {
                if (++attempt >= 5) throw e
                delay(2000L shl attempt)
            }
        }
    }

    private suspend fun download(url: String, target: File, size: Long) {
        var attempt = 0
        while (true) {
            try {
                val have = target.length().let { if (it > size) { target.delete(); 0L } else it }
                val conn = open(url, have)
                val append = have > 0 && conn.responseCode == 206
                var done = if (append) have else 0L
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(target, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var last = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - last > 2_000_000) {
                                last = done
                                _state.value = State.Downloading(done, size)
                            }
                        }
                    }
                }
                if (target.length() == size) return
                throw IOException("Download ended early")
            } catch (e: IOException) {
                if (++attempt >= 6) throw e
                delay(2000L shl attempt.coerceAtMost(4))
            }
        }
    }

    private suspend fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    /**
     * Minimal ustar/GNU/pax reader: regular files, directories, symlinks and hard links (device
     * nodes are skipped: the engine provides /dev). Owner write access is kept on everything
     * the app unpacks, the guest's own permission model is emulated by the engine.
     */
    suspend fun extractTarGz(archive: File, dest: File, progress: (Long, Long) -> Unit) {
        val total = archive.length()
        val counting = Counting(FileInputStream(archive).buffered(1 shl 20))
        val input = GZIPInputStream(counting, 1 shl 16)
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        val dirModes = mutableListOf<Pair<File, Int>>()
        var lastReport = 0L
        input.use {
            while (true) {
                coroutineContext.ensureActive()
                if (counting.count - lastReport > 4_000_000) {
                    lastReport = counting.count
                    progress(counting.count, total)
                }
                if (!readFully(input, header)) break
                if (header.all { it.toInt() == 0 }) break
                val type = header[156].toInt().toChar()
                val size = parseNumber(header, 124, 12)
                val mode = parseNumber(header, 100, 8).toInt()
                var name = longName ?: run {
                    val prefix = cString(header, 345, 155)
                    val n = cString(header, 0, 100)
                    if (prefix.isNotEmpty()) "$prefix/$n" else n
                }
                val link = longLink ?: cString(header, 157, 100)
                longName = null
                longLink = null
                when (type) {
                    'L', 'K' -> {
                        val text = readBytes(input, size).decodeToString().trimEnd('\u0000')
                        if (type == 'L') longName = text else longLink = text
                        continue
                    }
                    'x' -> {
                        val pax = parsePax(readBytes(input, size).decodeToString())
                        longName = pax["path"]
                        longLink = pax["linkpath"]
                        continue
                    }
                    'g' -> { skip(input, size); continue }
                }
                name = name.removePrefix("./").trimEnd('/')
                if (name.isEmpty()) { skip(input, size); continue }
                if (name.startsWith("/") || name.split('/').any { it == ".." }) {  // never outside dest
                    skip(input, size + padding(size))
                    continue
                }
                val out = File(dest, name)
                out.parentFile?.mkdirs()
                when (type) {
                    '0', '\u0000', '7' -> {
                        if (out.exists() || isSymlink(out)) out.delete()
                        java.io.FileOutputStream(out).use { o -> copyN(input, o, size) }
                        skip(input, padding(size))
                        Os.chmod(out.path, (mode and 0x1ff) or 0x180)  // keep owner rw
                        continue
                    }
                    '5' -> {
                        out.mkdirs()
                        dirModes += out to ((mode and 0x1ff) or 0x1c0)  // keep owner rwx
                    }
                    '2' -> {
                        if (out.exists() || isSymlink(out)) out.delete()
                        java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(link))
                    }
                    '1' -> {
                        val src = File(dest, link.removePrefix("./"))
                        if (out.exists() || isSymlink(out)) out.delete()
                        runCatching { java.nio.file.Files.createLink(out.toPath(), src.toPath()) }
                            .onFailure { src.copyTo(out, overwrite = true) }
                    }
                    else -> Unit  // char/block devices, fifos
                }
                skip(input, size + padding(size))
            }
        }
        for ((dir, m) in dirModes.asReversed()) runCatching { Os.chmod(dir.path, m) }
        progress(total, total)
    }

    private fun isSymlink(f: File) = java.nio.file.Files.isSymbolicLink(f.toPath())

    private fun padding(size: Long) = (512 - size % 512) % 512

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return if (off == 0) false else throw IOException("Truncated archive")
            off += n
        }
        return true
    }

    private fun readBytes(input: InputStream, size: Long): ByteArray {
        val b = ByteArray(size.toInt())
        readFully(input, b)
        skip(input, padding(size))
        return b
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw IOException("Truncated archive")
            left -= r
        }
    }

    private fun copyN(input: InputStream, out: java.io.OutputStream, n: Long) {
        var left = n
        val buf = ByteArray(1 shl 16)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw IOException("Truncated archive")
            out.write(buf, 0, r)
            left -= r
        }
    }

    private fun cString(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end].toInt() != 0) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    /** Octal field, or GNU base-256 for large values. */
    private fun parseNumber(h: ByteArray, off: Int, len: Int): Long {
        if (h[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (h[i].toLong() and 0xff)
            return v
        }
        val s = cString(h, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun parsePax(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val bytes = text.toByteArray()
        while (i < bytes.size) {
            var sp = i
            while (sp < bytes.size && bytes[sp] != ' '.code.toByte()) sp++
            val len = String(bytes, i, sp - i).toIntOrNull() ?: break
            val rec = String(bytes, sp + 1, len - (sp + 1 - i) - 1)
            val eq = rec.indexOf('=')
            if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
            i += len
        }
        return out
    }
}
