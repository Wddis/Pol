package com.atlant.space.engine.diag

import android.content.Context
import android.os.Build
import com.atlant.space.engine.AtlantEngine
import com.atlant.space.engine.runtime.StubConfig
import com.atlant.space.engine.util.Reflect

enum class CheckStatus { OK, WARN, FAIL }

data class Check(val name: String, val status: CheckStatus, val detail: String)

data class DiagnosticsReport(val checks: List<Check>, val generatedAt: Long) {
    val worst: CheckStatus get() = when {
        checks.any { it.status == CheckStatus.FAIL } -> CheckStatus.FAIL
        checks.any { it.status == CheckStatus.WARN } -> CheckStatus.WARN
        else -> CheckStatus.OK
    }
}

/**
 * Self-diagnostic of the virtualization environment. It answers the one question a user has when
 * a guest app will not start — "is it me, the app, or the sandbox?" — by checking the conditions
 * the engine depends on and reporting each as OK / WARN / FAIL with a plain explanation, rather
 * than leaving the user to read the journal.
 */
class Diagnostics(private val context: Context, private val engine: AtlantEngine) {

    fun run(): DiagnosticsReport {
        val checks = ArrayList<Check>()

        checks += Check(
            "Android version",
            if (Build.VERSION.SDK_INT in 26..36) CheckStatus.OK else CheckStatus.WARN,
            "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}). Supported range is 26–36.",
        )

        checks += hiddenApiCheck()
        checks += abiCheck()
        checks += storageCheck()
        checks += processSlotCheck()
        checks += gmsCheck()

        return DiagnosticsReport(checks, System.currentTimeMillis())
    }

    private fun hiddenApiCheck(): Check {
        if (Build.VERSION.SDK_INT < 28) return Check("Private API access", CheckStatus.OK, "Not restricted before Android 9.")
        val ok = Reflect.quietly {
            val cls = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass")
            cls.getMethod("addHiddenApiExemptions", Array<String>::class.java).invoke(null, arrayOf("")); true
        } == true
        return Check(
            "Private API access",
            if (ok) CheckStatus.OK else CheckStatus.FAIL,
            if (ok) "The sandbox can reach the framework internals it needs." else "The hidden-API exemption could not be applied; guests may fail to launch.",
        )
    }

    private fun abiCheck(): Check {
        val abi = engine.installer.runtimeAbi
        return Check("Guest ABI", CheckStatus.OK, "Guests run as $abi. Apps shipping only another ABI cannot be installed.")
    }

    private fun storageCheck(): Check {
        val free = context.filesDir.usableSpace / (1024 * 1024)
        val status = when { free < 100 -> CheckStatus.FAIL; free < 500 -> CheckStatus.WARN; else -> CheckStatus.OK }
        return Check("Free storage", status, "$free MB available for spaces and apps.")
    }

    private fun processSlotCheck(): Check {
        val used = engine.processes.running().size
        val total = StubConfig.PROCESS_SLOTS
        return Check(
            "Process slots",
            if (used < total) CheckStatus.OK else CheckStatus.WARN,
            "$used of $total guest process slots in use.",
        )
    }

    private fun gmsCheck(): Check = Check(
        "Google Play Services",
        CheckStatus.OK,
        if (engine.gms.hostHasGms) "Present on the device; guests that need it are routed to it."
        else "Not present on the device; guests fall back to their no-Play-Services path.",
    )
}
