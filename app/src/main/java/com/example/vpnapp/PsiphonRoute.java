package com.example.vpnapp;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Параметры приоритетного подключения и отдельный кеш успешных серверов для каждой страны. */
final class PsiphonRoute {
    private static final String QUIC = "QUIC-OSSH";
    private static final List<String> MEEK = Collections.unmodifiableList(Arrays.asList(
            "UNFRONTED-MEEK-OSSH", "UNFRONTED-MEEK-HTTPS-OSSH"));
    private static final List<String> LEARNABLE = Arrays.asList(QUIC,
            "SSH", "OSSH", "TLS-OSSH", "SHADOWSOCKS-OSSH",
            "UNFRONTED-MEEK-OSSH", "UNFRONTED-MEEK-HTTPS-OSSH",
            "UNFRONTED-MEEK-SESSION-TICKET-OSSH", "FRONTED-MEEK-OSSH", "FRONTED-MEEK-HTTP-OSSH");

    private PsiphonRoute() {}

    static String region(String country) {
        String code = country.toUpperCase(Locale.ROOT);
        return code.matches("[A-Z]{2}") ? code : "";
    }

    static boolean priority(String code) {
        return "DE".equals(code) || "NL".equals(code) || "SE".equals(code);
    }

    static List<String> initialTransports(String country, String network, String confirmed) {
        if (!priority(country)) return Collections.emptyList();
        if (learnable(confirmed)) return Collections.singletonList(confirmed);
        if ("SE".equals(country)) return MEEK;
        if ("NL".equals(country) && ("MOBILE".equals(network) || network.startsWith("MOBILE-"))) {
            return Collections.singletonList(QUIC);
        }
        return Collections.emptyList();
    }

    static int initialCandidateCount(List<String> transports) {
        if (transports.isEmpty()) return 0;
        return MEEK.equals(transports) ? 8 : 2;
    }

    static boolean learnable(String reported) {
        return LEARNABLE.contains(reported);
    }

    static String historyKey(String country, String network) {
        return region(country) + ":" + network;
    }

    static String cacheDirectory(String country) {
        String code = region(country);
        return code.isEmpty() ? "data" : "data-" + code;
    }

    static boolean acceptsRegion(String selected, String reported) {
        return selected.isEmpty() || reported.isEmpty() || selected.equalsIgnoreCase(reported);
    }
}
