package com.recovery.farmerv8;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
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
    static native String nativeDumpUnityMem(String dir); // libunity.so из ПАМЯТИ (для анализа оффсетов)
    static native boolean nativePatchJump(float mul);
    static native boolean nativeRestore();
    // сканер памяти (поиск адресов значений)
    static native int nativeScanNew(String value, boolean isFloat);
    static native int nativeScanRefine(String value, boolean isFloat);
    static native String nativeScanSave(String dir, boolean isFloat);
    static native int nativeScanPatch(String value, boolean isFloat);
    static native int nativeScanFreeze(String value, boolean isFloat, boolean on);
    // инспектор (поиск без ввода значения)
    static native int nativeScanFirst();
    static native int nativeScanDir(int mode, boolean isFloat);   // 0 умен.,1 увел.,2 измен.,3 не изм.
    static native String nativeScanList();
    // офсет-калькулятор + pointer-scan (кнопка «патроны»)
    static native String nativeFindPath(String dir);
    static native String nativeCalcResolve();
    static native int nativeCalcWrite(String value, boolean isFloat);
    static native int nativeCalcFreeze(String value, boolean isFloat, boolean on);
    static native int nativeCalcSet(String csv);

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

    // ---- окно (современное: круглая перетаскиваемая иконка + скруглённая карточка) ----
    static int dp(Context c, int v) { return (int)(v * c.getResources().getDisplayMetrics().density + 0.5f); }

    static final class Win { WindowManager wm; WindowManager.LayoutParams lp; View root; }

    static final class ShowOverlay implements Runnable {
        final Activity act; ShowOverlay(Activity a) { act = a; }
        @Override public void run() {
            if (sShown) return;
            try {
                int sw = act.getResources().getDisplayMetrics().widthPixels;
                int sh = act.getResources().getDisplayMetrics().heightPixels;

                LinearLayout root = new LinearLayout(act);
                root.setOrientation(LinearLayout.VERTICAL);

                // круглая иконка: перетаскивание (удержание+движение) + тап = меню
                TextView bubble = new TextView(act);
                bubble.setText("v8"); bubble.setTextColor(Color.WHITE); bubble.setTextSize(17);
                bubble.setGravity(Gravity.CENTER);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(Color.argb(240, 46, 150, 80));
                bg.setStroke(dp(act, 2), Color.argb(255, 190, 255, 190));
                bubble.setBackground(bg);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(act, 54), dp(act, 54));
                bubble.setLayoutParams(blp);
                root.addView(bubble);

                // карточка-меню
                LinearLayout card = new LinearLayout(act);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));
                GradientDrawable cbg = new GradientDrawable();
                cbg.setColor(Color.argb(240, 16, 19, 24));
                cbg.setCornerRadius(dp(act, 18));
                cbg.setStroke(dp(act, 1), Color.argb(255, 70, 90, 70));
                card.setBackground(cbg);

                TextView status = new TextView(act);
                status.setTextColor(Color.rgb(170, 255, 140)); status.setTextSize(12);
                status.setPadding(0, 0, 0, dp(act, 6));
                status.setText(sNativeOk ? "native OK" : "native нет");
                card.addView(status);

                card.addView(makeRow(act, new String[]{"DUMP имена", "ДАМП файлы"},
                        new View.OnClickListener[]{ new OnDump(act), new OnDumpFiles(act, status) }));
                card.addView(makeRow(act, new String[]{"🧠 ДАМП libunity (память) → пришли мне"},
                        new View.OnClickListener[]{ new OnDumpUnityMem(act, status) }));

                ScanUI scan = new ScanUI(act);
                scan.build(card);

                TextView list = new TextView(act);
                list.setTextColor(Color.rgb(150, 170, 150)); list.setTextSize(9); list.setText("");
                card.addView(list);

                // карточка в скролл с ограниченной высотой — всё помещается, листается
                ScrollView sv = new ScrollView(act);
                sv.addView(card);
                int cardW = Math.min(dp(act, 340), (int)(sw * 0.94f));
                LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(cardW, (int)(sh * 0.60f));
                svlp.topMargin = dp(act, 6);
                sv.setLayoutParams(svlp);
                sv.setVisibility(View.GONE);            // старт: только иконка
                root.addView(sv);

                Win win = new Win();
                boolean added = addRoot(act, root, win);
                if (!added) act.addContentView(root, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                bubble.setOnTouchListener(new DragToggle(win, sv));

                sShown = true; sStage = "окно показано";
                Toast.makeText(act, "v8 ✓ тащи иконку · тап — меню", Toast.LENGTH_LONG).show();
                new Handler(Looper.getMainLooper()).post(new Refresh(status, list));
            } catch (Throwable t) { try { XposedBridge.log(TAG + ": overlay failed " + t); } catch (Throwable ignore) {} }
        }
    }

    // горизонтальный ряд одинаковых кнопок
    static LinearLayout makeRow(Activity act, String[] labels, View.OnClickListener[] ls) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(act); b.setText(labels[i]); b.setOnClickListener(ls[i]);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p.setMargins(dp(act, 2), dp(act, 2), dp(act, 2), dp(act, 2));
            b.setLayoutParams(p);
            row.addView(b);
        }
        return row;
    }

    // перетаскивание окна за иконку + тап = показать/скрыть меню
    static final class DragToggle implements View.OnTouchListener {
        final Win win; final View menu;
        int sx, sy; float tx, ty; boolean moved;
        DragToggle(Win w, View m) { win = w; menu = m; }
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = win.lp.x; sy = win.lp.y; tx = e.getRawX(); ty = e.getRawY(); moved = false; return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int)(e.getRawX() - tx), dy = (int)(e.getRawY() - ty);
                    if (Math.abs(dx) + Math.abs(dy) > 16) moved = true;
                    win.lp.x = sx + dx; win.lp.y = sy + dy;
                    try { win.wm.updateViewLayout(win.root, win.lp); } catch (Throwable ignore) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) menu.setVisibility(menu.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    return true;
            }
            return false;
        }
    }

    // добавить корневой view как окно (несколько типов) и запомнить wm/lp в Win
    static boolean addRoot(Activity act, View root, Win win) {
        WindowManager wm;
        try { wm = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE); } catch (Throwable t) { return false; }
        int[] types;
        boolean canOverlay = Build.VERSION.SDK_INT >= 23 && canDraw(act);
        if (canOverlay) types = new int[]{ Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                                                       : WindowManager.LayoutParams.TYPE_PHONE,
                                           WindowManager.LayoutParams.TYPE_APPLICATION };
        else types = new int[]{ WindowManager.LayoutParams.TYPE_APPLICATION, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL };
        for (int type : types) {
            try {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT; lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.format = PixelFormat.TRANSLUCENT;
                lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                lp.gravity = Gravity.TOP | Gravity.START; lp.x = dp(act, 12); lp.y = dp(act, 90);
                lp.type = type;
                if (type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY && type != WindowManager.LayoutParams.TYPE_PHONE) {
                    try { lp.token = act.getWindow().getDecorView().getWindowToken(); } catch (Throwable ignore) {}
                }
                wm.addView(root, lp);
                win.wm = wm; win.lp = lp; win.root = root;
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
    static final class OnDumpUnityMem implements View.OnClickListener {
        final Activity act; final TextView status; OnDumpUnityMem(Activity a, TextView s) { act = a; status = s; }
        @Override public void onClick(View v) {
            String dir = sDumpDir;
            if (dir == null) { try { File d = act.getExternalFilesDir(null); if (d != null) dir = d.getAbsolutePath(); } catch (Throwable ignore) {} }
            if (dir == null) dir = act.getCacheDir().getAbsolutePath();
            status.setText("снимаю libunity из памяти (может быть большим)...");
            String r; try { r = sNativeOk ? nativeDumpUnityMem(dir) : "native нет"; } catch (Throwable t) { r = "err: " + t; }
            status.setText(r);
            Toast.makeText(act, r, Toast.LENGTH_LONG).show();
        }
    }
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
    // ===================== СКАНЕР адресов (UI) ==============================
    static final class ScanUI {
        final Activity act; final StringBuilder val = new StringBuilder(); boolean isFloat = false;
        boolean frozen = false;
        TextView disp, res, insp, inspLive, calc; Button freezeBtn;
        ScanUI(Activity a) { act = a; }

        void build(LinearLayout body) {
            TextView t = new TextView(act);
            t.setText("СКАНЕР адресов (патроны / прыжок)");
            t.setTextColor(Color.rgb(255, 205, 100)); t.setTextSize(13);
            t.setPadding(0, dp(act, 8), 0, dp(act, 4));
            body.addView(t);

            // поле значения (скруглённое)
            disp = new TextView(act);
            disp.setTextColor(Color.WHITE); disp.setTextSize(18); disp.setGravity(Gravity.CENTER);
            disp.setPadding(dp(act, 10), dp(act, 8), dp(act, 10), dp(act, 8));
            GradientDrawable dbg = new GradientDrawable();
            dbg.setColor(Color.argb(255, 28, 32, 38)); dbg.setCornerRadius(dp(act, 10));
            disp.setBackground(dbg);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.setMargins(0, dp(act, 2), 0, dp(act, 6)); disp.setLayoutParams(dlp);
            body.addView(disp);

            // цифровая клавиатура
            String[][] rows = { {"7","8","9"}, {"4","5","6"}, {"1","2","3"}, {"0",".","-"} };
            for (String[] r : rows) {
                View.OnClickListener[] ls = new View.OnClickListener[r.length];
                for (int i = 0; i < r.length; i++) ls[i] = new Key(this, r[i]);
                body.addView(makeRow(act, r, ls));
            }
            body.addView(makeRow(act, new String[]{"⌫", "C", "int/float"},
                    new View.OnClickListener[]{ new Key(this, "⌫"), new Key(this, "C"), new TypeToggle(this) }));

            // ПОИСК крупной кнопкой + УТОЧНИТЬ
            body.addView(makeRow(act, new String[]{"🔍 ПОИСК", "УТОЧНИТЬ"},
                    new View.OnClickListener[]{ new DoScan(this, 0), new DoScan(this, 1) }));
            body.addView(makeRow(act, new String[]{"СОХРАНИТЬ txt", "ПАТЧ = значение"},
                    new View.OnClickListener[]{ new DoSave(this), new DoPatch(this) }));
            // заморозка: непрерывно держит значение (если игра его перезаписывает)
            LinearLayout fr = makeRow(act, new String[]{"❄ ЗАМОРОЗИТЬ значение"}, new View.OnClickListener[]{ new DoFreeze(this) });
            freezeBtn = (Button) fr.getChildAt(0);
            body.addView(fr);

            res = new TextView(act); res.setTextColor(Color.rgb(200, 224, 200)); res.setTextSize(11);
            res.setPadding(0, dp(act, 6), 0, 0);
            res.setText("1) впиши текущее число (патроны) → ПОИСК\n2) стрельни, впиши новое → УТОЧНИТЬ (повтори)\n3) останется 1 → СОХРАНИТЬ txt → пришли мне");
            body.addView(res);

            // ========== ИНСПЕКТОР: поиск БЕЗ ввода значения (надёжнее) ==========
            TextView it = new TextView(act);
            it.setText("ИНСПЕКТОР (без ввода, по изменению)");
            it.setTextColor(Color.rgb(120, 200, 255)); it.setTextSize(13);
            it.setPadding(0, dp(act, 12), 0, dp(act, 4));
            body.addView(it);
            body.addView(makeRow(act, new String[]{"НАЧАТЬ СКАН", "ИЗМЕНИЛОСЬ", "НЕ ИЗМ."},
                    new View.OnClickListener[]{ new DoInspect(this, -1), new DoInspect(this, 2), new DoInspect(this, 3) }));
            body.addView(makeRow(act, new String[]{"УМЕНЬШИЛОСЬ ▼", "УВЕЛИЧИЛОСЬ ▲"},
                    new View.OnClickListener[]{ new DoInspect(this, 0), new DoInspect(this, 1) }));
            insp = new TextView(act); insp.setTextColor(Color.rgb(180, 210, 240)); insp.setTextSize(10);
            insp.setText("НАЧАТЬ СКАН → стрельни → УМЕНЬШИЛОСЬ → перезаряди → УВЕЛИЧИЛОСЬ …\nпока адресов не станет мало.");
            body.addView(insp);
            inspLive = new TextView(act); inspLive.setTextColor(Color.rgb(150, 230, 170)); inspLive.setTextSize(10);
            inspLive.setText("живые значения кандидатов:");
            body.addView(inspLive);
            new Handler(Looper.getMainLooper()).postDelayed(new InspRefresh(this), 1200);

            // ========== КАЛЬКУЛЯТОР ОФФСЕТОВ (стабильный адрес) ==========
            TextView ct = new TextView(act);
            ct.setText("СТАБИЛЬНЫЙ ОФФСЕТ (не зависит от оружия)");
            ct.setTextColor(Color.rgb(255, 170, 90)); ct.setTextSize(13);
            ct.setPadding(0, dp(act, 12), 0, dp(act, 4));
            body.addView(ct);
            body.addView(makeRow(act, new String[]{"🎯 ПАТРОНЫ (найти стаб. оффсет)"},
                    new View.OnClickListener[]{ new DoFindPath(this) }));
            body.addView(makeRow(act, new String[]{"ПОКАЗАТЬ", "ПАТЧ по пути", "❄ морозить путь"},
                    new View.OnClickListener[]{ new DoCalcShow(this), new DoCalcPatch(this), new DoCalcFreeze(this) }));
            calc = new TextView(act); calc.setTextColor(Color.rgb(255, 215, 170)); calc.setTextSize(10);
            calc.setText("1) инспектором сведи к 1 адресу ammo\n2) ПАТРОНЫ → строит стабильный путь [libunity+..]\n3) впиши желаемое → ПАТЧ по пути / морозить путь");
            body.addView(calc);

            refresh();
        }
        String value() { return val.length() == 0 ? "0" : val.toString(); }
        void refresh() { if (disp != null) disp.setText((val.length() == 0 ? "0" : val.toString()) + (isFloat ? "   float" : "   int")); }
    }
    static final class Key implements View.OnClickListener {
        final ScanUI s; final String k; Key(ScanUI s, String k) { this.s = s; this.k = k; }
        @Override public void onClick(View v) {
            if ("C".equals(k)) { s.val.setLength(0); }
            else if ("⌫".equals(k)) { if (s.val.length() > 0) s.val.deleteCharAt(s.val.length() - 1); }
            else if (".".equals(k)) { if (s.val.indexOf(".") < 0) { if (s.val.length() == 0) s.val.append("0"); s.val.append("."); s.isFloat = true; } }
            else if ("-".equals(k)) { if (s.val.length() == 0) s.val.append("-"); }        // минус только в начале
            else { s.val.append(k); }                                                       // цифра
            s.refresh();
        }
    }
    static final class TypeToggle implements View.OnClickListener {
        final ScanUI s; TypeToggle(ScanUI s) { this.s = s; }
        @Override public void onClick(View v) { s.isFloat = !s.isFloat; s.refresh(); }
    }
    static final class DoScan implements View.OnClickListener {
        final ScanUI s; final int mode; DoScan(ScanUI s, int m) { this.s = s; mode = m; }
        @Override public void onClick(View v) {
            if (!sNativeOk) { s.res.setText("native не загружен"); return; }
            s.res.setText(mode == 0 ? "сканирую всю кучу..." : "уточняю...");
            int n;
            try { n = (mode == 0) ? nativeScanNew(s.value(), s.isFloat) : nativeScanRefine(s.value(), s.isFloat); }
            catch (Throwable t) { s.res.setText("ошибка: " + t); return; }
            String hint = n == 0 ? " — не найдено, проверь значение/тип"
                        : n == 1 ? " — АДРЕС НАЙДЕН! жми СОХР.txt"
                        : n <= 20 ? " — почти, измени значение в игре и УТОЧНИ"
                        : " — много, измени значение в игре и УТОЧНИ";
            s.res.setText("кандидатов: " + n + hint);
        }
    }
    static final class DoSave implements View.OnClickListener {
        final ScanUI s; DoSave(ScanUI s) { this.s = s; }
        @Override public void onClick(View v) {
            if (!sNativeOk) { s.res.setText("native нет"); return; }
            String dir = sDumpDir;
            if (dir == null) { try { File d = s.act.getExternalFilesDir(null); if (d != null) dir = d.getAbsolutePath(); } catch (Throwable ignore) {} }
            if (dir == null) dir = s.act.getCacheDir().getAbsolutePath();
            String r; try { r = nativeScanSave(dir, s.isFloat); } catch (Throwable t) { r = "err: " + t; }
            s.res.setText(r);
            Toast.makeText(s.act, r, Toast.LENGTH_LONG).show();
        }
    }
    static final class DoPatch implements View.OnClickListener {
        final ScanUI s; DoPatch(ScanUI s) { this.s = s; }
        @Override public void onClick(View v) {
            if (!sNativeOk) { s.res.setText("native нет"); return; }
            String val = s.value();
            int n; try { n = nativeScanPatch(val, s.isFloat); } catch (Throwable t) { s.res.setText("err: " + t); return; }
            if (n < 0) { s.res.setText("слишком много адресов (" + (-n) + ") — сначала УТОЧНИ до ≤50"); Toast.makeText(s.act, "сначала уточни", Toast.LENGTH_SHORT).show(); }
            else { s.res.setText("записал " + val + " → " + n + " адрес(ов). Если откатилось — жми ЗАМОРОЗИТЬ."); Toast.makeText(s.act, "записал " + val + " в " + n, Toast.LENGTH_SHORT).show(); }
        }
    }
    static final class DoFindPath implements View.OnClickListener {
        final ScanUI s; DoFindPath(ScanUI s) { this.s = s; }
        @Override public void onClick(View v) {
            if (!sNativeOk) { s.calc.setText("native нет"); return; }
            s.calc.setText("ищу стабильный путь (пару секунд)...");
            String dir = sDumpDir; if (dir == null) dir = s.act.getCacheDir().getAbsolutePath();
            String r; try { r = nativeFindPath(dir); } catch (Throwable t) { r = "err: " + t; }
            s.calc.setText(r);
            Toast.makeText(s.act, r, Toast.LENGTH_LONG).show();
        }
    }
    static final class DoCalcShow implements View.OnClickListener {
        final ScanUI s; DoCalcShow(ScanUI s) { this.s = s; }
        @Override public void onClick(View v) {
            if (!sNativeOk) { s.calc.setText("native нет"); return; }
            String r; try { r = nativeCalcResolve(); } catch (Throwable t) { r = "err: " + t; }
            s.calc.setText(r);
        }
    }
    static final class DoCalcPatch implements View.OnClickListener {
        final ScanUI s; DoCalcPatch(ScanUI s) { this.s = s; }
        @Override public void onClick(View v) {
            if (!sNativeOk) { s.calc.setText("native нет"); return; }
            String val = s.value(); int n;
            try { n = nativeCalcWrite(val, s.isFloat); } catch (Throwable t) { s.calc.setText("err: " + t); return; }
            s.calc.setText(n > 0 ? ("записал " + val + " по пути. Если откатывается — морозь путь.") : "путь не разрешился (найди ПАТРОНЫ/объект не активен)");
            Toast.makeText(s.act, n > 0 ? ("патч " + val) : "нет пути", Toast.LENGTH_SHORT).show();
        }
    }
    static final class DoCalcFreeze implements View.OnClickListener {
        final ScanUI s; boolean on = false; DoCalcFreeze(ScanUI s) { this.s = s; }
        @Override public void onClick(View v) {
            if (!sNativeOk) { s.calc.setText("native нет"); return; }
            on = !on; String val = s.value(); int n;
            try { n = nativeCalcFreeze(val, s.isFloat, on); } catch (Throwable t) { s.calc.setText("err: " + t); return; }
            s.calc.setText(on ? ("морожу " + val + " по пути (пере-резолв каждый тик)") : "заморозка пути выкл");
            Toast.makeText(s.act, on ? ("морозка пути " + val) : "стоп", Toast.LENGTH_SHORT).show();
        }
    }
    static final class DoInspect implements View.OnClickListener {
        final ScanUI s; final int mode; DoInspect(ScanUI s, int m) { this.s = s; mode = m; }
        @Override public void onClick(View v) {
            if (!sNativeOk) { s.insp.setText("native нет"); return; }
            s.insp.setText(mode == -1 ? "снимок памяти (пару секунд)..." : "фильтрую...");
            int n;
            try { n = (mode == -1) ? nativeScanFirst() : nativeScanDir(mode, s.isFloat); }
            catch (Throwable t) { s.insp.setText("err: " + t); return; }
            String hint = (mode == -1) ? " — снято. измени значение в игре и жми направление"
                        : (n <= 1)  ? " — НАЙДЕНО! смотри живое значение, затем ПАТЧ/ЗАМОРОЗКА"
                        : (n <= 30) ? " — почти, ещё раз измени и фильтруй"
                                    : " — много, продолжай фильтровать по изменению";
            s.insp.setText("кандидатов: " + n + hint);
        }
    }
    static final class InspRefresh implements Runnable {
        final ScanUI s; InspRefresh(ScanUI s) { this.s = s; }
        @Override public void run() {
            try { if (sNativeOk && s.inspLive != null) s.inspLive.setText(nativeScanList()); } catch (Throwable ignore) {}
            new Handler(Looper.getMainLooper()).postDelayed(this, 700);
        }
    }
    static final class DoFreeze implements View.OnClickListener {
        final ScanUI s; DoFreeze(ScanUI s) { this.s = s; }
        @Override public void onClick(View v) {
            if (!sNativeOk) { s.res.setText("native нет"); return; }
            s.frozen = !s.frozen;
            String val = s.value();
            int n; try { n = nativeScanFreeze(val, s.isFloat, s.frozen); } catch (Throwable t) { s.res.setText("err: " + t); return; }
            if (s.freezeBtn != null) s.freezeBtn.setText(s.frozen ? "❄ ЗАМОРОЖЕНО (стоп)" : "❄ ЗАМОРОЗИТЬ значение");
            s.res.setText(s.frozen ? ("держу " + val + " в " + n + " адрес(ах) непрерывно") : "заморозка выключена");
            Toast.makeText(s.act, s.frozen ? ("заморозка " + val) : "стоп", Toast.LENGTH_SHORT).show();
        }
    }
}
