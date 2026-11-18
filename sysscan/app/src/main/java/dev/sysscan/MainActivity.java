package dev.sysscan;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int REQ = 42;
    private static final String GAME = "com.axlebolt.standoff2";

    private TextView status, out;
    private Button modeBtn, fpsBtn;
    private String compactLog = "", fullLog = "", modeReport = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean busy;

    private final Shizuku.OnRequestPermissionResultListener permListener = (code, res) -> {
        if (res == PackageManager.PERMISSION_GRANTED) Shell.connect(false);
        else setStatus("❌ Доступ Shizuku отклонён.");
    };
    private final Shizuku.OnBinderReceivedListener binderListener = () -> Shell.connect(false);
    private final Shizuku.OnBinderDeadListener deadListener = Shell::dead;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        status = new TextView(this);
        status.setTextSize(15);
        root.addView(status);

        root.addView(btn("Разрешить доступ (Shizuku)", v -> Shell.connect(true)));
        modeBtn = btn("", v -> toggleMode());
        modeBtn.setTextSize(18);
        root.addView(modeBtn);
        fpsBtn = btn("", v -> toggleFps());
        root.addView(fpsBtn);
        root.addView(btn("Скопировать отчёт (режим + FPS)", v -> copyText(modeReport + "\n" + FpsService.report, "отчёт")));
        LinearLayout drow = new LinearLayout(this);
        drow.addView(btn("🧹 Разовая чистка навсегда", v -> confirm("Разовая чистка", Debloat.preview(), () -> runBg("⏳ Удаляю мусор MIUI и проверяю…", () -> Debloat.run(this)))),
                new LinearLayout.LayoutParams(0, -2, 2f));
        drow.addView(btn("Вернуть удалённое", v -> confirm("Вернуть удалённое?", "Все пакеты из разовой чистки будут восстановлены.", () -> runBg("⏳ Восстанавливаю…", () -> Debloat.restore(this)))),
                new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(drow);
        LinearLayout row = new LinearLayout(this);
        row.addView(btn("Анализ", v -> analyze()), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(btn("Копир. лог", v -> copyLog()), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(btn("Сохранить", v -> shareFull()), new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(row);

        ScrollView sv = new ScrollView(this);
        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(11);
        out.setTextIsSelectable(true);
        sv.addView(out);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        Shell.setListener(this::setStatus);
        Shizuku.addRequestPermissionResultListener(permListener);
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addBinderDeadListener(deadListener);
        refreshButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshButtons();
        if (!FpsService.report.isEmpty() && !FpsService.running) out.setText(modeReport + "\n" + FpsService.report);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener);
        Shizuku.removeBinderReceivedListener(binderListener);
        Shizuku.removeBinderDeadListener(deadListener);
        Shell.setListener(null);
        super.onDestroy();
    }

    private void refreshButtons() {
        boolean on = GameMode.isOn(this);
        modeBtn.setText(on ? "🎮 Игровой режим: ВКЛЮЧЁН  (нажми — вернуть всё)" : "🎮 Игровой режим: ВЫКЛЮЧЕН  (нажми — включить)");
        fpsBtn.setText(FpsService.running ? "⏹ Остановить FPS-монитор и получить отчёт" : "▶ FPS-монитор + запустить Standoff 2");
    }

    private void toggleMode() {
        if (busy) return;
        if (!Shell.ready()) { toast("Сначала Shizuku: «Разрешить доступ»"); Shell.connect(true); return; }
        busy = true;
        boolean on = GameMode.isOn(this);
        setStatus(on ? "⏳ Возвращаю всё как было и проверяю…" : "⏳ Включаю игровой режим и проверяю…");
        new Thread(() -> {
            String r = on ? GameMode.disable(this) : GameMode.enable(this);
            modeReport = r;
            ui.post(() -> { busy = false; out.setText(r); refreshButtons(); status.setText(GameMode.isOn(this) ? "🎮 Режим включён" : "Режим выключен"); });
        }).start();
    }

    private void confirm(String title, String msg, Runnable ok) {
        if (!Shell.ready()) { toast("Сначала Shizuku: «Разрешить доступ»"); Shell.connect(true); return; }
        new android.app.AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("Выполнить", (d, w) -> ok.run()).setNegativeButton("Отмена", null).show();
    }

    private void runBg(String wait, java.util.concurrent.Callable<String> job) {
        if (busy) return;
        busy = true;
        setStatus(wait);
        new Thread(() -> {
            String r;
            try { r = job.call(); } catch (Throwable t) { r = "❌ " + t; }
            modeReport = r;
            String fr = r;
            ui.post(() -> { busy = false; out.setText(fr); status.setText("Готово. Можно скопировать отчёт."); refreshButtons(); });
        }).start();
    }

    private void toggleFps() {
        if (FpsService.running) {
            FpsService.stop(this);
            ui.postDelayed(() -> { out.setText(modeReport + "\n" + FpsService.report); refreshButtons(); }, 600);
            return;
        }
        if (!Shell.ready()) { toast("Сначала Shizuku: «Разрешить доступ»"); Shell.connect(true); return; }
        FpsService.report = "";
        boolean on = GameMode.isOn(this);
        setStatus(on ? "⏳ Закрываю фон перед игрой…" : "Запускаю монитор…");
        new Thread(() -> {
            if (on) GameMode.preGame(this); // после перезагрузки снова поднимаются синхронизации — гасим их перед матчем
            ui.post(() -> {
                FpsService.start(this);
                Intent i = getPackageManager().getLaunchIntentForPackage(GameMode.GAME);
                if (i != null) startActivity(i); else toast("Standoff 2 не найден");
                ui.postDelayed(this::refreshButtons, 300);
            });
        }).start();
    }

    private Button btn(String t, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void setStatus(String t) { ui.post(() -> status.setText(t)); }

    private String sh(String cmd) { return Shell.sh(cmd); }

    // ===================== АНАЛИЗ =====================

    private void analyze() {
        setStatus("⏳ Анализ… (10–30 сек)");
        out.setText("");
        new Thread(() -> {
            try { buildLogs(); } catch (Throwable t) { compactLog = fullLog = "CRASH: " + t; }
            ui.post(() -> {
                out.setText(screenSummary);
                status.setText("✅ Готово. Нажми «3. Скопировать лог» и отправь его в чат.");
            });
        }).start();
    }

    private String screenSummary = "";

    private static class Proc {
        int pid; String user; long rssKb; String cpu, state, name, kind = "native";
    }

    private void buildLogs() {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        boolean shell = Shell.ready();

        // Сравнение-доказательство: сколько процессов видно без shell-прав
        String localCount = UserService.run("ps -A -o PID | tail -n +2 | wc -l").trim();

        String id = sh("id").trim();
        String psRaw = sh("ps -A -o PID,PPID,USER,RSS,PCPU,S,NAME");
        Set<String> third = pkgSet(sh("pm list packages -3"));
        Set<String> sys = pkgSet(sh("pm list packages -s"));
        Set<String> disabled = pkgSet(sh("pm list packages -d"));

        List<Proc> procs = parsePs(psRaw);
        int nThird = 0, nSysApp = 0, nNative = 0;
        long rssThird = 0, rssSys = 0, rssNative = 0;
        for (Proc pr : procs) {
            String base = pr.name.contains(":") ? pr.name.substring(0, pr.name.indexOf(':')) : pr.name;
            if (third.contains(base)) { pr.kind = "USER"; nThird++; rssThird += pr.rssKb; }
            else if (sys.contains(base)) { pr.kind = "SYSAPP"; nSysApp++; rssSys += pr.rssKb; }
            else { nNative++; rssNative += pr.rssKb; }
        }
        Collections.sort(procs, (a, b) -> Long.compare(b.rssKb, a.rssKb));

        String meminfo = sh("cat /proc/meminfo");
        String memTotal = memField(meminfo, "MemTotal"), memAvail = memField(meminfo, "MemAvailable"),
                swapT = memField(meminfo, "SwapTotal"), swapF = memField(meminfo, "SwapFree");

        StringBuilder c = new StringBuilder();
        c.append("=== SYSSCAN LOG v").append(BuildConfig.VERSION_NAME).append(" | ").append(ts).append(" ===\n");
        c.append("MODE: ").append(shell ? "SHIZUKU/SHELL" : "NO-SHIZUKU (ограничено!)").append('\n');
        c.append("ID: ").append(id).append('\n');
        c.append("PROOF: процессов видно с этими правами=").append(procs.size())
                .append(" | без shell (права обычного приложения)=").append(localCount).append('\n');
        c.append("COUNTS: user_apps=").append(nThird).append(" (").append(rssThird / 1024).append("MB)")
                .append(" sys_apps=").append(nSysApp).append(" (").append(rssSys / 1024).append("MB)")
                .append(" native/daemons=").append(nNative).append(" (").append(rssNative / 1024).append("MB)\n");

        sec(c, "DEVICE", sh("for p in ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk "
                + "ro.miui.ui.version.name ro.build.version.incremental ro.board.platform ro.hardware.egl "
                + "persist.sys.miui_optimization dalvik.vm.heapsize ro.config.low_ram; do echo \"$p=$(getprop $p)\"; done; "
                + "echo uptime=$(cat /proc/uptime); df -h /data | tail -1"));
        sec(c, "RAM", "MemTotal=" + memTotal + " MemAvailable=" + memAvail + " SwapTotal=" + swapT + " SwapFree=" + swapF + "\n"
                + grepLines(meminfo, "Cached:", "Active:", "Inactive:", "Shmem:", "Mlocked:")
                + sh("cat /sys/block/zram0/disksize /sys/block/zram0/mm_stat 2>&1 | head -2; "
                + "echo swappiness=$(cat /proc/sys/vm/swappiness 2>&1); "
                + "echo lmk_props: $(getprop ro.lmk.use_minfree_levels) $(getprop ro.lmk.kill_heaviest_task) $(getprop ro.lmk.swap_free_low_percentage)"));
        sec(c, "CPU CORES", sh("for d in /sys/devices/system/cpu/cpu[0-9]*; do n=${d##*/}; "
                + "echo \"$n online=$(cat $d/online 2>/dev/null || echo 1) cur=$(cat $d/cpufreq/scaling_cur_freq 2>&1) "
                + "min=$(cat $d/cpufreq/scaling_min_freq 2>&1) max=$(cat $d/cpufreq/scaling_max_freq 2>&1) "
                + "hwmax=$(cat $d/cpufreq/cpuinfo_max_freq 2>&1) gov=$(cat $d/cpufreq/scaling_governor 2>&1)\"; done; "
                + "echo loadavg=$(cat /proc/loadavg); head -1 /proc/stat"));
        sec(c, "GPU", sh("d=/sys/class/kgsl/kgsl-3d0; for f in gpuclk max_gpuclk min_clock_mhz max_clock_mhz gpu_busy_percentage devfreq/governor thermal_pwrlevel; do echo \"$f=$(cat $d/$f 2>&1)\"; done"));
        sec(c, "THERMAL", sh("for z in /sys/class/thermal/thermal_zone*; do t=$(cat $z/temp 2>/dev/null); [ -n \"$t\" ] && echo \"$(cat $z/type)=$t\"; done | head -40; "
                + "echo thermal_mode=$(getprop sys.thermal.data.path) $(cat /sys/class/thermal/thermal_message/sconfig 2>&1)"));
        sec(c, "BATTERY", grepLines(sh("dumpsys battery"), "level", "temperature", "status", "health", "AC powered", "USB powered"));
        sec(c, "CPU LOAD BY PROCESS (dumpsys cpuinfo)", head(sh("dumpsys cpuinfo"), 60));
        sec(c, "TOP SNAPSHOT", head(sh("top -b -n 1 -m 35 -o PID,USER,%CPU,RES,S,NAME"), 45));

        StringBuilder pl = new StringBuilder("RSS_MB KIND   PID   USER       CPU% S NAME\n");
        for (Proc pr : procs) {
            pl.append(String.format(Locale.US, "%6.1f %-6s %-5d %-10s %4s %s %s\n",
                    pr.rssKb / 1024.0, pr.kind, pr.pid, trim(pr.user, 10), pr.cpu, pr.state, pr.name));
        }
        sec(c, "ALL PROCESSES (" + procs.size() + ", по ОЗУ)", pl.toString());
        sec(c, "PSS BY PROCESS / OOM (dumpsys meminfo)", head(sh("dumpsys meminfo"), 260));
        sec(c, "PROCESS STATES (dumpsys activity lru)", head(sh("dumpsys activity lru"), 150));
        sec(c, "THIRD-PARTY PACKAGES (" + third.size() + ")", join(third));
        sec(c, "DISABLED PACKAGES (" + disabled.size() + ")", join(disabled));
        sec(c, "SYSTEM PACKAGES (" + sys.size() + ")", join(sys));
        sec(c, "GAME " + GAME, sh("dumpsys package " + GAME + " | grep -E 'versionName|targetSdk|lastUpdateTime' | head -5; "
                + "pidof " + GAME + " && echo RUNNING || echo NOT_RUNNING"));
        sec(c, "DISPLAY/ANIM", sh("dumpsys display | grep -E 'mRefreshRate|fps=' | head -5; "
                + "for k in window_animation_scale transition_animation_scale animator_duration_scale; do echo \"$k=$(settings get global $k)\"; done"));
        sec(c, "WAKELOCKS", head(sh("dumpsys power | grep -E 'PARTIAL_WAKE_LOCK|FULL_WAKE_LOCK|SCREEN_'"), 40));
        sec(c, "AUTOSTART-ish: deviceidle whitelist", head(sh("dumpsys deviceidle whitelist"), 80));
        c.append("=== END ===\n");
        compactLog = c.toString();

        StringBuilder f = new StringBuilder(compactLog);
        sec(f, "RAW ps -A", psRaw);
        sec(f, "pm list packages -U", sh("pm list packages -U"));
        sec(f, "dumpsys activity processes", head(sh("dumpsys activity processes"), 1500));
        sec(f, "dumpsys jobscheduler (head)", head(sh("dumpsys jobscheduler"), 600));
        sec(f, "dumpsys alarm (head)", head(sh("dumpsys alarm"), 600));
        sec(f, "dumpsys batterystats --charged (head)", head(sh("dumpsys batterystats --charged"), 800));
        fullLog = f.toString();

        StringBuilder scr = new StringBuilder();
        scr.append(shell ? "✅ Режим SHELL через Shizuku\n" : "⚠️ БЕЗ Shizuku — Android прячет чужие процессы\n");
        scr.append(id).append("\n\n");
        scr.append("Видно процессов: ").append(procs.size()).append("  (без shell: ").append(localCount).append(")\n");
        scr.append("Скачанные: ").append(nThird).append(" | системные прилож.: ").append(nSysApp)
                .append(" | демоны/ядро: ").append(nNative).append('\n');
        scr.append("ОЗУ: свободно ").append(memAvail).append(" из ").append(memTotal).append('\n');
        scr.append("Лог: ").append(compactLog.length() / 1024).append(" КБ (полный ").append(fullLog.length() / 1024).append(" КБ)\n\n");
        scr.append("ТОП-40 по ОЗУ:\n");
        for (int i = 0; i < Math.min(40, procs.size()); i++) {
            Proc pr = procs.get(i);
            scr.append(String.format(Locale.US, "%6.1fMB %-6s %s\n", pr.rssKb / 1024.0, pr.kind, pr.name));
        }
        screenSummary = scr.toString();
    }

    // ===================== ВЫВОД =====================

    private void copyLog() {
        if (compactLog.isEmpty()) { toast("Сначала нажми «Анализ»"); return; }
        copyText(compactLog, "лог");
    }

    private void copyText(String text, String what) {
        if (text.trim().isEmpty()) { toast("Пока пусто"); return; }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("sysscan", text));
            toast("Скопирован " + what + " (" + Math.max(1, text.length() / 1024) + " КБ). Вставь в чат.");
        } catch (Throwable t) {
            toast("Буфер не принял (" + t.getClass().getSimpleName() + "). Используй «Сохранить и поделиться».");
        }
    }

    private void shareFull() {
        if (fullLog.isEmpty()) { toast("Сначала нажми «Анализ»"); return; }
        try {
            String name = "SysScan_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
            Uri uri;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(fullLog.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                java.io.File file = new java.io.File(getExternalFilesDir(null), name);
                try (OutputStream os = new java.io.FileOutputStream(file)) {
                    os.write(fullLog.getBytes(StandardCharsets.UTF_8));
                }
                uri = Uri.fromFile(file);
            }
            toast("Сохранено в Загрузки/" + name);
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Отправить лог"));
        } catch (Throwable t) {
            toast("Ошибка сохранения: " + t);
        }
    }

    // ===================== УТИЛИТЫ =====================

    private static List<Proc> parsePs(String raw) {
        List<Proc> list = new ArrayList<>();
        String[] lines = raw.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String[] t = lines[i].trim().split("\\s+", 7);
            if (t.length < 7) continue;
            try {
                Proc p = new Proc();
                p.pid = Integer.parseInt(t[0]);
                p.user = t[2];
                p.rssKb = Long.parseLong(t[3]);
                p.cpu = t[4];
                p.state = t[5];
                p.name = t[6].trim();
                if (p.pid == 2 || "2".equals(t[1])) { p.kind = "kernel"; } // kthreadd и его потоки
                list.add(p);
            } catch (NumberFormatException ignored) { }
        }
        return list;
    }

    private static Set<String> pkgSet(String raw) {
        Set<String> s = new HashSet<>();
        for (String l : raw.split("\n")) if (l.startsWith("package:")) s.add(l.substring(8).trim());
        return s;
    }

    private static String join(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        Collections.sort(l);
        StringBuilder b = new StringBuilder();
        for (String x : l) b.append(x).append('\n');
        return b.toString();
    }

    private static void sec(StringBuilder b, String title, String body) {
        b.append("\n--- ").append(title).append(" ---\n").append(body.trim()).append('\n');
    }

    private static String head(String s, int n) {
        String[] l = s.split("\n");
        if (l.length <= n) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(l[i]).append('\n');
        return b.append("…(+").append(l.length - n).append(" строк)\n").toString();
    }

    private static String grepLines(String s, String... keys) {
        StringBuilder b = new StringBuilder();
        for (String l : s.split("\n"))
            for (String k : keys) if (l.contains(k)) { b.append(l.trim()).append('\n'); break; }
        return b.toString();
    }

    private static String memField(String meminfo, String key) {
        for (String l : meminfo.split("\n")) {
            if (l.startsWith(key + ":")) {
                try {
                    long kb = Long.parseLong(l.replaceAll("[^0-9]", ""));
                    return (kb / 1024) + "MB";
                } catch (NumberFormatException e) { return l; }
            }
        }
        return "?";
    }

    private static String trim(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    private void toast(String t) { ui.post(() -> Toast.makeText(this, t, Toast.LENGTH_LONG).show()); }
}
