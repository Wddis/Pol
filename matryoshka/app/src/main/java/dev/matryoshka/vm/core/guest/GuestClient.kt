package dev.matryoshka.vm.core.guest

import dev.matryoshka.vm.core.adb.AdbConnection
import dev.matryoshka.vm.core.adb.AdbKey
import dev.matryoshka.vm.core.adb.AdbSync
import dev.matryoshka.vm.core.adb.RemoteEntry
import dev.matryoshka.vm.core.host.shQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class GuestApp(
    val packageName: String,
    val apkPaths: List<String>,
    val versionCode: Long,
    val isSystem: Boolean,
    val enabled: Boolean = true,
)

data class ApkSource(val name: String, val size: Long, val open: () -> InputStream)

/**
 * Talks to a running guest through its adbd. Everything the app does *inside* the virtual device
 * (installing / exporting apps, file exchange, power, root provisioning) goes through here.
 */
class GuestClient(
    private val host: String,
    private val port: Int,
    private val key: AdbKey,
) {
    private val mutex = Mutex()
    @Volatile private var conn: AdbConnection? = null

    suspend fun connection(): AdbConnection = mutex.withLock {
        conn?.takeIf { it.isAlive } ?: withContext(Dispatchers.IO) {
            AdbConnection.connect(host, port, key).also { conn = it }
        }
    }

    val isConnected: Boolean get() = conn?.isAlive == true

    /** Retries until adbd in the guest answers (it starts early in boot). */
    suspend fun awaitAdb(timeoutMs: Long, onAttempt: (Int) -> Unit = {}): Boolean =
        withTimeoutOrNull(timeoutMs) {
            var attempt = 0
            while (true) {
                onAttempt(++attempt)
                if (runCatching { connection() }.isSuccess) return@withTimeoutOrNull true
                delay(1500)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false

    suspend fun awaitBootCompleted(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (getprop("sys.boot_completed") != "1") delay(1000)
            true
        } ?: false

    /** Runs a command without a pty; returns stdout+stderr and the exit status. */
    suspend fun shell(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val marker = "__MVM_EXIT__"
        val stream = connection().open("exec:($command) 2>&1; echo \"$marker\$?\"")
        val text = stream.use { it.readAllText() }
        val idx = text.lastIndexOf(marker)
        if (idx < 0) 1 to text
        else (text.substring(idx + marker.length).trim().toIntOrNull() ?: 1) to text.substring(0, idx).trimEnd()
    }

    suspend fun getprop(name: String): String = shell("getprop ${name.shQuote()}").second.trim()

    suspend fun props(): Map<String, String> {
        val out = shell("getprop").second
        val re = Regex("""^\[(.+?)]: \[(.*)]$""")
        return out.lineSequence().mapNotNull { re.find(it)?.destructured?.let { (k, v) -> k to v } }.toMap()
    }

    // ---------------------------------------------------------------- apps

    suspend fun listApps(includeSystem: Boolean): List<GuestApp> {
        val third = parsePackages(shell("pm list packages -f -3 --show-versioncode").second, isSystem = false)
        val system = if (includeSystem) parsePackages(shell("pm list packages -f -s --show-versioncode").second, isSystem = true) else emptyList()
        val disabled = shell("pm list packages -d").second.lineSequence()
            .map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()
        return (third + system).map { it.copy(enabled = it.packageName !in disabled) }.sortedBy { it.packageName }
    }

    /**
     * Installs one APK or a split set. Streams bytes straight into `pm` — no temp copy in the guest.
     */
    suspend fun install(apks: List<ApkSource>, onProgress: (Float) -> Unit = {}): String = withContext(Dispatchers.IO) {
        require(apks.isNotEmpty())
        val total = apks.sumOf { it.size }.coerceAtLeast(1)
        var sent = 0L
        if (apks.size == 1) {
            val apk = apks.single()
            val s = connection().open("exec:cmd package install -r -S ${apk.size}")
            apk.open().use { input -> pump(input, s.outputStream) { n -> sent += n; onProgress(sent.toFloat() / total) } }
            val out = s.use { it.readAllText() }.trim()
            if (!out.contains("Success")) throw IOException(out.ifBlank { "Installation failed" })
            return@withContext out
        }
        val create = shell("cmd package install-create -r -S $total").second
        val session = Regex("""\[(\d+)]""").find(create)?.groupValues?.get(1) ?: throw IOException(create)
        try {
            apks.forEachIndexed { i, apk ->
                val s = connection().open("exec:cmd package install-write -S ${apk.size} $session ${i}_${apk.name.shQuote()} -")
                apk.open().use { input -> pump(input, s.outputStream) { n -> sent += n; onProgress(sent.toFloat() / total) } }
                val out = s.use { it.readAllText() }
                if (!out.contains("Success")) throw IOException(out)
            }
            val commit = shell("cmd package install-commit $session").second
            if (!commit.contains("Success")) throw IOException(commit)
            commit
        } catch (e: Exception) {
            shell("cmd package install-abandon $session")
            throw e
        }
    }

    suspend fun uninstall(pkg: String): Boolean = shell("pm uninstall ${pkg.shQuote()}").second.contains("Success")

    suspend fun launch(pkg: String) {
        shell("monkey -p ${pkg.shQuote()} -c android.intent.category.LAUNCHER 1")
    }

    suspend fun forceStop(pkg: String) { shell("am force-stop ${pkg.shQuote()}") }
    suspend fun clearData(pkg: String) = shell("pm clear ${pkg.shQuote()}").second.contains("Success")
    suspend fun setEnabled(pkg: String, enabled: Boolean) {
        shell(if (enabled) "pm enable ${pkg.shQuote()}" else "pm disable-user --user 0 ${pkg.shQuote()}")
    }

    /** Copies all APK files of [pkg] out of the guest (base.apk + splits). */
    suspend fun exportApk(pkg: String, sinkFor: (fileName: String) -> OutputStream) = withContext(Dispatchers.IO) {
        val paths = shell("pm path ${pkg.shQuote()}").second.lineSequence()
            .map { it.removePrefix("package:").trim() }.filter { it.endsWith(".apk") }.toList()
        if (paths.isEmpty()) throw IOException("Package $pkg not found")
        sync { sync -> paths.forEach { p -> sinkFor(p.substringAfterLast('/')).use { out -> sync.pull(p, out) } } }
        paths
    }

    /** Pulls only the base APK into [dest] (used to read the label/icon on the host). */
    suspend fun pullBaseApk(app: GuestApp, dest: File) = withContext(Dispatchers.IO) {
        val base = app.apkPaths.firstOrNull { it.endsWith("base.apk") } ?: app.apkPaths.first()
        sync { s -> dest.outputStream().buffered().use { s.pull(base, it) } }
        dest
    }

    suspend fun apkSize(app: GuestApp): Long =
        sync { s -> app.apkPaths.firstOrNull()?.let { s.stat(it)?.size } ?: 0L }

    // ---------------------------------------------------------------- files

    suspend fun list(path: String): List<RemoteEntry> = sync { it.list(path) }
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

    suspend fun push(input: InputStream, remotePath: String, onProgress: (Long) -> Unit = {}) =
        sync { it.push(input, remotePath, onProgress = onProgress) }

    suspend fun pull(remotePath: String, out: OutputStream, onProgress: (Long) -> Unit = {}) =
        sync { it.pull(remotePath, out, onProgress) }

    suspend fun mkdir(path: String) = shell("mkdir -p ${path.shQuote()}")
    suspend fun delete(path: String) = shell("rm -rf ${path.shQuote()}")

    /** Ask the media scanner to index a pushed file so gallery/file apps see it. */
    suspend fun scanMedia(path: String) {
        shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d ${("file://$path").shQuote()}")
    }

    // ---------------------------------------------------------------- power & system

    suspend fun powerOff(): Boolean = runCatching { shell("svc power shutdown || reboot -p") }.isSuccess

    suspend fun reboot() { runCatching { shell("svc power reboot || reboot") } }

    suspend fun applyDisplay(width: Int, height: Int, dpi: Int) {
        shell("wm size ${width}x$height; wm density $dpi")
    }

    suspend fun isRooted(): Boolean = shell("su -c id 2>/dev/null || id").second.contains("uid=0")

    suspend fun <T> sync(block: (AdbSync) -> T): T = withContext(Dispatchers.IO) {
        AdbSync(connection().open("sync:")).use(block)
    }

    fun close() {
        conn?.close()
        conn = null
    }

    companion object {
        /**
         * Parses `pm list packages -f --show-versioncode` lines such as
         * `package:/data/app/~~Ab==/com.foo-Cd==/base.apk=com.foo versionCode:42`.
         * The APK path itself may contain '=', so we split at the ".apk=" boundary.
         */
        fun parsePackages(output: String, isSystem: Boolean): List<GuestApp> =
            output.lineSequence().mapNotNull { raw ->
                val line = raw.trim().removePrefix("package:")
                val apkEnd = line.lastIndexOf(".apk=")
                if (apkEnd < 0) return@mapNotNull null
                val path = line.substring(0, apkEnd + 4)
                val rest = line.substring(apkEnd + 5)
                val pkg = rest.substringBefore(' ')
                val version = Regex("""versionCode:(\d+)""").find(rest)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                GuestApp(pkg, listOf(path), version, isSystem)
            }.toList()

        private fun pump(input: InputStream, out: OutputStream, onChunk: (Int) -> Unit) {
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                onChunk(n)
            }
            out.flush()
        }
    }
}
