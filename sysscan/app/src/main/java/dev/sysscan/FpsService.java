package dev.sysscan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * FPS-монитор игры: раз в секунду читает кадры слоя игры из SurfaceFlinger
 * (dumpsys SurfaceFlinger --latency "<слой>", AOSP SurfaceFlinger::dumpStatsLocked),
 * частоты ядер, температуры и свободную ОЗУ. Это и есть доказательная база: реальные числа во время матча.
 */
public class FpsService extends Service {

    public static volatile boolean running;
    public static volatile String report = "";
    public static volatile String live = "";

    private static final String CH = "fps";
    private volatile boolean stop;

    static class Sample {
        long t; int fps; double worstMs; int big, little; double cpuT, gpuT, batT; long memMb;
    }

    private final List<Sample> samples = new ArrayList<>();
    private final List<Double> intervals = new ArrayList<>();
    private String layer = null, zones = "", gpuZone = "", startMode;
    private long lastFrameNs = 0;
    private int hwMaxBig = 2016000;

    public static void start(Context c) {
        Intent i = new Intent(c, FpsService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void stop(Context c) { c.stopService(new Intent(c, FpsService.class)); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_NOT_STICKY;
        running = true;
        stop = false;
        startForeground(1, notif("FPS-монитор запущен. Открой Standoff 2."));
        startMode = GameMode.isOn(this) ? "ИГРОВОЙ РЕЖИМ ВКЛ" : "ИГРОВОЙ РЕЖИМ ВЫКЛ";
        new Thread(this::loop, "fps").start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stop = true;
        running = false;
        report = buildReport();
        super.onDestroy();
    }

    private Notification notif(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(new NotificationChannel(CH, "FPS", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("SysScan FPS")
                .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).build();
    }

    private void loop() {
        // один раз: найти нужные термозоны и макс. частоту больших ядер
        String z = Shell.sh("for z in /sys/class/thermal/thermal_zone*; do echo \"$z/temp $(cat $z/type)\"; done; "
                + "echo HW $(cat /sys/devices/system/cpu/cpu4/cpufreq/cpuinfo_max_freq)");
        StringBuilder zs = new StringBuilder();
        for (String l : z.split("\n")) {
            String[] t = l.trim().split(" ");
            if (t.length < 2) continue;
            if (t[0].equals("HW")) { try { hwMaxBig = Integer.parseInt(t[1]); } catch (Exception ignored) { } }
            else if (t[1].matches("cpu-1-[0-3]-usr")) zs.append(t[0]).append(' ');
            else if (t[1].equals("gpu-usr")) gpuZone = t[0];
        }
        zones = zs.toString().trim();

        long t0 = System.currentTimeMillis();
        int noLayer = 0;
        while (!stop) {
            long tick = System.currentTimeMillis();
            try {
                if (layer == null || noLayer > 5) { layer = findLayer(); noLayer = 0; }
                String cmd = (layer != null ? "dumpsys SurfaceFlinger --latency '" + layer + "'; " : "")
                        + "echo '#S'; cat /sys/devices/system/cpu/cpu4/cpufreq/scaling_cur_freq /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq; "
                        + "echo '#T'; cat " + (zones.isEmpty() ? "/dev/null" : zones) + "; "
                        + "echo '#G'; " + (gpuZone.isEmpty() ? "" : "cat " + gpuZone + "; ")
                        + "echo '#B'; cat /sys/class/power_supply/battery/temp; "
                        + "echo '#M'; grep MemAvailable /proc/meminfo";
                Sample s = parse(Shell.sh(cmd));
                s.t = (tick - t0) / 1000;
                if (s.fps == 0) noLayer++; else noLayer = 0;
                synchronized (samples) { samples.add(s); }
                live = String.format(Locale.US, "FPS %d | худший кадр %.0f мс | ядра %d МГц | CPU %.0f°C | ОЗУ %d МБ",
                        s.fps, s.worstMs, s.big / 1000, s.cpuT, s.memMb);
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, notif(live));
            } catch (Throwable ignored) { }
            long sleep = 1000 - (System.currentTimeMillis() - tick);
            if (sleep > 0) try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
        }
    }

    private String findLayer() {
        String list = Shell.sh("dumpsys SurfaceFlinger --list | grep '" + GameMode.GAME + "'");
        String best = null;
        for (String l : list.split("\n")) {
            String n = l.trim();
            if (n.isEmpty() || n.startsWith("Background")) continue;
            if (n.startsWith("SurfaceView")) return n;
            if (best == null) best = n;
        }
        return best;
    }

    Sample parse(String out) {
        Sample s = new Sample();
        String sec = "L";
        List<Long> frames = new ArrayList<>();
        int si = 0;
        List<Double> temps = new ArrayList<>();
        boolean first = true;
        for (String raw : out.split("\n")) {
            String l = raw.trim();
            if (l.startsWith("#")) { sec = l; continue; }
            if (l.isEmpty()) continue;
            try {
                switch (sec) {
                    case "L":
                        if (first) { first = false; break; } // период vsync
                        String[] t = l.split("\\s+");
                        if (t.length >= 3) {
                            long present = Long.parseLong(t[1]);
                            if (present > 0 && present < Long.MAX_VALUE / 2) frames.add(present);
                        }
                        break;
                    case "#S": if (si++ == 0) s.big = Integer.parseInt(l); else s.little = Integer.parseInt(l); break;
                    case "#T": temps.add(Double.parseDouble(l) / 1000.0); break;
                    case "#G": s.gpuT = Double.parseDouble(l) / 1000.0; break;
                    case "#B": s.batT = Double.parseDouble(l) / 10.0; break;
                    case "#M": s.memMb = Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024; break;
                }
            } catch (Exception ignored) { }
        }
        for (double d : temps) s.cpuT = Math.max(s.cpuT, d);
        Collections.sort(frames);
        long prev = lastFrameNs;
        int n = 0;
        double worst = 0;
        for (long f : frames) {
            if (f <= lastFrameNs) continue;
            n++;
            if (prev > 0) {
                double ms = (f - prev) / 1e6;
                if (ms < 1000) { worst = Math.max(worst, ms); synchronized (samples) { intervals.add(ms); } }
            }
            prev = f;
        }
        if (!frames.isEmpty()) lastFrameNs = Math.max(lastFrameNs, frames.get(frames.size() - 1));
        // первый замер содержит всю историю буфера — его не считаем
        s.fps = samples.isEmpty() ? 0 : Math.min(n, 120);
        s.worstMs = worst;
        return s;
    }

    private String buildReport() {
        List<Sample> ss;
        List<Double> iv;
        synchronized (samples) { ss = new ArrayList<>(samples); iv = new ArrayList<>(intervals); }
        StringBuilder r = new StringBuilder();
        r.append("=== SYSSCAN FPS REPORT | ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()))
                .append(" | ").append(startMode).append(" ===\n");
        r.append("layer: ").append(layer).append('\n');
        List<Integer> fps = new ArrayList<>();
        int throttled = 0, s55 = 0;
        double maxCpu = 0, maxGpu = 0, maxBat = 0;
        long minMem = Long.MAX_VALUE;
        for (Sample s : ss) {
            if (s.fps <= 0) continue; // игра свёрнута / меню без отрисовки
            fps.add(s.fps);
            if (s.fps >= 55) s55++;
            if (s.big > 0 && s.big < hwMaxBig) throttled++;
            maxCpu = Math.max(maxCpu, s.cpuT); maxGpu = Math.max(maxGpu, s.gpuT); maxBat = Math.max(maxBat, s.batT);
            if (s.memMb > 0) minMem = Math.min(minMem, s.memMb);
        }
        if (fps.isEmpty()) {
            r.append("Нет кадров игры. Монитор должен работать, пока идёт матч.\n");
        } else {
            Collections.sort(fps);
            double avg = 0;
            for (int f : fps) avg += f;
            avg /= fps.size();
            Collections.sort(iv);
            double p99 = iv.isEmpty() ? 0 : iv.get(Math.min(iv.size() - 1, (int) (iv.size() * 0.99)));
            r.append(String.format(Locale.US,
                    "секунд в игре: %d\nсредний FPS: %.1f\nмедиана FPS: %d\nмин FPS (1 с): %d\n1%% low FPS: %.1f (кадр %.1f мс)\n"
                            + "секунд с FPS>=55: %d%%\nбольшие ядра ниже %d МГц: %d%% времени\n"
                            + "макс CPU: %.1f°C, GPU: %.1f°C, батарея: %.1f°C\nмин свободной ОЗУ: %d МБ\n",
                    fps.size(), avg, fps.get(fps.size() / 2), fps.get(0), p99 > 0 ? 1000 / p99 : 0, p99,
                    100 * s55 / fps.size(), hwMaxBig / 1000, 100 * throttled / fps.size(),
                    maxCpu, maxGpu, maxBat, minMem == Long.MAX_VALUE ? 0 : minMem));
        }
        r.append("\nt,fps,worst_ms,big_mhz,little_mhz,cpu_c,gpu_c,bat_c,mem_mb\n");
        for (Sample s : ss) {
            r.append(String.format(Locale.US, "%d,%d,%.0f,%d,%d,%.0f,%.0f,%.1f,%d\n",
                    s.t, s.fps, s.worstMs, s.big / 1000, s.little / 1000, s.cpuT, s.gpuT, s.batT, s.memMb));
        }
        return r.toString();
    }
}
