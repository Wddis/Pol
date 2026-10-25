package com.example.vpnapp;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Журнал диагностики в памяти: попытки подключения, ответы Tor и мостов. */
final class DiagLog {

    private static final int MAX_LINES = 500;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    private DiagLog() {
    }

    static synchronized void add(String message) {
        if (LINES.size() >= MAX_LINES) LINES.removeFirst();
        LINES.addLast(TIME.format(new Date()) + "  " + message);
    }

    static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : LINES) sb.append(line).append('\n');
        return sb.toString();
    }

    static synchronized void clear() {
        LINES.clear();
    }
}
