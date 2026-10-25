package com.example.vpnapp;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Разбор мостов, вставленных пользователем. Понимает строки с «Bridge » и без,
 * а также текст, в котором при копировании пропали переводы строк.
 */
final class BridgeParser {

    /** Итог разбора: корректные строки и число отброшенных. */
    static final class Result {
        final List<String> valid;
        final int invalid;
        final Set<String> transports;

        Result(List<String> valid, int invalid, Set<String> transports) {
            this.valid = valid;
            this.invalid = invalid;
            this.transports = transports;
        }
    }

    private static final Set<String> TRANSPORTS = new HashSet<>(Arrays.asList(
            "obfs4", "webtunnel", "snowflake", "meek_lite", "meek", "dnstt"));
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}$");
    private static final Pattern IPV6 = Pattern.compile("^\\[[0-9a-fA-F:.]+]:\\d{1,5}$");
    private static final Pattern FINGERPRINT = Pattern.compile("^[0-9A-Fa-f]{40}$");

    private BridgeParser() {
    }

    static Result parse(@Nullable String text) {
        List<List<String>> lines = new ArrayList<>();
        List<String> current = null;
        boolean currentHasAddress = false;
        for (String token : tokens(text)) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.equals("bridge")) continue;
            boolean transport = TRANSPORTS.contains(lower);
            boolean address = isAddress(token);
            // Новая строка: имя транспорта или второй адрес подряд (обычный мост)
            if (transport || current == null || (address && currentHasAddress)) {
                current = new ArrayList<>();
                lines.add(current);
                currentHasAddress = false;
                if (transport) {
                    current.add(lower.equals("meek") ? "meek_lite" : lower);
                    continue;
                }
            }
            if (address) currentHasAddress = true;
            // Кусок без «=» после параметров — перенесённый хвост длинного значения (cert=…)
            boolean tail = current.size() >= 3 && !address && !token.contains("=")
                    && !FINGERPRINT.matcher(token).matches()
                    && current.get(current.size() - 1).contains("=");
            if (tail) {
                int last = current.size() - 1;
                current.set(last, current.get(last) + token);
                continue;
            }
            current.add(token);
        }

        List<String> valid = new ArrayList<>();
        Set<String> transports = new HashSet<>();
        int invalid = 0;
        for (List<String> parts : lines) {
            String line = TextUtils.join(" ", parts);
            if (isValid(parts)) {
                if (!valid.contains(line)) valid.add(line);
                String t = BridgeSet.transportOf(line);
                transports.add(t != null ? t : "vanilla");
            } else {
                invalid++;
            }
        }
        return new Result(valid, invalid, transports);
    }

    /** Слова текста без комментариев, кавычек и обратных апострофов. */
    private static List<String> tokens(@Nullable String text) {
        List<String> result = new ArrayList<>();
        if (text == null) return result;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.replace(' ', ' ').replace("`", "").trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            for (String t : line.split("\\s+")) {
                if (!t.isEmpty()) result.add(t);
            }
        }
        return result;
    }

    private static boolean isValid(List<String> parts) {
        if (parts.size() < 2) return false;
        String first = parts.get(0);
        String joined = TextUtils.join(" ", parts);
        if (isAddress(first)) {
            // Обычный мост: адрес и отпечаток
            return FINGERPRINT.matcher(parts.get(1)).matches();
        }
        if (!isAddress(parts.get(1))) return false;
        switch (first) {
            case "obfs4":
                return joined.contains("cert=");
            case "webtunnel":
            case "meek_lite":
                return joined.contains("url=");
            case "dnstt":
                return joined.contains("pubkey=");
            case "snowflake":
                return true;
            default:
                return false;
        }
    }

    private static boolean isAddress(String token) {
        return IPV4.matcher(token).matches() || IPV6.matcher(token).matches();
    }
}
