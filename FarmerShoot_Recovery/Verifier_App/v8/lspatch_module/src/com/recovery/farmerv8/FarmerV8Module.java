package com.recovery.farmerv8;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
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
 * FarmerShoot v8 — LSPatch / Xposed модуль (FINAL v3, максимально надёжный показ).
 *
 * Цепочка укреплена на каждом шаге:
 *  - heartbeat-ТОСТ каждые 2.5с со статусом стадии (виден на Vulkan/GL всегда);
 *    если тоста нет вообще — модуль не исполняется LSPatch (см. README).
 *  - Activity берём через registerActivityLifecycleCallbacks (надёжно), с
 *    запасной рефлексией; НИКАКИХ хуков фреймворка.
 *  - окно через WindowManager (несколько типов) + запас addContentView.
 *  - нативный движок libfarmerv8.so даёт il2cpp-данные независимо от рендера.
 */
public class FarmerV8Module implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    static final String TAG = "FarmerV8";
    static String sModulePath = null;
    static volatile boolean sNativeOk = false;
    static volatile boolean sStarted = false;
    static volatile boolean sShown = false;
    static volatile String sStage = "start";
    static volatile String sDumpDir = null;
    static volatile Context sApp = null;

    static native String nativeInfo();
    static native boolean nativeDumpTo(String path);
    static native String nativeDumpFiles(String dir);   // libunity.so + global-metadata.dat
    static native boolean nativePatchJump(float mul);
    static native boolean nativeRestore();

    @Override public void initZygote(StartupParam sp) { try { sModulePath = sp.modulePath; } catch (Throwable ignore) {} }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try { XposedBridge.log(TAG + ": handleLoadPackage " + lpparam.packageName); } catch (Throwable ignore) {}
        if (sStarted) return; sStarted = true;
        sStage = "loaded:" + lpparam.packageName;
        try { Thread t = new Thread(new Bootstrap()); t.setDaemon(true); t.start(); }
        catch (Throwable e) { try { XposedBridge.log(TAG + ": bootstrap start failed " + e); } catch (Throwable ignore) {} }
    }

    // ---- фон: контекст -> heartbeat -> native -> lifecycle -> окно -----------
    static final class Bootstrap implements Runnable {
        @Override public void run() {
            Context ctx = null;
            for (int i = 0; i < 240 && ctx == null; i++) { ctx = appContext(); if (ctx == null) { sStage = "ждём контекст " + i; sleep(500); } }
            sApp = ctx;
            // heartbeat-тосты (видно всегда) — запускаем как только есть контекст
            if (ctx != null) new Handler(Looper.getMainLooper()).post(new Heartbeat(ctx, 0));
            if (ctx != null) {
                try { File d = ctx.getExternalFilesDir(null); if (d != null) { sDumpDir = d.getAbsolutePath(); writeAlive(d); } } catch (Throwable ignore) {}
                sStage = "native: загружаю";
                loadNative(ctx);
                sStage = sNativeOk ? "native OK, ищу активити" : "native нет, ищу активити";
                // надёжный путь: lifecycle callbacks
                try { if (ctx instanceof Application) ((Application) ctx).registerActivityLifecycleCallbacks(new LifeCb()); }
                catch (Throwable t) { try { XposedBridge.log(TAG + ": lifecycle reg failed " + t); } catch (Throwable ignore) {} }
            }
            // запасной путь: поллинг активной активити рефлексией
            for (int i = 0; i < 600 && !sShown; i++) {
                Activity a = topActivity();
                if (a != null) { final Activity fa = a; a.runOnUiThread(new ShowOverlay(fa)); }
                sleep(700);
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

    static void writeAlive(File dir) {
        try {
            File f = new File(dir, "v8_alive.txt");
            FileOutputStream os = new FileOutputStream(f);
            os.write(("FarmerV8 alive. stage=" + sStage + " native=" + sNativeOk + " ts=" + System.currentTimeMillis()).getBytes());
            os.close();
        } catch (Throwable ignore) {}
    }

    // heartbeat: показывает стадию тостом, пока окно не появилось
    static final class Heartbeat implements Runnable {
        final Context ctx; int n; Heartbeat(Context c, int n) { ctx = c; this.n = n; }
        @Override public void run() {
            try {
                if (!sShown && n < 24) {
                    String msg = "v8: " + sStage + (sNativeOk ? " · " + shortInfo() : "");
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                    new Handler(Looper.getMainLooper()).postDelayed(new Heartbeat(ctx, n + 1), 2500);
                }
            } catch (Throwable ignore) {}
        }
    }
    static String shortInfo() {
        try { String s = nativeInfo(); int nl = s.indexOf('\n'); return nl > 0 ? s.substring(0, nl) : s; }
        catch (Throwable t) { return ""; }
    }

    // lifecycle callbacks — надёжно отдают Activity
    static final class LifeCb implements Application.ActivityLifecycleCallbacks {
        public void onActivityResumed(Activity a) { if (!sShown && a != null) a.runOnUiThread(new ShowOverlay(a)); }
        public void onActivityCreated(Activity a, Bundle b) {}
        public void onActivityStarted(Activity a) { if (!sShown && a != null) a.runOnUiThread(new ShowOverlay(a)); }
        public void onActivityPaused(Activity a) {}
        public void onActivityStopped(Activity a) {}
        public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        public void onActivityDestroyed(Activity a) {}
    }

    // запас: активная Activity через рефлексию ActivityThread.mActivities
    static Activity topActivity() {
        try {
            Class<?> atc = Class.forName("android.app.ActivityThread");
            Object at = atc.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field fa = atc.getDeclaredField("mActivities"); fa.setAccessible(true);
            Object obj = fa.get(at);
            if (!(obj instanceof Map)) return null;
            for (Object rec : ((Map<?, ?>) obj).values()) {
                Class<?> rc = rec.getClass();
                try {
                    java.lang.reflect.Field pf = rc.getDeclaredField("paused"); pf.setAccessible(true);
                    if (pf.getBoolean(rec)) continue;
                } catch (Throwable ignore) {}
                java.lang.reflect.Field af = rc.getDeclaredField("activity"); af.setAccessible(true);
                Object a = af.get(rec);
                if (a instanceof Activity) return (Activity) a;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    // ---- native ----
    static synchronized void loadNative(Context ctx) {
        if (sNativeOk) return;
        String apk = findModuleApk(ctx);
        if (apk == null) { try { XposedBridge.log(TAG + ": apk not found"); } catch (Throwable ignore) {} return; }
        ZipFile zf = null;
        try {
            zf = new ZipFile(apk);
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libfarmerv8.so");
            if (e == null) e = zf.getEntry("assets/libfarmerv8.so");
            if (e == null) return;
            File out = new File(ctx.getCacheDir(), "libfarmerv8_" + e.getCrc() + ".so");
            if (!out.exists() || out.length() != e.getSize()) {
                InputStream in = zf.getInputStream(e); OutputStream os = new FileOutputStream(out);
                byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) os.write(b, 0, n);
                os.close(); in.close();
            }
            System.load(out.getAbsolutePath());
            sNativeOk = true;
        } catch (Throwable t) { try { XposedBridge.log(TAG + ": native load failed " + t); } catch (Throwable ignore) {} }
        finally { if (zf != null) try { zf.close(); } catch (Throwable ignore) {} }
    }
    static String findModuleApk(Context ctx) {
        if (sModulePath != null && new File(sModulePath).exists() && zipHasLib(sModulePath)) return sModulePath;
        try { String h = scan(new File(ctx.getCacheDir(), "lspatch")); if (h != null) return h; } catch (Throwable ignore) {}
        try { String h = scan(ctx.getCacheDir()); if (h != null) return h; } catch (Throwable ignore) {}
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
        try { zf = new ZipFile(apk); return zf.getEntry("lib/arm64-v8a/libfarmerv8.so") != null || zf.getEntry("assets/libfarmerv8.so") != null; }
        catch (Throwable t) { return false; } finally { if (zf != null) try { zf.close(); } catch (Throwable ignore) {} }
    }

    // ---- окно ----
    static final class ShowOverlay implements Runnable {
        final Activity act; ShowOverlay(Activity a) { act = a; }
        @Override public void run() {
            if (sShown) return;
            try {
                LinearLayout panel = new LinearLayout(act);
                panel.setOrientation(LinearLayout.VERTICAL);
                panel.setBackgroundColor(Color.argb(230, 14, 18, 14));
                panel.setPadding(22, 22, 22, 22);

                // шапка всегда видна; по тапу сворачивает/разворачивает тело
                TextView title = new TextView(act);
                title.setText("FarmerShoot v8 ✓  (тап — свернуть)");
                title.setTextColor(Color.rgb(170, 255, 140)); title.setTextSize(15);
                title.setPadding(0, 0, 0, 8);
                panel.addView(title);

                LinearLayout body = new LinearLayout(act);
                body.setOrientation(LinearLayout.VERTICAL);
                panel.addView(body);

                TextView status = new TextView(act);
                status.setTextColor(Color.WHITE); status.setTextSize(11);
                status.setText(sNativeOk ? "native OK" : "native нет");
                body.addView(status);

                LinearLayout row = new LinearLayout(act);
                Button bDump = new Button(act); bDump.setText("DUMP имена"); bDump.setOnClickListener(new OnDump(act));
                Button bJump = new Button(act); bJump.setText("Прыжок ×3"); bJump.setOnClickListener(new OnJump(act));
                Button bRest = new Button(act); bRest.setText("Откат"); bRest.setOnClickListener(new OnRestore(act));
                row.addView(bDump); row.addView(bJump); row.addView(bRest);
                body.addView(row);

                // снять libunity.so + расшифрованную global-metadata.dat (для Il2CppDumper)
                Button bFiles = new Button(act); bFiles.setText("СНЯТЬ ДАМП (файлы)"); bFiles.setOnClickListener(new OnDumpFiles(act, status));
                body.addView(bFiles);

                TextView list = new TextView(act);
                list.setTextColor(Color.rgb(200, 220, 200)); list.setTextSize(9); list.setText("...");
                ScrollView sv = new ScrollView(act); sv.addView(list);
                body.addView(sv, new LinearLayout.LayoutParams(900, 620));

                // свернуть/развернуть (НЕ закрывает окно — шапка остаётся)
                title.setOnClickListener(new OnToggle(body, title));

                boolean added = addViaWM(act, panel);
                if (!added) {
                    act.addContentView(panel, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                sShown = true; sStage = "окно показано";
                Toast.makeText(act, "FarmerShoot v8 ✓ окно показано", Toast.LENGTH_LONG).show();
                new Handler(Looper.getMainLooper()).post(new Refresh(status, list));
            } catch (Throwable t) { try { XposedBridge.log(TAG + ": overlay failed " + t); } catch (Throwable ignore) {} }
        }
    }

    // пробуем несколько типов окна
    static boolean addViaWM(Activity act, View panel) {
        WindowManager wm;
        try { wm = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE); } catch (Throwable t) { return false; }
        int[] types;
        boolean canOverlay = Build.VERSION.SDK_INT >= 23 && canDraw(act);
        if (canOverlay) {
            types = new int[]{ Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                                           : WindowManager.LayoutParams.TYPE_PHONE,
                               WindowManager.LayoutParams.TYPE_APPLICATION };
        } else {
            types = new int[]{ WindowManager.LayoutParams.TYPE_APPLICATION,
                               WindowManager.LayoutParams.TYPE_APPLICATION_PANEL };
        }
        for (int type : types) {
            try {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT; lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.format = PixelFormat.TRANSLUCENT;
                lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                lp.gravity = Gravity.TOP | Gravity.START; lp.x = 24; lp.y = 110;
                lp.type = type;
                if (type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY && type != WindowManager.LayoutParams.TYPE_PHONE) {
                    try { lp.token = act.getWindow().getDecorView().getWindowToken(); } catch (Throwable ignore) {}
                }
                wm.addView(panel, lp);
                return true;
            } catch (Throwable ignore) {}
        }
        return false;
    }
    static boolean canDraw(Context c) { try { return Settings.canDrawOverlays(c); } catch (Throwable t) { return false; } }

    static final class Refresh implements Runnable {
        final TextView status, list; Refresh(TextView s, TextView l) { status = s; list = l; }
        @Override public void run() {
            try {
                if (sNativeOk) { String info = nativeInfo(); int nl = info.indexOf('\n'); status.setText(nl > 0 ? info.substring(0, nl) : info); list.setText(info); }
                else status.setText("native нет — но окно работает");
            } catch (Throwable ignore) {}
            new Handler(Looper.getMainLooper()).postDelayed(this, 900);
        }
    }

    static final class OnDump implements View.OnClickListener {
        final Activity act; OnDump(Activity a) { act = a; }
        @Override public void onClick(View v) {
            String dir = sDumpDir;
            if (dir == null) { try { File d = act.getExternalFilesDir(null); if (d != null) dir = d.getAbsolutePath(); } catch (Throwable ignore) {} }
            if (dir == null) dir = act.getCacheDir().getAbsolutePath();
            String path = dir + "/v8_dump.txt";
            boolean ok = false; try { if (sNativeOk) ok = nativeDumpTo(path); } catch (Throwable ignore) {}
            Toast.makeText(act, ok ? ("имена: " + path) : "native нет / не готово", Toast.LENGTH_LONG).show();
        }
    }
    // снятие файлов для Il2CppDumper в files-папку
    static final class OnDumpFiles implements View.OnClickListener {
        final Activity act; final TextView status; OnDumpFiles(Activity a, TextView s) { act = a; status = s; }
        @Override public void onClick(View v) {
            String dir = sDumpDir;
            if (dir == null) { try { File d = act.getExternalFilesDir(null); if (d != null) dir = d.getAbsolutePath(); } catch (Throwable ignore) {} }
            if (dir == null) dir = act.getCacheDir().getAbsolutePath();
            String r;
            try { r = sNativeOk ? nativeDumpFiles(dir) : "native не загружен"; }
            catch (Throwable t) { r = "err: " + t; }
            final String res = r, fdir = dir;
            status.setText("ДАМП:\n" + res + "\n→ " + fdir);
            Toast.makeText(act, "ДАМП в " + fdir, Toast.LENGTH_LONG).show();
        }
    }

    static final class OnJump implements View.OnClickListener {
        final Activity act; OnJump(Activity a) { act = a; }
        @Override public void onClick(View v) {
            boolean ok = false; try { if (sNativeOk) ok = nativePatchJump(3.0f); } catch (Throwable ignore) {}
            Toast.makeText(act, ok ? "прыжок ×3" : "нужны имена (см. README)", Toast.LENGTH_SHORT).show();
        }
    }
    static final class OnRestore implements View.OnClickListener {
        final Activity act; OnRestore(Activity a) { act = a; }
        @Override public void onClick(View v) {
            boolean ok = false; try { if (sNativeOk) ok = nativeRestore(); } catch (Throwable ignore) {}
            Toast.makeText(act, ok ? "откат" : "—", Toast.LENGTH_SHORT).show();
        }
    }
    // сворачивание тела окна (шапка остаётся, окно не исчезает насовсем)
    static final class OnToggle implements View.OnClickListener {
        final View body; final TextView title; OnToggle(View b, TextView t) { body = b; title = t; }
        @Override public void onClick(View v) {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            title.setText(show ? "FarmerShoot v8 ✓  (тап — свернуть)" : "FarmerShoot v8 ✓  (тап — развернуть)");
        }
    }
}
