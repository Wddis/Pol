package com.example.vpnapp;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Обход DPI без сервера (ByeDPI, libbyedpi.so, собирается scripts/build-byedpi.sh).
 * Ядро — локальный SOCKS-прокси: соединение идёт напрямую к сайту, но форма первых пакетов
 * меняется (режется TLS ClientHello и т.п.), чтобы ТСПУ не узнал протокол и не оборвал связь.
 * Заграничного сервера нет — блокировать по адресу нечего. Но работает только против блокировок
 * по DPI/имени сайта: против блокировки по IP и «белых списков» не поможет (там нужен туннель).
 * <p>
 * Запускается отдельным процессом (как Psiphon): так проще и надёжнее, чем в своём процессе.
 * Методы и колбэки — в потоке handler.
 */
final class BypassController {

    interface Listener {
        /** SOCKS-порт поднят; дальше подключение проверяет сам сервис пробой зарубежного сайта. */
        void onBypassListening(int socksPort, String profileLabel);

        /** Процесс не запустился или упал. */
        void onBypassError(String reason);
    }

    /** Стратегия обхода: название для журнала и аргументы ByeDPI. */
    static final class Profile {
        final String label;
        final String[] args;

        Profile(String label, String... args) {
            this.label = label;
            this.args = args;
        }
    }

    // Набор стратегий для перебора. Разные провайдеры ловятся разными способами, поэтому
    // пробуем по очереди и проверяем каждую реальным запросом к зарубежному сайту.
    // Формат аргументов — см. README ByeDPI (-s split, -d disorder, -f fake, -o oob, --tlsrec, --auto).
    static final List<Profile> PROFILES = Arrays.asList(
            // fake+disorder с QUIC-fake — часто нужно для Meta (Instagram); сильные идут первыми
            new Profile("fake+disorder", "-f", "-1", "-t", "8", "-d", "1+s", "--udp-fake", "2"),
            new Profile("disorder SNI", "-d", "1+s"),
            new Profile("split+tlsrec", "-s", "1+s", "--tlsrec", "1+s"),
            new Profile("split SNI", "-s", "1+s"),
            new Profile("oob SNI", "-o", "1+s"),
            new Profile("fake+split+disorder", "-f", "-1", "-t", "8", "-s", "1+s",
                    "-d", "1+s", "--tlsrec", "1+s", "--udp-fake", "2"));

    private static final String TAG = "BypassController";
    private static final String LIB = "libbyedpi.so";
    private static final String DIR = "byedpi";
    private static final String LOG_FILE = "byedpi.log";
    private static final int PORT = 10809;  // локальный порт SOCKS ByeDPI
    private static final long LISTEN_TIMEOUT_MS = 6_000;
    private static final long STALE_WAIT_MS = 3_000;
    private static final long LOG_LIMIT_BYTES = 256 * 1024;
    private static final int LOG_TAIL_BYTES = 4 * 1024;
    private static final Object LAUNCH_LOCK = new Object();

    private final Context context;
    private final Handler handler;

    // Поля ниже меняются только в потоке handler
    @Nullable private Listener listener;
    private int session;
    @Nullable private volatile Process process;
    private volatile int liveSession;

    BypassController(Context context, Handler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
    }

    /** Есть ли ByeDPI в APK (его нет в локальной сборке без NDK). */
    static boolean isAvailable(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, LIB).isFile();
    }

    int socksPort() {
        return PORT;
    }

    /** Разобрать свою строку аргументов ByeDPI в профиль или null, если пусто. */
    @Nullable
    static Profile customProfile(@Nullable String args) {
        if (args == null) return null;
        List<String> parts = new ArrayList<>();
        for (String p : args.trim().split("\\s+")) {
            if (!p.isEmpty()) parts.add(p);
        }
        if (parts.isEmpty()) return null;
        return new Profile("свои настройки", parts.toArray(new String[0]));
    }

    /** Запуск ByeDPI с указанной стратегией. */
    void start(Profile profile, Listener l) {
        stop();
        listener = l;
        int s = ++session;
        liveSession = s;
        File exe = new File(context.getApplicationInfo().nativeLibraryDir, LIB);
        DiagLog.add("Обход DPI: запуск, стратегия «" + profile.label + "» "
                + join(profile.args));
        new Thread(() -> run(s, exe, profile), "oda-byedpi").start();
    }

    /** Остановка: процесс ByeDPI завершается. */
    void stop() {
        listener = null;
        session++;
        liveSession = 0;
        Process p = process;
        process = null;
        if (p != null) {
            DiagLog.add("Обход DPI: остановка");
            p.destroy();
        }
    }

    private void run(int s, File exe, Profile profile) {
        Process p;
        synchronized (LAUNCH_LOCK) {
            if (s != liveSession) return;
            try {
                if (!exe.isFile()) throw new IOException("нет " + LIB + " в APK");
                killStale();
                File dir = new File(context.getFilesDir(), DIR);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File log = new File(dir, LOG_FILE);
                if (log.length() > LOG_LIMIT_BYTES) {
                    //noinspection ResultOfMethodCallIgnored
                    log.delete();
                }
                List<String> cmd = new ArrayList<>();
                cmd.add(exe.getAbsolutePath());
                cmd.add("-i");
                cmd.add("127.0.0.1");
                cmd.add("-p");
                cmd.add(Integer.toString(PORT));
                cmd.addAll(Arrays.asList(profile.args));
                p = new ProcessBuilder(cmd)
                        .directory(dir)
                        .redirectErrorStream(true)
                        .redirectOutput(log)
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
                process = p;
            } else {
                p.destroy();
            }
        });
        // Ждём, пока поднимется локальный SOCKS-порт
        boolean up = awaitListening(s, p);
        if (!p.isAlive()) {
            handler.post(() -> fail(s, "процесс завершился: " + logTail(context)));
            return;
        }
        if (!up) {
            handler.post(() -> fail(s, "порт не открылся"));
            return;
        }
        handler.post(() -> onListening(s, profile.label));
    }

    /** Ждёт, пока ByeDPI начнёт принимать подключения на локальном порту. */
    private boolean awaitListening(int s, Process p) {
        long deadline = SystemClock.elapsedRealtime() + LISTEN_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline && s == liveSession && p.isAlive()) {
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
                return true;
            } catch (IOException e) {
                SystemClock.sleep(150);
            }
        }
        return false;
    }

    private void onListening(int s, String label) {
        Listener l = listener;
        if (s != session || l == null) return;
        DiagLog.add("Обход DPI: SOCKS порт " + PORT + " готов (" + label + ")");
        l.onBypassListening(PORT, label);
    }

    private void fail(int s, String reason) {
        Listener l = listener;
        if (s != session || l == null) return;
        DiagLog.add("Обход DPI: " + reason);
        stop();
        l.onBypassError(reason);
    }

    /** Хвост журнала ByeDPI для экрана «Журнал». */
    static String logTail(Context context) {
        File log = new File(new File(context.getFilesDir(), DIR), LOG_FILE);
        if (!log.exists()) return "";
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(log, "r")) {
            long start = Math.max(0, f.length() - LOG_TAIL_BYTES);
            byte[] buf = new byte[(int) (f.length() - start)];
            f.seek(start);
            f.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Завершить процессы ByeDPI, оставшиеся от прошлого запуска (держат порт). */
    private static void killStale() {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        int self = android.os.Process.myPid();
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
            if (cmdline == null || !cmdline.contains(LIB)) continue;
            DiagLog.add("Обход DPI: завершаю прошлый процесс " + pid);
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

    private static String join(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(a[i]);
        }
        return sb.toString();
    }
}
