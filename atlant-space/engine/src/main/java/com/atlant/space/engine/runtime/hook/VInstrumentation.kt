package com.atlant.space.engine.runtime.hook

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import com.atlant.space.engine.AtlantEngine
import com.atlant.space.engine.runtime.StubConfig
import com.atlant.space.engine.runtime.VClient
import com.atlant.space.engine.runtime.VirtualRuntime
import com.atlant.space.engine.runtime.env.RedirectContext
import com.atlant.space.engine.util.Reflect

/**
 * Replacement `Instrumentation` installed into `ActivityThread.mInstrumentation`.
 *
 * Two things must happen for a guest activity to come up: its class has to be loaded from the
 * guest's own ClassLoader (not the host's), and every `Context` it is given has to be the
 * redirected one so its storage lands in the space. Both are centralised here, with every other
 * call forwarded to the real instrumentation so the platform behaves normally otherwise.
 */
class VInstrumentation(val base: Instrumentation, val host: Context) : Instrumentation() {

    override fun newActivity(cl: ClassLoader?, className: String, intent: Intent?): Activity {
        if (StubConfig.isStub(className)) {
            val real = intent?.getStringExtra(AtlantEngine.EXTRA_ACTIVITY)
            val pkg = intent?.getStringExtra(AtlantEngine.EXTRA_PACKAGE) ?: VirtualRuntime.hostingPackage
            val guest = pkg?.let { VirtualRuntime.guest(it) }
            if (real != null && guest != null) {
                val loader = guestClassLoader(guest) ?: cl
                return base.newActivity(loader, real, intent)
            }
        }
        return base.newActivity(cl, className, intent)
    }

    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        val wrapped = context?.let { runCatching { RedirectContext.wrap(it) }.getOrDefault(it) }
        return base.newApplication(cl, className, wrapped ?: context)
    }

    override fun callApplicationOnCreate(app: Application) {
        base.callApplicationOnCreate(app)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        base.callActivityOnCreate(activity, icicle)
    }

    private fun guestClassLoader(g: VirtualRuntime.LoadedGuest): ClassLoader? = Reflect.quietly {
        val at = com.atlant.space.engine.runtime.GuestLoadedApk.currentActivityThread()
        val packages = Reflect.get(at, "mPackages") as Map<*, *>
        val ref = packages[g.record.packageName] as? java.lang.ref.WeakReference<*>
        (ref?.get())?.let { Reflect.get(it, "mClassLoader") as? ClassLoader }
    }

    companion object {
        /** Installs a [VInstrumentation] over the current one. Idempotent. */
        fun install(host: Context) {
            val at = com.atlant.space.engine.runtime.GuestLoadedApk.currentActivityThread()
            val current = Reflect.get(at, "mInstrumentation") as Instrumentation
            if (current is VInstrumentation) return
            Reflect.set(at, "mInstrumentation", VInstrumentation(current, host))
        }
    }

    // --- everything below is a straight delegation to the real instrumentation ---
    override fun onCreate(arguments: Bundle?) = base.onCreate(arguments)
    override fun start() = base.start()
    override fun onStart() = base.onStart()
    override fun onException(obj: Any?, e: Throwable?): Boolean = base.onException(obj, e)
    override fun sendStatus(resultCode: Int, results: Bundle?) = base.sendStatus(resultCode, results)
    override fun finish(resultCode: Int, results: Bundle?) = base.finish(resultCode, results)
    override fun setAutomaticPerformanceSnapshots() = base.setAutomaticPerformanceSnapshots()
    override fun startPerformanceSnapshot() = base.startPerformanceSnapshot()
    override fun endPerformanceSnapshot() = base.endPerformanceSnapshot()
    override fun onDestroy() = base.onDestroy()
    override fun getContext(): Context = base.context
    override fun getComponentName(): ComponentName = base.componentName
    override fun getTargetContext(): Context = base.targetContext
    override fun getProcessName(): String = base.processName
    override fun isProfiling(): Boolean = base.isProfiling
    override fun startProfiling() = base.startProfiling()
    override fun stopProfiling() = base.stopProfiling()
    override fun setInTouchMode(inTouch: Boolean) = base.setInTouchMode(inTouch)
    override fun waitForIdle(recipient: Runnable?) = base.waitForIdle(recipient)
    override fun waitForIdleSync() = base.waitForIdleSync()
    override fun runOnMainSync(runner: Runnable) = base.runOnMainSync(runner)
    override fun callActivityOnDestroy(activity: Activity) = base.callActivityOnDestroy(activity)
    override fun callActivityOnResume(activity: Activity) = base.callActivityOnResume(activity)
    override fun callActivityOnPause(activity: Activity) = base.callActivityOnPause(activity)
}
