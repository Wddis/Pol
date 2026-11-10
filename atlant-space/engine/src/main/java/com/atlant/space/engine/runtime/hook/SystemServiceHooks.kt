package com.atlant.space.engine.runtime.hook

import android.os.Build
import android.os.IInterface
import com.atlant.space.engine.runtime.VirtualRuntime
import com.atlant.space.engine.util.Reflect

/**
 * Installs the proxy around the process-wide singletons that hold the system service Binder
 * proxies, so later calls go through our hooks. Each installer finds the private singleton
 * field, replaces its cached instance with a [ProxyInvocationHandler] wrapping the real one,
 * and leaves the rest of the framework untouched.
 *
 * The installers are deliberately independent and each is wrapped in a try/catch by the caller:
 * on a device shape where one singleton has moved, the others still install and the guest keeps
 * the hooks that did succeed.
 */
object SystemServiceHooks {

    /** Replaces the IActivityManager / IActivityTaskManager singletons. */
    fun installActivityManager(configure: (ProxyInvocationHandler) -> Unit) {
        // Android 8+: ActivityManager.IActivityManagerSingleton (a Singleton<IActivityManager>).
        patchSingleton("android.app.ActivityManager", "IActivityManagerSingleton", "android.app.IActivityManager", configure)
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+: ActivityTaskManager.IActivityTaskManagerSingleton.
            patchSingleton("android.app.ActivityTaskManager", "IActivityTaskManagerSingleton", "android.app.IActivityTaskManager", configure)
        }
    }

    /** Replaces the IPackageManager cached in ActivityThread and in ApplicationPackageManager. */
    fun installPackageManager(activityThread: Any, packageManager: android.content.pm.PackageManager, configure: (ProxyInvocationHandler) -> Unit) {
        val at = Class.forName("android.app.ActivityThread")
        val real = Reflect.getStatic(at, "sPackageManager") ?: Reflect.call(activityThread, "getPackageManager")
        val iface = Class.forName("android.content.pm.IPackageManager")
        val proxy = ProxyInvocationHandler.wrap(iface, real!!, configure)
        Reflect.quietly { Reflect.setStatic(at, "sPackageManager", proxy) }
        Reflect.quietly { Reflect.set(packageManager, "mPM", proxy) }
    }

    /** Replaces the IContentProvider returned for the chosen authorities (settings redirection). */
    private fun patchSingleton(className: String, fieldName: String, ifaceName: String, configure: (ProxyInvocationHandler) -> Unit) {
        val cls = Class.forName(className)
        val singleton = Reflect.getStatic(cls, fieldName) ?: return
        val singletonClass = Class.forName("android.util.Singleton")
        // Force the Singleton to resolve, then read its cached instance.
        Reflect.quietly { Reflect.method(singletonClass, "get").invoke(singleton) }
        val instanceField = Reflect.field(singletonClass, "mInstance")
        val real = instanceField.get(singleton) ?: return
        if (java.lang.reflect.Proxy.isProxyClass(real.javaClass)) return
        val iface = Class.forName(ifaceName)
        val proxy = ProxyInvocationHandler.wrap(iface, real, configure)
        instanceField.set(singleton, proxy)
    }

    fun asBinderProxyName(obj: Any?): String? = (obj as? IInterface)?.javaClass?.name
    fun runtimeReady() = VirtualRuntime.initialized
}
