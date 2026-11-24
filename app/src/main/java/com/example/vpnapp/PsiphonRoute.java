package com.example.vpnapp;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Параметры приоритетного подключения и отдельный кеш успешных серверов для каждой страны. */
final class PsiphonRoute {
    static final int PRIORITY_CANDIDATES = 8;
    static final List<String> PRIORITY_TRANSPORTS = Collections.unmodifiableList(Arrays.asList(
            "UNFRONTED-MEEK-OSSH", "UNFRONTED-MEEK-HTTPS-OSSH"));

    private PsiphonRoute() {}

    static String region(String country) {
        String code = country.toUpperCase(Locale.ROOT);
        return code.matches("[A-Z]{2}") ? code : "";
    }

    static boolean priority(String code) {
        return "DE".equals(code) || "NL".equals(code) || "SE".equals(code);
    }

    static String cacheDirectory(String country) {
        String code = region(country);
        return code.isEmpty() ? "data" : "data-" + code;
    }

    static boolean acceptsRegion(String selected, String reported) {
        return selected.isEmpty() || reported.isEmpty() || selected.equalsIgnoreCase(reported);
    }
}
