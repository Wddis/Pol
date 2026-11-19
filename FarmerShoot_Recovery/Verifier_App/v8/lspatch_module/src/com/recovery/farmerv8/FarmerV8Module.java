package com.recovery.farmerv8;

import android.app.Activity;
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
 * FarmerShoot v8 — LSPatch / Xposed модуль.
 *
 * Что делает внутри процесса игры:
 *   1) Показывает тост «v8 загружен» — доказательство, что инъекция прошла.
 *   2) Добавляет маленькую панель-окно прямо в активити игры (без root и без
 *      overlay-разрешения — мы уже внутри процесса).
 *   3) Пытается подгрузить нативный движок libfarmerv8.so (IL2CPP-резолвер +
 *      ImGui-оверлей + патчи ammo/jump). Если он вшит в модуль — грузит и
 *      запускает; если нет — панель всё равно видна (proof инъекции).
 *
 * ЧЕСТНО: Xposed хукает Java/ART, а логика Unity — нативный IL2CPP. Поэтому
 * чтение патронов и патч прыжка делает НАТИВНАЯ часть, а не Java-хуки.
 */
public class FarmerV8Module implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    static final String TAG = "FarmerV8";
    static String sModulePath = null;   // путь к apk самого модуля (из initZygote)
    static boolean sNativeTried = false;
    static boolean sNativeOk = false;
    static boolean sPanelShown = false;

    // --- native bridge (реализуется в libfarmerv8.so, опционально) ----------
    static native String nativeCheck();         // снимок watch-полей -> строка
    static native boolean nativePatchJump(float mul);  // прыжок ×mul
    static native boolean nativeRestore();      // откат патчей

    @Override
    public void initZygote(StartupParam sp) {
        // LSPatch кладёт сюда путь к apk модуля — отсюда достанем .so.
        sModulePath = sp.modulePath;
        XposedBridge.log(TAG + ": initZygote modulePath=" + sModulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // LSPatch встраивает модуль в одну игру, так что работаем для неё.
        XposedBridge.log(TAG + ": loaded in " + lpparam.packageName);
        tryLoadNative();
        // Поймать активити игры, чтобы показать панель на UI-потоке.
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity",
                    lpparam.classLoader, "onResume", new OnResumeHook());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onResume failed: " + t);
        }
    }

    // Достаём libfarmerv8.so из apk модуля и грузим (если вшит).
    static void tryLoadNative() {
        if (sNativeTried) return;
        sNativeTried = true;
        if (sModulePath == null) { XposedBridge.log(TAG + ": no modulePath, native skipped"); return; }
        ZipFile zf = null;
        try {
            zf = new ZipFile(sModulePath);
            ZipEntry e = zf.getEntry("lib/arm64-v8a/libfarmerv8.so");
            if (e == null) e = zf.getEntry("assets/libfarmerv8.so");
            if (e == null) { XposedBridge.log(TAG + ": native lib not bundled"); return; }
            File out = new File(System.getProperty("java.io.tmpdir", "/data/local/tmp"),
                    "libfarmerv8_" + e.getCrc() + ".so");
            if (!out.exists() || out.length() == 0) {
                InputStream in = zf.getInputStream(e);
                OutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[65536];
                int n;
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

    // Хук Activity.onResume — один раз добавляет панель в окно игры.
    static final class OnResumeHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (sPanelShown) return;
            Object obj = param.thisObject;
            if (!(obj instanceof Activity)) return;
            Activity act = (Activity) obj;
            act.runOnUiThread(new ShowPanel(act));
        }
    }

    // Строит и показывает панель поверх контента активити (в её же окне).
    static final class ShowPanel implements Runnable {
        final Activity act;
        ShowPanel(Activity a) { act = a; }
        @Override
        public void run() {
            if (sPanelShown) return;
            sPanelShown = true;
            try {
                Toast.makeText(act,
                        "FarmerShoot v8 ✓ модуль загружен" + (sNativeOk ? " (native)" : " (java)"),
                        Toast.LENGTH_LONG).show();

                LinearLayout panel = new LinearLayout(act);
                panel.setOrientation(LinearLayout.VERTICAL);
                panel.setBackgroundColor(Color.argb(210, 20, 24, 20));
                panel.setPadding(24, 24, 24, 24);

                TextView title = new TextView(act);
                title.setText("FarmerShoot v8" + (sNativeOk ? " · native" : " · java-only"));
                title.setTextColor(Color.rgb(170, 255, 140));
                panel.addView(title);

                TextView status = new TextView(act);
                status.setTextColor(Color.WHITE);
                status.setText(sNativeOk ? "движок загружен — см. оверлей/лог"
                                         : "инъекция OK. Нативный движок не вшит —\nammo/jump недоступны (нужна .so).");
                panel.addView(status);

                Button bCheck = new Button(act);
                bCheck.setText("ПРОВЕРИТЬ");
                bCheck.setOnClickListener(new OnCheck(act, status));
                panel.addView(bCheck);

                Button bJump = new Button(act);
                bJump.setText("Прыжок ×3");
                bJump.setOnClickListener(new OnJump(act, 3.0f));
                panel.addView(bJump);

                Button bRestore = new Button(act);
                bRestore.setText("Откат");
                bRestore.setOnClickListener(new OnRestore(act));
                panel.addView(bRestore);

                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.leftMargin = 24; lp.topMargin = 120;
                panel.setOnTouchListener(new DragMove(lp, panel));
                act.addContentView(panel, lp);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": panel failed: " + t);
            }
        }
    }

    static final class OnCheck implements View.OnClickListener {
        final Activity act; final TextView out;
        OnCheck(Activity a, TextView o) { act = a; out = o; }
        @Override public void onClick(View v) {
            String r;
            try { r = sNativeOk ? nativeCheck() : "нативный движок не вшит"; }
            catch (Throwable t) { r = "native check err: " + t; }
            out.setText(r);
            Toast.makeText(act, "CHECK: " + r, Toast.LENGTH_SHORT).show();
        }
    }

    static final class OnJump implements View.OnClickListener {
        final Activity act; final float mul;
        OnJump(Activity a, float m) { act = a; mul = m; }
        @Override public void onClick(View v) {
            boolean ok = false;
            try { if (sNativeOk) ok = nativePatchJump(mul); } catch (Throwable ignore) {}
            Toast.makeText(act, ok ? ("прыжок ×" + mul + " применён")
                                   : "нужен нативный движок (.so не вшит)", Toast.LENGTH_SHORT).show();
        }
    }

    static final class OnRestore implements View.OnClickListener {
        final Activity act;
        OnRestore(Activity a) { act = a; }
        @Override public void onClick(View v) {
            boolean ok = false;
            try { if (sNativeOk) ok = nativeRestore(); } catch (Throwable ignore) {}
            Toast.makeText(act, ok ? "откат выполнен" : "нечего откатывать", Toast.LENGTH_SHORT).show();
        }
    }

    // Перетаскивание панели пальцем.
    static final class DragMove implements View.OnTouchListener {
        final FrameLayout.LayoutParams lp; final View target;
        float dx, dy;
        DragMove(FrameLayout.LayoutParams p, View t) { lp = p; target = t; }
        @Override public boolean onTouch(View v, android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    dx = e.getRawX() - lp.leftMargin; dy = e.getRawY() - lp.topMargin; return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    lp.leftMargin = (int) (e.getRawX() - dx);
                    lp.topMargin = (int) (e.getRawY() - dy);
                    target.setLayoutParams(lp); return true;
            }
            return false;
        }
    }
}
