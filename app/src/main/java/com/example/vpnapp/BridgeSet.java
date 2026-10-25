package com.example.vpnapp;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Один способ подключения к Tor: название для экрана и строки мостов.
 * Пустой список строк — прямое подключение без мостов.
 */
final class BridgeSet {

    private static final String SEPARATOR = "\n";

    final String label;
    final List<String> lines;

    BridgeSet(String label, List<String> lines) {
        this.label = label;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
    }

    static BridgeSet direct(String label) {
        return new BridgeSet(label, Collections.emptyList());
    }

    boolean isDirect() {
        return lines.isEmpty();
    }

    /** Транспорты (obfs4, snowflake, …), которые нужно запустить через IPtProxy. */
    Set<String> transports() {
        Set<String> result = new LinkedHashSet<>();
        for (String line : lines) {
            String t = transportOf(line);
            if (t != null) result.add(t);
        }
        return result;
    }

    /** Первая строка указанного транспорта (нужна для настроек snowflake). */
    @Nullable
    String firstLine(String transport) {
        for (String line : lines) {
            if (transport.equals(transportOf(line))) return line;
        }
        return null;
    }

    /** Сериализация для хранения в настройках: первая строка — название. */
    String serialize() {
        StringBuilder sb = new StringBuilder(label);
        for (String line : lines) sb.append(SEPARATOR).append(line);
        return sb.toString();
    }

    @Nullable
    static BridgeSet deserialize(@Nullable String data) {
        if (data == null || data.isEmpty()) return null;
        String[] parts = data.split(SEPARATOR);
        List<String> lines = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].trim().isEmpty()) lines.add(parts[i].trim());
        }
        return new BridgeSet(parts[0], lines);
    }

    /** Разбор строк, вставленных пользователем: убираем «Bridge », пустые и комментарии. */
    static List<String> parseUserLines(@Nullable String text) {
        List<String> result = new ArrayList<>();
        if (text == null) return result;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.regionMatches(true, 0, "Bridge ", 0, 7)) line = line.substring(7).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            result.add(line);
        }
        return result;
    }

    /** Транспорт строки моста или null для обычного моста «IP:порт отпечаток». */
    @Nullable
    static String transportOf(String line) {
        String first = line.trim().split("\\s+")[0];
        if (first.isEmpty() || Character.isDigit(first.charAt(0)) || first.startsWith("[")) {
            return null;
        }
        return first;
    }

    /** Значение параметра key=value из строки моста. */
    @Nullable
    static String arg(String line, String key) {
        for (String token : line.trim().split("\\s+")) {
            if (token.startsWith(key + "=")) return token.substring(key.length() + 1);
        }
        return null;
    }

    boolean sameLines(BridgeSet other) {
        return lines.equals(other.lines);
    }
}
