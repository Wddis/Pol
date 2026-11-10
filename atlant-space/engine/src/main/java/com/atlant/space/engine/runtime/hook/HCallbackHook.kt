package com.atlant.space.engine.runtime.hook

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.Message
import com.atlant.space.engine.AtlantEngine
import com.atlant.space.engine.runtime.GuestLoadedApk
import com.atlant.space.engine.runtime.StubConfig
import com.atlant.space.engine.runtime.VClient
import com.atlant.space.engine.runtime.VirtualRuntime
import com.atlant.space.engine.util.Reflect

/**
 * Intercepts the activity-launch message on the main thread handler (`ActivityThread.mH`) so the
 * `ActivityInfo` the platform is about to use for a stub activity is rewritten to the guest's real
 * component just before the activity is created. This is the counterpart to [VInstrumentation]:
 * the instrumentation loads the right *class*, this makes the record around it describe the right
 * *component* (package, process, applicationInfo), which is what binds the new activity to the
 * guest's `LoadedApk` and redirected data.
 *
 * On Android 9+ launches arrive as `EXECUTE_TRANSACTION` carrying a `ClientTransaction`; before
 * that as `LAUNCH_ACTIVITY` carrying an `ActivityClientRecord`. Both shapes are handled; an
 * unrecognised shape is passed through untouched.
 */
class HCallbackHook(private val original: Handler.Callback?) : Handler.Callback {

    override fun handleMessage(msg: Message): Boolean {
        runCatching { rewrite(msg) }
        return original?.handleMessage(msg) ?: false
    }

    private fun rewrite(msg: Message) {
        when {
            Build.VERSION.SDK_INT >= 28 && msg.what == EXECUTE_TRANSACTION -> rewriteTransaction(msg.obj)
            msg.what == LAUNCH_ACTIVITY -> rewriteRecord(msg.obj)
        }
    }

    private fun rewriteTransaction(transaction: Any?) {
        transaction ?: return
        val callbacks = Reflect.quietly { Reflect.call(transaction, "getCallbacks") } as? List<*> ?: return
        for (item in callbacks) {
            item ?: continue
            if (!item.javaClass.name.contains("LaunchActivityItem")) continue
            val info = Reflect.quietly { Reflect.get(item, "mInfo") } as? ActivityInfo ?: continue
            val intent = Reflect.quietly { Reflect.get(item, "mIntent") } as? Intent
            rewriteActivityInfo(info, intent)
        }
    }

    private fun rewriteRecord(record: Any?) {
        record ?: return
        val info = Reflect.quietly { Reflect.get(record, "activityInfo") } as? ActivityInfo ?: return
        val intent = Reflect.quietly { Reflect.get(record, "intent") } as? Intent
        rewriteActivityInfo(info, intent)
    }

    /** Turns a stub [info] into the guest component the launch really targets. */
    private fun rewriteActivityInfo(info: ActivityInfo, intent: Intent?) {
        if (!StubConfig.isStub(info.name)) return
        val realActivity = intent?.getStringExtra(AtlantEngine.EXTRA_ACTIVITY) ?: return
        val pkg = intent.getStringExtra(AtlantEngine.EXTRA_PACKAGE) ?: return
        val host = AtlantEngine.peek()?.context ?: return
        // This runs just before the activity is created, the first point where the launch extras
        // are visible in the guest process; make sure the guest is fully loaded here.
        VClient.ensure(host, intent)
        val guest = VirtualRuntime.guest(pkg) ?: return

        info.name = realActivity
        info.packageName = pkg
        info.processName = VirtualRuntime.processName
        info.applicationInfo = GuestLoadedApk.buildApplicationInfo(guest, host.applicationInfo)
        val targetComp = guest.manifest.component(realActivity)
        if (targetComp != null) {
            info.exported = targetComp.isExported
        }
    }

    companion object {
        private const val LAUNCH_ACTIVITY = 100
        private const val EXECUTE_TRANSACTION = 159

        /** Wraps `ActivityThread.mH.mCallback`. Idempotent. */
        fun install() {
            val at = GuestLoadedApk.currentActivityThread()
            val h = Reflect.get(at, "mH") as Handler
            val field = Reflect.field(Handler::class.java, "mCallback")
            val current = field.get(h) as? Handler.Callback
            if (current is HCallbackHook) return
            field.set(h, HCallbackHook(current))
        }
    }
}
