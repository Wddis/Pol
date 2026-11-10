package com.atlant.space.engine.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.atlant.space.engine.util.Reflect
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Builds the `LoadedApk` the platform uses to materialise a guest app's classes, resources and
 * context, and installs it into `ActivityThread.mPackages` under the guest package name.
 *
 * The platform's own `ActivityThread.getPackageInfo(...)` would create a `LoadedApk` for an
 * *installed* package; the guest is not installed, so we construct an `ApplicationInfo` that
 * points at the guest's staged APKs and private data directory and let the platform build the
 * rest. This is the single place that knows the shape of these private classes, so every
 * version-specific adjustment lives here.
 */
object GuestLoadedApk {

    fun buildApplicationInfo(g: VirtualRuntime.LoadedGuest, hostInfo: ApplicationInfo): ApplicationInfo {
        val ai = ApplicationInfo()
        ai.packageName = g.record.packageName
        ai.sourceDir = g.apkPaths.first()
        ai.publicSourceDir = g.apkPaths.first()
        if (g.apkPaths.size > 1) {
            val splits = g.apkPaths.drop(1).toTypedArray()
            ai.splitSourceDirs = splits
            ai.splitPublicSourceDirs = splits
        }
        ai.dataDir = g.dataDir
        ai.nativeLibraryDir = g.nativeLibDir
        ai.processName = VirtualRuntime.processName
        ai.uid = hostInfo.uid
        ai.targetSdkVersion = g.record.targetSdk
        ai.minSdkVersion = g.record.minSdk
        ai.className = g.manifest.applicationClass
        ai.flags = ApplicationInfo.FLAG_HAS_CODE or ApplicationInfo.FLAG_ALLOW_BACKUP
        if (Build.VERSION.SDK_INT >= 24) {
            // deviceProtectedDataDir / credentialProtectedDataDir are hidden fields, absent from
            // the public SDK. Set them reflectively so the guest's protected-storage paths also
            // point inside the space instead of leaking to the host's real data directory.
            Reflect.quietly { Reflect.set(ai, "deviceProtectedDataDir", g.dataDir) }
            Reflect.quietly { Reflect.set(ai, "credentialProtectedDataDir", g.dataDir) }
        }
        return ai
    }

    /**
     * ClassLoader for the guest. The parent is the boot classloader so the guest sees only the
     * Android framework and its own classes, never the engine's classes or the host app's.
     * Native libraries are loaded from the guest's extracted lib directory.
     */
    fun createClassLoader(g: VirtualRuntime.LoadedGuest): ClassLoader {
        val dexPath = g.apkPaths.joinToString(File.pathSeparator)
        val optimized = File(File(g.dataDir), "code_cache").apply { mkdirs() }
        return DexClassLoader(dexPath, optimized.path, g.nativeLibDir, ClassLoader.getSystemClassLoader().parent)
    }

    /**
     * Returns a `LoadedApk` for the guest, creating and caching it in `ActivityThread.mPackages`
     * on first use so the platform reuses the same instance (and the same ClassLoader) later.
     */
    fun getOrCreate(activityThread: Any, ai: ApplicationInfo, classLoader: ClassLoader): Any {
        val packages = Reflect.get(activityThread, "mPackages") as MutableMap<String, Any>
        synchronized(packages) {
            (packages[ai.packageName] as? java.lang.ref.WeakReference<*>)?.get()?.let { return it }
            val compat = Reflect.get(activityThread, "mCompatConfiguration")
            // ActivityThread.getPackageInfoNoCheck(ApplicationInfo, CompatibilityInfo)
            val loadedApk = Reflect.method(
                activityThread.javaClass, "getPackageInfoNoCheck",
                ApplicationInfo::class.java, Class.forName("android.content.res.CompatibilityInfo"),
            ).invoke(activityThread, ai, compat)
            // Force our ClassLoader in before any class is resolved from it.
            Reflect.quietly { Reflect.set(loadedApk, "mClassLoader", classLoader) }
            Reflect.quietly { Reflect.set(loadedApk, "mApplicationInfo", ai) }
            return loadedApk
        }
    }

    fun currentActivityThread(): Any =
        Reflect.callStatic(Class.forName("android.app.ActivityThread"), "currentActivityThread")!!
}
