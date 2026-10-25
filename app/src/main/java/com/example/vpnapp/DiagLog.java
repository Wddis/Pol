package com.example.vpnapp;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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

/**
 * Журнал диагностики: попытки подключения, ответы Tor и мостов.
 * Дублируется в файл, чтобы пережить падение приложения.
 */
final class DiagLog {

    private static final int MAX_LINES = 500;
    private static final String FILE = "diag.log";
    private static final String PREFS = "oda_diag";
    private static final String KEY_LAST_EXIT = "last_exit";
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
        add("Прошлый запуск завершился аварийно: причина " + reason + ", код " + info.getStatus()
                + ", " + info.getDescription() + " ("
                + new SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(new Date(info.getTimestamp())) + ")");
    }
}
