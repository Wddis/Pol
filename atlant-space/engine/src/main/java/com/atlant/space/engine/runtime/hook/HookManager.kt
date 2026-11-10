package com.atlant.space.engine.runtime.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.atlant.space.engine.AtlantEngine
import com.atlant.space.engine.runtime.GuestLoadedApk
import com.atlant.space.engine.runtime.StubConfig
import com.atlant.space.engine.runtime.VirtualRuntime

/**
 * Installs every hook a guest process needs and holds the cross-cutting logic that several hooks
 * share. Installation order matters: the system-service singletons are wrapped first (so later
 * framework calls already route through us), then the instrumentation and the main-thread handler
 * callback that together perform the stub-to-guest swap.
 */
object HookManager {

    fun install(host: Context) {
        SystemServiceHooks.installActivityManager { configureActivityManager(host, it) }
        runCatching {
            SystemServiceHooks.installPackageManager(
                GuestLoadedApk.currentActivityThread(), host.packageManager,
            ) { configurePackageManager(host, it) }
        }
        runCatching { VInstrumentation.install(host) }
        runCatching { HCallbackHook.install() }
    }

    /**
     * When guest code calls `startActivity` for one of its own components, the system would reject
     * it (that component is not in any installed manifest). We rewrite such an intent into a launch
     * of a stub bound to the guest's process slot, carrying the real target as extras; the
     * instrumentation and handler hooks turn it back into the real component in the target process.
     */
    private fun configureActivityManager(host: Context, h: ProxyInvocationHandler) {
        h.add(object : MethodHook("startActivity") {
            override fun before(call: HookCall): Any? {
                rewriteGuestIntent(host, call.args)
                return PROCEED
            }
        })
        h.add(object : MethodHook("startActivities") {
            override fun before(call: HookCall): Any? {
                call.args.forEach { a ->
                    (a as? Array<*>)?.filterIsInstance<Intent>()?.let { intents ->
                        intents.forEach { maybeRewrite(host, it) }
                    }
                }
                return PROCEED
            }
        })
    }

    private fun rewriteGuestIntent(host: Context, args: Array<Any?>) {
        for (i in args.indices) {
            val intent = args[i] as? Intent ?: continue
            maybeRewrite(host, intent)
        }
    }

    private fun maybeRewrite(host: Context, intent: Intent) {
        val comp = intent.component ?: return
        val pkg = comp.packageName
        val guest = VirtualRuntime.guest(pkg) ?: return
        val engine = AtlantEngine.peek() ?: return
        val slot = VirtualRuntime.slot
        val activity = comp.className
        intent.component = ComponentName(host.packageName, StubConfig.stubActivity(slot, 0))
        intent.putExtra(AtlantEngine.EXTRA_SPACE, VirtualRuntime.spaceId)
        intent.putExtra(AtlantEngine.EXTRA_SLOT, slot)
        intent.putExtra(AtlantEngine.EXTRA_PACKAGE, pkg)
        intent.putExtra(AtlantEngine.EXTRA_ACTIVITY, activity)
        intent.putExtra(AtlantEngine.EXTRA_PROCESS, guest.manifest.processOf(guest.manifest.component(activity) ?: return))
        engine.log.i("Hook", "Rewrote guest intent $pkg/$activity to stub slot $slot", VirtualRuntime.spaceId, pkg)
    }

    /** Answers a guest's queries about *its own* package from the loaded guest record. */
    private fun configurePackageManager(host: Context, h: ProxyInvocationHandler) {
        h.add(object : MethodHook("getApplicationInfo") {
            override fun after(call: HookCall, result: Any?): Any? {
                val pkg = call.args.firstOrNull() as? String ?: return result
                val guest = VirtualRuntime.guest(pkg) ?: return result
                return GuestLoadedApk.buildApplicationInfo(guest, host.applicationInfo)
            }
        })
    }
}
