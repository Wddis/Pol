package com.example.vpnapp;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Журнал диагностики: попытки подключения, ответы Tor и мостов.
 * Дублируется в файл, чтобы пережить падение приложения.
 */
final class DiagLog {

    private static final int MAX_LINES = 500;
    private static final String FILE = "diag.log";
    private static final String PREFS = "oda_diag";
    private static final String KEY_LAST_EXIT = "last_exit";
    // Строк системного журнала (logcat) упавшего процесса, которые попадают в журнал
    private static final int CRASH_LINES = 40;
    private static final int CRASH_LINE_CHARS = 300;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    private static File file;

    private DiagLog() {
    }

    /** Вызывать при старте: загружает прошлый журнал и записывает причину прошлого завершения. */
    static synchronized void init(Context context) {
        if (file != null) return;
        Context app = context.getApplicationContext();
        file = new File(app.getFilesDir(), FILE);
        if (LINES.isEmpty() && file.exists()) {
            try {
                for (String line : new String(readAll(file), StandardCharsets.UTF_8).split("\n")) {
                    if (!line.isEmpty()) LINES.addLast(line);
                }
                while (LINES.size() > MAX_LINES) LINES.removeFirst();
            } catch (IOException ignored) {
                // нет журнала — не страшно
            }
        }
        logLastExit(app);
        installCrashHandler();
    }

    static synchronized void add(String message) {
        String line = TIME.format(new Date()) + "  " + message.replace('\n', ' ');
        if (LINES.size() >= MAX_LINES) LINES.removeFirst();
        LINES.addLast(line);
        append(line + "\n", true);
    }

    static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : LINES) sb.append(line).append('\n');
        return sb.toString();
    }

    static synchronized void clear() {
        LINES.clear();
        append("", false);
    }

    private static void append(String text, boolean append) {
        if (file == null) return;
        try (OutputStream out = new FileOutputStream(file, append)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // журнал не критичен
        }
    }

    private static byte[] readAll(File f) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return Files.readAllBytes(f.toPath());
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return buf;
        }
    }

    /** Падение Java-кода: записываем стек и передаём дальше системе. */
    private static void installCrashHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            add("ПАДЕНИЕ в потоке " + thread.getName() + ": " + sw.toString().replace('\n', '|'));
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Причина прошлого завершения процесса (Android 11+), в том числе падения в нативном коде. */
    private static void logLastExit(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 1);
        if (exits.isEmpty()) return;
        ApplicationExitInfo info = exits.get(0);
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getLong(KEY_LAST_EXIT, 0) == info.getTimestamp()) return;
        sp.edit().putLong(KEY_LAST_EXIT, info.getTimestamp()).apply();
        int reason = info.getReason();
        boolean abnormal = reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                || reason == ApplicationExitInfo.REASON_ANR
                || (reason == ApplicationExitInfo.REASON_EXIT_SELF && info.getStatus() != 0);
        if (!abnormal) return;
        add("Прошлый запуск завершился аварийно: " + reasonText(reason, info.getStatus())
                + " (причина " + reason + ", код " + info.getStatus() + ", " + info.getDescription()
                + ", процесс " + info.getPid() + ", "
                + new SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(new Date(info.getTimestamp())) + ")");
        // Подробности — в фоне: logcat занимает до секунды, а init вызывается и из интерфейса
        if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR) {
            new Thread(() -> logCrashDetails(info), "oda-crash-log").start();
        }
    }

    private static String reasonText(int reason, int status) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH:
                return "падение кода Java";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "падение нативного кода, сигнал " + status + signalName(status);
            case ApplicationExitInfo.REASON_ANR:
                return "приложение не отвечало (ANR)";
            default:
                return "завершилось само с кодом " + status;
        }
    }

    private static String signalName(int signal) {
        switch (signal) {
            case 4:
                return " (SIGILL)";
            case 6:
                return " (SIGABRT: abort — ART/JNI, FORTIFY, assert)";
            case 7:
                return " (SIGBUS)";
            case 8:
                return " (SIGFPE)";
            case 11:
                return " (SIGSEGV: обращение к неверной памяти)";
            default:
                return "";
        }
    }

    /**
     * Что напечатал упавший процесс перед смертью: строки logcat с уровнем W и выше по его pid
     * (своё приложение читает свой журнал без разрешений). При нативном падении здесь причина:
     * «JNI DETECTED ERROR …», «Abort message …», «Fatal signal 6 … in tid … (имя потока)».
     * Для ANR (Android 11+) — стек главного потока из отчёта системы.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private static void logCrashDetails(ApplicationExitInfo info) {
        List<String> lines = new java.util.ArrayList<>();
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-v", "time", "--pid=" + info.getPid(), "*:W")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("---------")) continue;  // «beginning of main» и т.п.
                    lines.add(clip(line));
                    if (lines.size() > CRASH_LINES) lines.remove(0);
                }
            }
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy();
        } catch (IOException | InterruptedException | RuntimeException e) {
            add("Журнал Android упавшего процесса недоступен: " + e);
        }
        if (lines.isEmpty()) {
            add("Журнал Android упавшего процесса пуст (уже перезаписан или закрыт производителем)");
        } else {
            add("Журнал Android упавшего процесса " + info.getPid() + " (W и выше, последние " + lines.size() + "):");
            for (String l : lines) add("  " + l);
        }
        if (info.getReason() == ApplicationExitInfo.REASON_ANR) logAnrTrace(info);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static void logAnrTrace(ApplicationExitInfo info) {
        try (InputStream in = info.getTraceInputStream()) {
            if (in == null) return;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            boolean main = false;
            int n = 0;
            while ((line = r.readLine()) != null && n < CRASH_LINES) {
                if (line.startsWith("\"main\"")) main = true;
                if (!main) continue;
                if (line.trim().isEmpty()) break;
                add("  ANR " + clip(line.trim()));
                n++;
            }
        } catch (IOException | RuntimeException e) {
            add("Отчёт ANR недоступен: " + e);
        }
    }

    private static String clip(String s) {
        return s.length() > CRASH_LINE_CHARS ? s.substring(0, CRASH_LINE_CHARS) + "…" : s;
    }
}
