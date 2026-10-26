package com.example.vpnapp;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Обход DPI без сервера (ByeDPI, libbyedpi.so, собирается scripts/build-byedpi.sh).
 * Ядро — локальный SOCKS-прокси: соединение идёт напрямую к сайту, но форма первых пакетов
 * меняется (режется TLS ClientHello и т.п.), чтобы ТСПУ не узнал протокол и не оборвал связь.
 * Заграничного сервера нет — блокировать по адресу нечего. Но работает только против блокировок
 * по DPI/имени сайта: против блокировки по IP и «белых списков» не поможет (там нужен туннель).
 * <p>
 * Один контроллер — один процесс ByeDPI на своём порту ({@link #BASE_PORT} + номер слота):
 * сервис проверяет несколько стратегий одновременно (см. BypassSearch). Запускается отдельным
 * процессом (как Psiphon). Методы и колбэки — в потоке handler.
 */
final class BypassController {

    interface Listener {
        /** SOCKS-порт поднят; дальше стратегию проверяет сам сервис (NetProbe). */
        void onBypassListening(int slot, int socksPort, BypassProfile profile);

        /** Процесс не запустился или завершился (в том числе уже после подключения). */
        void onBypassError(int slot, BypassProfile profile, String reason);
    }

    static final int BASE_PORT = 10809;  // локальные порты SOCKS ByeDPI: 10809, 10810, …

    private static final String TAG = "BypassController";
    private static final String LIB = "libbyedpi.so";
    private static final String DIR = "byedpi";
    private static final long LISTEN_TIMEOUT_MS = 6_000;
    private static final long STALE_WAIT_MS = 3_000;
    private static final int LOG_TAIL_CHARS = 4 * 1024;
    private static final Object LAUNCH_LOCK = new Object();

    private final Context context;
    private final Handler handler;
    private final int slot;
    private final int port;

    // Поля ниже меняются только в потоке handler
    @Nullable private Listener listener;
    @Nullable private BypassProfile profile;
    @Nullable private volatile ByeDpiStats stats;
    private int session;
    @Nullable private volatile Process process;
    private volatile int liveSession;
    // Последние строки вывода ByeDPI (ошибки ядра): в памяти, чтобы файл журнала не рос без конца
    private final StringBuilder output = new StringBuilder();
    // Вывод, ещё не попавший в журнал (его забирает сторож подключения, см. drainOutput)
    private final StringBuilder fresh = new StringBuilder();

    BypassController(Context context, Handler handler, int slot) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.slot = slot;
        this.port = BASE_PORT + slot;
    }

    /** Есть ли ByeDPI в APK (его нет в локальной сборке без NDK). */
    static boolean isAvailable(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, LIB).isFile();
    }

    int socksPort() {
        return port;
    }

    /** Стратегия, с которой запущен процесс (или null). */
    @Nullable
    BypassProfile profile() {
        return profile;
    }

    /**
     * Запуск ByeDPI с указанной стратегией.
     *
     * @param helper порт запасного пути (HelperRelay → Psiphon) или 0 — см. BypassProfile.route
     */
    void start(BypassProfile p, int helper, Listener l) {
        stop();
        listener = l;
        profile = p;
        int s = ++session;
        liveSession = s;
        synchronized (output) {
            output.setLength(0);
            fresh.setLength(0);
        }
        BypassProfile.Route route = BypassProfile.route(p, helper);
        stats = new ByeDpiStats(route.groups);
        File exe = new File(context.getApplicationInfo().nativeLibraryDir, LIB);
        DiagLog.add("Обход DPI: запуск «" + p.label + "» (порт " + port + "): " + p.argsLine()
                + (route.groups.size() > 1 ? "; группы: " + route.describe() : ""));
        new Thread(() -> run(s, exe, route.args), "oda-byedpi-" + slot).start();
    }

    /** Остановка: процесс ByeDPI завершается. */
    void stop() {
        listener = null;
        profile = null;
        session++;
        liveSession = 0;
        Process p = process;
        process = null;
        if (p != null) p.destroy();
    }

    /** Последние строки вывода ByeDPI (для журнала). */
    String outputTail() {
        synchronized (output) {
            return output.toString().trim();
        }
    }

    /** Сводка по соединениям (ByeDpiStats) или null, если процесс не запущен. */
    @Nullable
    ByeDpiStats stats() {
        return stats;
    }

    /** Ошибки ядра ByeDPI с прошлого вызова (например «pool is full»); отладочный вывод сюда не идёт. */
    String drainOutput() {
        synchronized (output) {
            String s = fresh.toString();
            fresh.setLength(0);
            return s;
        }
    }

    private void run(int s, File exe, List<String> strategy) {
        Process proc;
        synchronized (LAUNCH_LOCK) {
            if (s != liveSession) return;
            try {
                if (!exe.isFile()) throw new IOException("нет " + LIB + " в APK");
                killStale(port);
                File dir = new File(context.getFilesDir(), DIR);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                List<String> cmd = new ArrayList<>();
                cmd.add(exe.getAbsolutePath());
                cmd.addAll(BypassProfile.processArgs(port));
                cmd.addAll(strategy);
                proc = new ProcessBuilder(cmd)
                        .directory(dir)
                        .redirectErrorStream(true)
                        .start();
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "start", e);
                String reason = "не запустился: " + e.getMessage();
                handler.post(() -> fail(s, reason));
                return;
            }
        }
        handler.post(() -> {
            if (s == session) {
                process = proc;
            } else {
                proc.destroy();
            }
        });
        Thread reader = new Thread(() -> readOutput(s, proc), "oda-byedpi-out-" + slot);
        reader.start();
        // Ждём, пока поднимется локальный SOCKS-порт
        boolean up = awaitListening(s, proc);
        if (!proc.isAlive()) return;  // о завершении сообщит readOutput
        if (!up) {
            handler.post(() -> fail(s, "порт не открылся"));
            return;
        }
        handler.post(() -> onListening(s));
    }

    /**
     * Читает вывод процесса до его завершения (иначе он встанет на заполненном канале — ByeDPI
     * пишет подробно, -x 1) и сообщает, если процесс завершился сам — в том числе уже после
     * подключения. Отладочные строки разбирает ByeDpiStats, ошибки идут в журнал.
     */
    private void readOutput(int s, Process proc) {
        ByeDpiStats st = stats;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (st != null && st.feed(line)) continue;
                synchronized (output) {
                    output.append(line).append('\n');
                    if (output.length() > LOG_TAIL_CHARS) {
                        output.delete(0, output.length() - LOG_TAIL_CHARS);
                    }
                    fresh.append(line).append('\n');
                    if (fresh.length() > LOG_TAIL_CHARS) {
                        fresh.delete(0, fresh.length() - LOG_TAIL_CHARS);
                    }
                }
            }
        } catch (IOException ignored) {
            // процесс остановлен
        }
        if (s != liveSession) return;  // остановили сами
        int code;
        try {
            code = proc.waitFor();
        } catch (InterruptedException e) {
            return;
        }
        String tail = outputTail();
        String reason = "процесс завершился (код " + code + ")"
                + (tail.isEmpty() ? "" : ": " + lastLines(tail, 3));
        handler.post(() -> fail(s, reason));
    }

    /** Ждёт, пока ByeDPI начнёт принимать подключения на локальном порту. */
    private boolean awaitListening(int s, Process p) {
        long deadline = SystemClock.elapsedRealtime() + LISTEN_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline && s == liveSession && p.isAlive()) {
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return true;
            } catch (IOException e) {
                SystemClock.sleep(150);
            }
        }
        return false;
    }

    private void onListening(int s) {
        Listener l = listener;
        BypassProfile p = profile;
        if (s != session || l == null || p == null) return;
        l.onBypassListening(slot, port, p);
    }

    private void fail(int s, String reason) {
        Listener l = listener;
        BypassProfile p = profile;
        if (s != session || l == null || p == null) return;
        DiagLog.add("Обход DPI «" + p.label + "»: " + reason);
        stop();
        l.onBypassError(slot, p, reason);
    }

    private static String lastLines(String text, int n) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - n); i < lines.length; i++) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(lines[i].trim());
        }
        return sb.toString();
    }

    /** Завершить процессы ByeDPI на этом порту, оставшиеся от прошлого запуска (держат порт). */
    private static void killStale(int port) {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        int self = android.os.Process.myPid();
        // В /proc/<pid>/cmdline аргументы разделены нулевыми байтами
        String portArg = "\0-p\0" + port + "\0";
        List<File> killed = new ArrayList<>();
        for (File f : procs) {
            String name = f.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == self) continue;
            String cmdline = readCmdline(new File(f, "cmdline"));
            if (cmdline == null || !cmdline.contains(LIB) || !cmdline.contains(portArg)) continue;
            DiagLog.add("Обход DPI: завершаю прошлый процесс " + pid + " (порт " + port + ")");
            android.os.Process.killProcess(pid);
            killed.add(f);
        }
        long deadline = SystemClock.elapsedRealtime() + STALE_WAIT_MS;
        for (File f : killed) {
            while (f.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        }
    }

    @Nullable
    private static String readCmdline(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[512];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
