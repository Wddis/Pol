package com.example.vpnapp;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    /** Объединение нескольких наборов в один (Tor пробует все мосты параллельно). */
    static BridgeSet combine(String label, List<BridgeSet> sets) {
        List<String> lines = new ArrayList<>();
        for (BridgeSet set : sets) {
            for (String line : set.lines) {
                if (!lines.contains(line)) lines.add(line);
            }
        }
        return new BridgeSet(label, lines);
    }

    /** Только строки указанного транспорта (null — если таких нет). */
    @Nullable
    BridgeSet only(String transport) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (transport.equals(transportOf(line))) result.add(line);
        }
        return result.isEmpty() ? null : new BridgeSet(label, result);
    }

    /** Все строки, кроме указанного транспорта. */
    BridgeSet without(String transport) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (!transport.equals(transportOf(line))) result.add(line);
        }
        return new BridgeSet(label, result);
    }

    /** Строки моста с этим отпечатком: так запоминаем именно сработавший мост. */
    @Nullable
    BridgeSet narrowTo(@Nullable String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) return null;
        String fp = fingerprint.toUpperCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (line.toUpperCase(Locale.ROOT).contains(fp)) result.add(line);
        }
        if (result.isEmpty()) return null;
        return new BridgeSet(labelFor(transportOf(result.get(0))), result);
    }

    /** Название транспорта для экрана. */
    static String labelFor(@Nullable String transport) {
        if (transport == null) return "Bridge";
        switch (transport) {
            case "snowflake":
                return "Snowflake";
            case "webtunnel":
                return "WebTunnel";
            case "meek_lite":
                return "meek";
            case "dnstt":
                return "DNSTT";
            default:
                return transport;
        }
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
