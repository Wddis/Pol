package dev.matryoshka.vm.core.nest

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

enum class ProbeLevel { OK, WARN, FAIL, INFO }

data class ProbeItem(val key: String, val title: String, val level: ProbeLevel, val detail: String)

data class ProbeReport(val items: List<ProbeItem>, val raw: String, val createdAt: Long = System.currentTimeMillis()) {
    val failures get() = items.count { it.level == ProbeLevel.FAIL }
    val warnings get() = items.count { it.level == ProbeLevel.WARN }
}

/**
 * Stage-0 check of everything the Nest engine needs on this phone: the native part (seccomp,
 * ptrace path rewriting, exec from app data, shared memory + fd passing, interception cost) runs
 * inside the real supervisor binary; the rest (GPU, RAM, background limits) is checked here.
 */
class NestProbe(private val context: Context) {

    val supervisor: File get() = File(context.applicationInfo.nativeLibraryDir, "libnest_run.so")

    suspend fun run(): ProbeReport = withContext(Dispatchers.IO) {
        val items = mutableListOf<ProbeItem>()
        val raw = StringBuilder()

        // --- host facts
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val miui = getprop("ro.miui.ui.version.name")
        items += info("device", "Device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        items += info("android", "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            if (miui.isNotEmpty()) " · MIUI $miui" else "")
        items += info("abi", "CPU ABIs", Build.SUPPORTED_ABIS.joinToString())
        items += ProbeItem(
            "abi32", "32-bit apps", if (Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()) ProbeLevel.OK else ProbeLevel.WARN,
            if (Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()) "supported" else "64-bit only phone: old 32-bit games will not run",
        )
        items += info("ram", "RAM", "%.1f GB total, %.1f GB available".format(mem.totalMem / 1e9, mem.availMem / 1e9))
        val free = StatFs(context.filesDir.path).availableBytes / 1e9
        items += ProbeItem("storage", "Free storage", if (free >= 4) ProbeLevel.OK else ProbeLevel.WARN, "%.1f GB".format(free))
        val target = context.applicationInfo.targetSdkVersion
        items += ProbeItem("target_sdk", "App target SDK", if (target <= 28) ProbeLevel.OK else ProbeLevel.FAIL, target.toString())

        // --- background limits
        val pm = context.getSystemService(PowerManager::class.java)
        val unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
        items += ProbeItem(
            "battery", "Background work", if (unrestricted) ProbeLevel.OK else ProbeLevel.WARN,
            if (unrestricted) "battery optimisation disabled" else "battery optimisation is on — allow background work",
        )
        if (Build.VERSION.SDK_INT >= 31) {
            items += ProbeItem("phantom", "Child process limit", ProbeLevel.WARN,
                "Android 12+: disable the phantom process limit in Developer options (or via ADB) for big guests")
        } else {
            items += ProbeItem("phantom", "Child process limit", ProbeLevel.OK, "not present on this Android version")
        }

        // --- GPU (the guest renders through the host GPU)
        items += probeGl()
        val vk = context.packageManager.systemAvailableFeatures.orEmpty()
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version ?: 0
        items += info("vulkan", "Vulkan", if (vk == 0) "not available" else "${vk shr 22}.${(vk shr 12) and 0x3ff}")

        // --- native engine checks inside the real supervisor binary
        val work = File(context.filesDir, "nest/probe").apply { mkdirs() }
        val native = runSupervisor(listOf("--probe", work.absolutePath))
        raw.append(native)
        for (line in native.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            when {
                key.startsWith("info.") -> items += info(key, titleFor(key), value)
                key.startsWith("check.") -> {
                    val ok = value == "ok" || value.startsWith("ok:")
                    items += ProbeItem(key, titleFor(key), if (ok) ProbeLevel.OK else ProbeLevel.FAIL,
                        value.substringAfter(':', if (ok) "ok" else value))
                }
            }
        }
        if (native.isBlank()) {
            items += ProbeItem("check.supervisor", "Engine supervisor", ProbeLevel.FAIL,
                "could not run ${supervisor.name} (exists=${supervisor.exists()})")
        }
        ProbeReport(items, raw.toString())
    }

    private suspend fun runSupervisor(args: List<String>): String = withTimeoutOrNull(120_000) {
        runCatching {
            val p = ProcessBuilder(listOf(supervisor.absolutePath) + args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(5, TimeUnit.SECONDS)
            out
        }.getOrElse { "check.supervisor=fail:${it.message}\n" }
    } ?: "check.supervisor=fail:timeout\n"

    private fun probeGl(): ProbeItem = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(display, ver, 0, ver, 1)
        val attribs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40 /* ES3 */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0)
        val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surf, surf, ctx)
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
        val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surf)
        EGL14.eglDestroyContext(display, ctx)
        EGL14.eglTerminate(display)
        if (renderer.isEmpty()) error("no renderer")
        ProbeItem("gles", "GPU (OpenGL ES 3)", ProbeLevel.OK, "$renderer · $version")
    }.getOrElse { ProbeItem("gles", "GPU (OpenGL ES 3)", ProbeLevel.FAIL, it.message ?: "EGL failed") }

    private fun info(key: String, title: String, detail: String) = ProbeItem(key, title, ProbeLevel.INFO, detail)

    private fun titleFor(key: String) = when (key) {
        "check.seccomp_filter" -> "Syscall filter (seccomp)"
        "check.exec_from_app_data" -> "Run guest programs"
        "check.memfd_fd_passing" -> "Shared memory + fd passing"
        "check.tracer_path_rewrite" -> "Virtual filesystem (ptrace)"
        "check.tracer_benchmark" -> "Interception cost"
        "info.kernel" -> "Kernel"
        "info.machine" -> "Machine"
        "info.uid" -> "App UID"
        "info.binder_device" -> "Host binder device"
        "info.ashmem_device" -> "Host ashmem device"
        "info.yama_ptrace_scope" -> "ptrace scope (Yama)"
        else -> key
    }

    companion object {
        fun getprop(name: String): String = runCatching {
            val p = ProcessBuilder("getprop", name).start()
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor(2, TimeUnit.SECONDS) }
        }.getOrDefault("")
    }
}
