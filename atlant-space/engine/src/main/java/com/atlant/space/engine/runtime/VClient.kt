package com.atlant.space.engine.runtime

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import com.atlant.space.engine.AtlantEngine
import com.atlant.space.engine.device.VirtualDeviceEnvironment
import com.atlant.space.engine.runtime.env.RedirectContext
import com.atlant.space.engine.runtime.hook.HookManager
import com.atlant.space.engine.util.Reflect
import java.io.File

/**
 * Bootstrap that runs inside a guest host process (`:vprocN`) the first time one of its stub
 * components is created. It brings the process from "an empty host process the system launched"
 * to "a process that is running the guest app": it fixes the process identity, installs the hooks,
 * builds the guest's `LoadedApk` and creates the guest `Application` against a redirected context.
 *
 * Everything here is reflection over private framework internals, so every step is defensive: a
 * step that fails on an unusual device shape is logged and skipped, leaving the process in the
 * best state reached so far rather than crashing the guest.
 */
object VClient {

    @Volatile private var hooksInstalled = false
    @Volatile var guestApplication: Application? = null; private set

    /** Idempotent. Called from every stub component's entry point with the launch extras. */
    @Synchronized
    fun ensure(host: Context, intent: Intent?) {
        val engine = AtlantEngine.get(host)
        val spaceId = intent?.getIntExtra(AtlantEngine.EXTRA_SPACE, -1) ?: -1
        val slot = intent?.getIntExtra(AtlantEngine.EXTRA_SLOT, -1) ?: -1
        val pkg = intent?.getStringExtra(AtlantEngine.EXTRA_PACKAGE)
        val processName = intent?.getStringExtra(AtlantEngine.EXTRA_PROCESS) ?: pkg ?: return
        if (spaceId < 0 || slot < 0 || pkg == null) {
            engine.log.w("VClient", "Stub launched without a target; nothing to bootstrap")
            return
        }
        val space = engine.spaces.get(spaceId) ?: return
        VirtualRuntime.init(spaceId, slot, processName, space.env)
        enableHiddenApi()
        installHooks(host)
        loadGuest(host, spaceId, pkg, processName)
    }

    /** Whitelist the whole hidden API surface on Android 9+ so the reflection below is allowed. */
    private fun enableHiddenApi() {
        if (Build.VERSION.SDK_INT < 28) return
        Reflect.quietly {
            val cls = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass")
            cls.getMethod("addHiddenApiExemptions", Array<String>::class.java).invoke(null, arrayOf(""))
        }
    }

    private fun installHooks(host: Context) {
        if (hooksInstalled) return
        runCatching { HookManager.install(host) }
            .onFailure { AtlantEngine.get(host).log.e("VClient", "Hook install failed", it, VirtualRuntime.spaceId) }
        hooksInstalled = true
    }

    private fun loadGuest(host: Context, spaceId: Int, pkg: String, processName: String) {
        if (VirtualRuntime.guest(pkg) != null) return
        val engine = AtlantEngine.get(host)
        val space = engine.spaces.get(spaceId) ?: return
        val app = engine.apps.app(space.dirName, pkg) ?: return
        val manifest = engine.apps.manifest(space.dirName, pkg) ?: return
        val record = app.record

        val dataDir = engine.storage.dataDir(space.dirName, pkg)
        val extDataDir = engine.storage.extDataDir(space.dirName, pkg)
        val loaded = VirtualRuntime.LoadedGuest(
            record = record,
            manifest = manifest,
            apkPaths = app.apkPaths.map { it.path },
            nativeLibDir = app.nativeLibDir?.path ?: File(dataDir, "lib").path,
            dataDir = dataDir.path,
            extDataDir = extDataDir.path,
        )
        VirtualRuntime.registerPackage(loaded)
        VirtualRuntime.hostingPackage = pkg

        runCatching { materializeApplication(host, loaded) }
            .onFailure { engine.log.e("VClient", "Could not create guest Application for $pkg", it, spaceId, pkg) }
    }

    /**
     * Builds the guest's `LoadedApk`, installs it into `ActivityThread.mPackages`, and creates the
     * guest `Application` object wrapped in a [RedirectContext]. From here the guest's own code can
     * be instantiated by the instrumentation hook when the real activity is created.
     */
    private fun materializeApplication(host: Context, g: VirtualRuntime.LoadedGuest) {
        val at = GuestLoadedApk.currentActivityThread()
        val hostInfo = host.applicationInfo
        val ai = GuestLoadedApk.buildApplicationInfo(g, hostInfo)
        val classLoader = GuestLoadedApk.createClassLoader(g)
        val loadedApk = GuestLoadedApk.getOrCreate(at, ai, classLoader)

        // LoadedApk.makeApplication(forceDefaultAppClass=false, instrumentation=null)
        val makeApplication = Reflect.quietly {
            Reflect.method(loadedApk.javaClass, "makeApplication", Boolean::class.javaPrimitiveType!!, Class.forName("android.app.Instrumentation"))
        }
        val appObj = makeApplication?.let {
            Reflect.quietly { it.invoke(loadedApk, false, null) } as? Application
        }
        if (appObj != null) {
            guestApplication = appObj
            AtlantEngine.get(host).log.i("VClient", "Guest Application ready: ${g.record.packageName} (${appObj.javaClass.name})", VirtualRuntime.spaceId, g.record.packageName)
        }
    }

    val environment: VirtualDeviceEnvironment get() = VirtualRuntime.environment
}
