package com.recovery.farmerv8;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * FarmerShoot v8 — LSPatch / Xposed модуль (финал).
 *
 * Надёжная инициализация внутри процесса игры:
 *   - фоновый поток ждёт Application-контекст (ActivityThread.currentApplication);
 *   - находит apk самого модуля (в кэше игры) и грузит нативный движок
 *     libfarmerv8.so — он рисует ImGui-оверлей ПРЯМО на GL-поверхности игры
 *     (видно поверх Unity всегда) + читает/патчит ammo/jump через IL2CPP;
 *   - показывает тост-подтверждение и (best-effort) Java-панель в активити.
 *
 * ЧЕСТНО: Xposed хукает Java, а логика Unity — нативный IL2CPP. Поэтому
 * значения читает/пишет нативная часть; видимое меню — это ImGui-оверлей.
 */
public class FarmerV8Module implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    static final String TAG = "FarmerV8";
    static String sModulePath = null;
    static volatile boolean sNativeOk = false;
    static volatile boolean sBootStarted = false;
    static volatile boolean sPanelShown = false;

    static native String nativeCheck();
    static native boolean nativePatchJump(float mul);
    static native boolean nativeRestore();

    @Override
    public void initZygote(StartupParam sp) {
        sModulePath = sp.modulePath;
        XposedBridge.log(TAG + ": initZygote modulePath=" + sModulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + ": loaded in " + lpparam.packageName);
        if (!sBootStarted) { sBootStarted = true; new Thread(new Bootstrap()).start(); }
        // доп. путь: панель в активити (best-effort, может быть под GL-поверхностью)
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity",
                    lpparam.classLoader, "onResume", new OnResumeHook());
        } catch (Throwable t) { XposedBridge.log(TAG + ": hook onResume failed: " + t); }
    }

    // --- Фоновая инициализация: ждём контекст, грузим нативный движок ---------
    static final class Bootstrap implements Runnable {
        @Override public void run() {
            Context ctx = null;
            for (int i = 0; i < 120 && ctx == null; i++) {   // до ~60с
                ctx = currentContext();
                if (ctx == null) sleep(500);
            }
            if (ctx == null) { XposedBridge.log(TAG + ": no context"); return; }
            loadNative(ctx);
            final Context fctx = ctx;
            new Handler(Looper.getMainLooper()).post(new ShowToast(fctx));
        }
    }

    static Context currentContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return (Context) app;
        } catch (Throwable t) { return null; }
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (Throwable ignore) {} }

    // Находим apk модуля и грузим libfarmerv8.so.
    static synchronized void loadNative(Context ctx) {
        if (sNativeOk) return;
        String apk = findModuleApk(ctx);
        if (apk == null) { XposedBridge.log(TAG + ": module apk not found (native skipped)"); return; }
        ZipFile zf = null;
        try {
            zf = new ZipFile(apk);
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libfarmerv8.so");
            if (e == null) e = zf.getEntry("assets/libfarmerv8.so");
            if (e == null) { XposedBridge.log(TAG + ": native lib not bundled in " + apk); return; }
            File out = new File(ctx.getCacheDir(), "libfarmerv8_" + e.getCrc() + ".so");
            if (!out.exists() || out.length() != e.getSize()) {
                InputStream in = zf.getInputStream(e);
                OutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.close(); in.close();
            }
            System.load(out.getAbsolutePath());
            sNativeOk = true;
            XposedBridge.log(TAG + ": native engine loaded: " + out);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": native load failed: " + t);
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignore) {}
        }
    }

    // Ищем apk модуля: сперва modulePath, затем кэш игры .../cache/lspatch/**.apk.
    static String findModuleApk(Context ctx) {
        if (sModulePath != null && new File(sModulePath).exists() && zipHasLib(sModulePath)) return sModulePath;
        try {
            File root = new File(ctx.getCacheDir(), "lspatch");
            String hit = scanForApk(root);
            if (hit != null) return hit;
        } catch (Throwable ignore) {}
        return sModulePath;  // последний шанс
    }
    static String scanForApk(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isDirectory()) { String r = scanForApk(f); if (r != null) return r; }
            else if (f.getName().endsWith(".apk") && zipHasLib(f.getAbsolutePath())) return f.getAbsolutePath();
        }
        return null;
    }
    static boolean zipHasLib(String apk) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(apk);
            return zf.getEntry("lib/arm64-v8a/libfarmerv8.so") != null
                || zf.getEntry("assets/libfarmerv8.so") != null;
        } catch (Throwable t) { return false; }
        finally { if (zf != null) try { zf.close(); } catch (Throwable ignore) {} }
    }

    static final class ShowToast implements Runnable {
        final Context ctx; ShowToast(Context c) { ctx = c; }
        @Override public void run() {
            try {
                Toast.makeText(ctx, "FarmerShoot v8 ✓ " + (sNativeOk ? "движок загружен (ImGui-оверлей)"
                        : "модуль загружен (native нет)"), Toast.LENGTH_LONG).show();
            } catch (Throwable ignore) {}
        }
    }

    // --- Java-панель в активити (доп. путь) ----------------------------------
    static final class OnResumeHook extends XC_MethodHook {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            if (sPanelShown) return;
            Object o = param.thisObject;
            if (!(o instanceof Activity)) return;
            ((Activity) o).runOnUiThread(new ShowPanel((Activity) o));
        }
    }
    static final class ShowPanel implements Runnable {
        final Activity act; ShowPanel(Activity a) { act = a; }
        @Override public void run() {
            if (sPanelShown) return; sPanelShown = true;
            try {
                LinearLayout p = new LinearLayout(act);
                p.setOrientation(LinearLayout.VERTICAL);
                p.setBackgroundColor(Color.argb(200, 18, 22, 18));
                p.setPadding(20, 20, 20, 20);
                TextView t = new TextView(act);
                t.setText("v8 " + (sNativeOk ? "native ✓" : "java"));
                t.setTextColor(Color.rgb(170, 255, 140));
                p.addView(t);
                Button b = new Button(act);
                b.setText("v8 status");
                b.setOnClickListener(new OnStatus(act));
                p.addView(b);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.TOP | Gravity.END; lp.topMargin = 40; lp.rightMargin = 24;
                act.addContentView(p, lp);
            } catch (Throwable t) { XposedBridge.log(TAG + ": panel failed: " + t); }
        }
    }
    static final class OnStatus implements View.OnClickListener {
        final Activity act; OnStatus(Activity a) { act = a; }
        @Override public void onClick(View v) {
            String s;
            try { s = sNativeOk ? nativeCheck() : "native не загружен"; }
            catch (Throwable t) { s = "native err: " + t; }
            Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
        }
    }
}
