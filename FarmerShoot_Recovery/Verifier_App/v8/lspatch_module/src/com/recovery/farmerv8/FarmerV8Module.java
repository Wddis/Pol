package com.recovery.farmerv8;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * FarmerShoot v8 — LSPatch / Xposed модуль (FINAL v2).
 *
 * Окно показывается через WindowManager (отдельное окно ПОВЕРХ игры) — видно
 * независимо от графического API (и на Vulkan, и на OpenGL). Активная Activity
 * ищется рефлексией (без хука onResume, который под LSPatch мог не срабатывать).
 * Нативный движок libfarmerv8.so читает IL2CPP и отдаёт имена/значения в окно;
 * на OpenGL он ещё и рисует свой ImGui-оверлей.
 */
public class FarmerV8Module implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    static final String TAG = "FarmerV8";
    static String sModulePath = null;
    static volatile boolean sNativeOk = false;
    static volatile boolean sStarted = false;
    static volatile boolean sShown = false;
    static volatile String sDumpDir = null;

    static native String nativeInfo();
    static native boolean nativeDumpTo(String path);
    static native boolean nativePatchJump(float mul);
    static native boolean nativeRestore();

    @Override public void initZygote(StartupParam sp) { sModulePath = sp.modulePath; }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + ": loaded in " + lpparam.packageName);
        if (!sStarted) { sStarted = true; Thread th = new Thread(new Bootstrap()); th.setDaemon(true); th.start(); }
    }

    // ---- фоновая инициализация: контекст -> native -> окно -------------------
    static final class Bootstrap implements Runnable {
        @Override public void run() {
            Context ctx = null;
            for (int i = 0; i < 120 && ctx == null; i++) { ctx = appContext(); if (ctx == null) sleep(500); }
            if (ctx != null) {
                try { File d = ctx.getExternalFilesDir(null); if (d != null) sDumpDir = d.getAbsolutePath(); } catch (Throwable ignore) {}
                loadNative(ctx);
            }
            // ищем активную Activity и показываем окно (повторяем, пока не покажем)
            for (int i = 0; i < 600; i++) {               // до ~5 мин попыток
                if (sShown) { sleep(3000); continue; }
                Activity act = topActivity();
                if (act != null) act.runOnUiThread(new ShowOverlay(act));
                sleep(500);
            }
        }
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (Throwable ignore) {} }

    static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return (Context) app;
        } catch (Throwable t) { return null; }
    }

    // текущая НЕ-paused Activity через рефлексию ActivityThread.mActivities
    static Activity topActivity() {
        try {
            Class<?> atc = Class.forName("android.app.ActivityThread");
            Object at = atc.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field fa = atc.getDeclaredField("mActivities"); fa.setAccessible(true);
            Object obj = fa.get(at);
            if (!(obj instanceof Map)) return null;
            Map<?, ?> map = (Map<?, ?>) obj;
            for (Object rec : map.values()) {
                Class<?> rc = rec.getClass();
                java.lang.reflect.Field pf = rc.getDeclaredField("paused"); pf.setAccessible(true);
                if (!pf.getBoolean(rec)) {
                    java.lang.reflect.Field af = rc.getDeclaredField("activity"); af.setAccessible(true);
                    Object a = af.get(rec);
                    if (a instanceof Activity) return (Activity) a;
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    // ---- загрузка нативного движка ------------------------------------------
    static synchronized void loadNative(Context ctx) {
        if (sNativeOk) return;
        String apk = findModuleApk(ctx);
        if (apk == null) { XposedBridge.log(TAG + ": module apk not found"); return; }
        ZipFile zf = null;
        try {
            zf = new ZipFile(apk);
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libfarmerv8.so");
            if (e == null) e = zf.getEntry("assets/libfarmerv8.so");
            if (e == null) { XposedBridge.log(TAG + ": .so not in apk"); return; }
            File out = new File(ctx.getCacheDir(), "libfarmerv8_" + e.getCrc() + ".so");
            if (!out.exists() || out.length() != e.getSize()) {
                InputStream in = zf.getInputStream(e);
                OutputStream os = new FileOutputStream(out);
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) os.write(b, 0, n);
                os.close(); in.close();
            }
            System.load(out.getAbsolutePath());
            sNativeOk = true;
            XposedBridge.log(TAG + ": native loaded " + out);
        } catch (Throwable t) { XposedBridge.log(TAG + ": native load failed: " + t); }
        finally { if (zf != null) try { zf.close(); } catch (Throwable ignore) {} }
    }

    static String findModuleApk(Context ctx) {
        if (sModulePath != null && new File(sModulePath).exists() && zipHasLib(sModulePath)) return sModulePath;
        try { String h = scan(new File(ctx.getCacheDir(), "lspatch")); if (h != null) return h; } catch (Throwable ignore) {}
        return sModulePath;
    }
    static String scan(File d) {
        if (d == null || !d.isDirectory()) return null;
        File[] fs = d.listFiles(); if (fs == null) return null;
        for (File f : fs) {
            if (f.isDirectory()) { String r = scan(f); if (r != null) return r; }
            else if (f.getName().endsWith(".apk") && zipHasLib(f.getAbsolutePath())) return f.getAbsolutePath();
        }
        return null;
    }
    static boolean zipHasLib(String apk) {
        ZipFile zf = null;
        try { zf = new ZipFile(apk);
            return zf.getEntry("lib/arm64-v8a/libfarmerv8.so") != null || zf.getEntry("assets/libfarmerv8.so") != null;
        } catch (Throwable t) { return false; }
        finally { if (zf != null) try { zf.close(); } catch (Throwable ignore) {} }
    }

    // ---- окно WindowManager (видно и на Vulkan) ------------------------------
    static final class ShowOverlay implements Runnable {
        final Activity act; ShowOverlay(Activity a) { act = a; }
        @Override public void run() {
            if (sShown) return;
            try {
                LinearLayout panel = new LinearLayout(act);
                panel.setOrientation(LinearLayout.VERTICAL);
                panel.setBackgroundColor(Color.argb(225, 16, 20, 16));
                panel.setPadding(22, 22, 22, 22);

                TextView title = new TextView(act);
                title.setText("FarmerShoot v8 — INJECTED ✓");
                title.setTextColor(Color.rgb(170, 255, 140));
                title.setTextSize(16);
                panel.addView(title);

                TextView status = new TextView(act);
                status.setTextColor(Color.WHITE);
                status.setTextSize(11);
                status.setText(sNativeOk ? "native: загружен, читаю il2cpp..." : "native: не загружен (окно работает)");
                panel.addView(status);

                LinearLayout row = new LinearLayout(act);
                Button bDump = new Button(act); bDump.setText("DUMP имена"); bDump.setOnClickListener(new OnDump(act));
                Button bJump = new Button(act); bJump.setText("Прыжок ×3"); bJump.setOnClickListener(new OnJump(act));
                Button bRest = new Button(act); bRest.setText("Откат"); bRest.setOnClickListener(new OnRestore(act));
                row.addView(bDump); row.addView(bJump); row.addView(bRest);
                panel.addView(row);

                TextView list = new TextView(act);
                list.setTextColor(Color.rgb(200, 220, 200));
                list.setTextSize(9);
                list.setText("...");
                ScrollView sv = new ScrollView(act);
                sv.addView(list);
                LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(900, 700);
                panel.addView(sv, svp);

                Button bHide = new Button(act); bHide.setText("спрятать"); bHide.setOnClickListener(new OnHide(panel));
                panel.addView(bHide);

                WindowManager wm = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.format = PixelFormat.TRANSLUCENT;
                lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                         | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                lp.gravity = Gravity.TOP | Gravity.START; lp.x = 24; lp.y = 120;
                boolean overlay = Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(act);
                if (overlay) {
                    lp.type = Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                } else {
                    lp.type = WindowManager.LayoutParams.TYPE_APPLICATION;
                    lp.token = act.getWindow().getDecorView().getWindowToken();
                }
                try { wm.addView(panel, lp); }
                catch (Throwable t1) {
                    // запасной путь: прямо в контент активити
                    act.addContentView(panel, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                sShown = true;
                Toast.makeText(act, "FarmerShoot v8 ✓ окно показано", Toast.LENGTH_LONG).show();
                new Handler(Looper.getMainLooper()).post(new Refresh(status, list));
            } catch (Throwable t) { XposedBridge.log(TAG + ": overlay failed: " + t); }
        }
    }

    // авто-обновление текста из nativeInfo()
    static final class Refresh implements Runnable {
        final TextView status, list; Refresh(TextView s, TextView l) { status = s; list = l; }
        @Override public void run() {
            try {
                if (sNativeOk) {
                    String info = nativeInfo();
                    int nl = info.indexOf('\n');
                    String head = nl > 0 ? info.substring(0, nl) : info;
                    status.setText(head);
                    list.setText(info);
                } else {
                    status.setText("native не загружен — пришлите лог LSPatch по тегу FarmerV8");
                }
            } catch (Throwable ignore) {}
            new Handler(Looper.getMainLooper()).postDelayed(this, 800);
        }
    }

    static final class OnDump implements View.OnClickListener {
        final Activity act; OnDump(Activity a) { act = a; }
        @Override public void onClick(View v) {
            String dir = sDumpDir;
            if (dir == null) { try { File d = act.getExternalFilesDir(null); if (d != null) dir = d.getAbsolutePath(); } catch (Throwable ignore) {} }
            if (dir == null) dir = act.getCacheDir().getAbsolutePath();
            String path = dir + "/v8_dump.txt";
            boolean ok = false;
            try { if (sNativeOk) ok = nativeDumpTo(path); } catch (Throwable ignore) {}
            Toast.makeText(act, ok ? ("имена записаны: " + path) : "native нет / не готово", Toast.LENGTH_LONG).show();
        }
    }
    static final class OnJump implements View.OnClickListener {
        final Activity act; OnJump(Activity a) { act = a; }
        @Override public void onClick(View v) {
            boolean ok = false; try { if (sNativeOk) ok = nativePatchJump(3.0f); } catch (Throwable ignore) {}
            Toast.makeText(act, ok ? "прыжок ×3 активен" : "нужны имена класса/поля (см. README)", Toast.LENGTH_SHORT).show();
        }
    }
    static final class OnRestore implements View.OnClickListener {
        final Activity act; OnRestore(Activity a) { act = a; }
        @Override public void onClick(View v) {
            boolean ok = false; try { if (sNativeOk) ok = nativeRestore(); } catch (Throwable ignore) {}
            Toast.makeText(act, ok ? "откат" : "нечего откатывать", Toast.LENGTH_SHORT).show();
        }
    }
    static final class OnHide implements View.OnClickListener {
        final View panel; OnHide(View p) { panel = p; }
        @Override public void onClick(View v) { panel.setVisibility(View.GONE); }
    }
}
