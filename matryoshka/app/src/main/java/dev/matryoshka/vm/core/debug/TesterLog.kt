package dev.matryoshka.vm.core.debug

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import dev.matryoshka.vm.BuildConfig
import dev.matryoshka.vm.core.VmManager
import dev.matryoshka.vm.core.nest.ProbeReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Temporary tester tool: collects everything needed to debug a problem on the tester's phone into
 * one text file and opens the share sheet (Telegram, e-mail, files...). No personal data beyond
 * the device model and this app's own logs is included.
 */
object TesterLog {

    @Volatile var lastProbe: ProbeReport? = null

    suspend fun collect(context: Context, manager: VmManager): File = withContext(Dispatchers.IO) {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(4)?.forEach { it.delete() }
        val file = File(dir, "matryoshka-log-$ts.txt")
        file.bufferedWriter().use { w ->
            w.appendLine("== Matryoshka tester log $ts")
            w.appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG}")
            w.appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
            w.appendLine("fingerprint=${Build.FINGERPRINT}")
            w.appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
            w.appendLine()

            lastProbe?.let { p ->
                w.appendLine("== Nest probe (${Date(p.createdAt)})")
                p.items.forEach { w.appendLine("[${it.level}] ${it.key}: ${it.detail}") }
                w.appendLine("-- raw")
                w.appendLine(p.raw)
            } ?: w.appendLine("== Nest probe: not run yet")
            w.appendLine()

            w.appendLine("== Virtual devices")
            manager.repo.vms.value.forEach { vm ->
                w.appendLine("${vm.name} [${vm.id}] engine=${vm.engine} ram=${vm.memoryMb} cpus=${vm.cpuCores} state=${manager.state(vm.id)}")
                manager.logs.value[vm.id].orEmpty().takeLast(80).forEach { w.appendLine("   $it") }
                manager.repo.logDir(vm.id).listFiles()?.forEach { f ->
                    w.appendLine("   -- ${f.name} (tail)")
                    runCatching { f.readLines().takeLast(60) }.getOrDefault(emptyList()).forEach { w.appendLine("   $it") }
                }
            }
            w.appendLine()

            w.appendLine("== logcat (this app only)")
            runCatching {
                val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "1500", "--pid=${Process.myPid()}")
                    .redirectErrorStream(true).start()
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach { w.appendLine(it) } }
                p.waitFor(5, TimeUnit.SECONDS)
            }.onFailure { w.appendLine("logcat failed: ${it.message}") }
        }
        file
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData makes the read grant follow the intent through the chooser to the target app.
        send.clipData = android.content.ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
