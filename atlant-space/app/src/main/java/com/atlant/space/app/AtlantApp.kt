package com.atlant.space.app

import android.app.Application
import android.content.Context
import com.atlant.space.engine.AtlantEngine
import com.atlant.space.engine.runtime.hook.HookManager

/**
 * The host application, created in every process the OS spawns for this app — the main UI process
 * and each guest host process (`:vprocN`). The two roles diverge immediately:
 *
 *  - In the **main** process it brings the engine up: loads the spaces, recovers interrupted
 *    installs and prepares the managers the UI drives.
 *  - In a **guest** process it must not touch any of that. It only installs the hooks as early as
 *    possible, so that by the time the system delivers the first activity launch the process is
 *    ready to be turned into the guest. The guest itself is loaded lazily from the launch extras.
 */
class AtlantApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val engine = AtlantEngine.get(this)
        if (isMainProcess()) {
            engine.bootstrapMainProcess()
        } else {
            // A guest host process: install the hooks now; the guest is materialised on first launch.
            runCatching { HookManager.install(this) }
                .onFailure { engine.log.e("App", "Early hook install failed in ${processName()}", it) }
        }
    }

    private fun isMainProcess(): Boolean = processName() == packageName

    private fun processName(): String {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else legacyProcessName()
        return name ?: packageName
    }

    private fun legacyProcessName(): String? {
        val pid = android.os.Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }
}
