package dev.matryoshka.vm.core.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ShellResult(val code: Int, val out: String, val err: String) {
    val ok get() = code == 0
}

/**
 * Persistent `su` session on the *host* phone. Only used by engines that need root
 * (hypervisor via raw crosvm, container). Commands are serialised through one process so that
 * the root manager (Magisk / KernelSU / APatch) asks for permission only once.
 */
class RootShell {

    private val lock = Mutex()
    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    private var stdout: BufferedReader? = null
    private var stderrFile: File? = null

    /** Cheap check without keeping a session: is any `su` binary reachable at all? */
    fun suBinaryPresent(): Boolean =
        System.getenv("PATH").orEmpty().split(':').plus(listOf("/system/bin", "/system/xbin", "/sbin", "/debug_ramdisk"))
            .any { File(it, "su").exists() }

    suspend fun isGranted(): Boolean = run("id -u", timeoutMs = 20_000).let { it.ok && it.out.trim() == "0" }

    suspend fun run(command: String, timeoutMs: Long = 120_000): ShellResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(timeoutMs) { exec(command) }
            if (result == null) {
                // A hung command poisons the session; recreate it next time.
                close()
                ShellResult(124, "", "timeout after ${timeoutMs}ms: $command")
            } else result
        }
    }

    /** Starts a long-running root process (an engine) whose lifetime is independent of the shell. */
    fun spawn(command: String, logFile: File): Process {
        logFile.parentFile?.mkdirs()
        return ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
    }

    private fun ensure() {
        if (process?.isAlive == true) return
        val errFile = File.createTempFile("rootshell", ".err")
        val p = ProcessBuilder("su")
            .redirectError(ProcessBuilder.Redirect.to(errFile))
            .start()
        process = p
        stdin = OutputStreamWriter(p.outputStream)
        stdout = BufferedReader(InputStreamReader(p.inputStream))
        stderrFile = errFile
    }

    private fun exec(command: String): ShellResult {
        return try {
            ensure()
            val marker = "__MVM_" + UUID.randomUUID().toString().replace("-", "")
            val errFile = stderrFile!!
            errFile.writeText("")
            val w = stdin!!
            // Run in a subshell so `exit` inside the command cannot kill the session.
            w.write("( $command\n) </dev/null 2>>${errFile.absolutePath}\necho \"$marker \$?\"\n")
            w.flush()
            val out = StringBuilder()
            while (true) {
                val line = stdout!!.readLine() ?: return ShellResult(1, out.toString(), "su session closed")
                if (line.startsWith(marker)) {
                    val code = line.removePrefix(marker).trim().toIntOrNull() ?: 1
                    return ShellResult(code, out.toString().trimEnd(), errFile.readText().trimEnd())
                }
                out.appendLine(line)
            }
            @Suppress("UNREACHABLE_CODE")
            ShellResult(1, "", "")
        } catch (e: Exception) {
            close()
            ShellResult(1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    fun close() {
        runCatching { stdin?.close() }
        process?.let { p ->
            p.destroy()
            runCatching { p.waitFor(1, TimeUnit.SECONDS) }
        }
        process = null
    }
}

/** Single-quote a value for POSIX shells. */
fun String.shQuote(): String = "'" + replace("'", "'\\''") + "'"
