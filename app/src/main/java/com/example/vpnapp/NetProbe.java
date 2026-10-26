package com.example.vpnapp;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Проверка сети напрямую, мимо VPN (само приложение исключено из VPN): открываются ли
 * зарубежные и российские сайты. Если российские открываются, а зарубежные нет — у оператора,
 * скорее всего, «белые списки», и обойти их не сможет ни Psiphon, ни Tor.
 * Вызывать из фонового потока.
 */
final class NetProbe {

    enum Result {
        /** Зарубежные сайты открываются. */
        OK,
        /** Российские открываются, зарубежные нет: похоже на «белые списки». */
        WHITELIST,
        /** Не открывается ничего. */
        OFFLINE
    }

    /** Насколько хорошо обход DPI работает через свой SOCKS. */
    enum Bypass {
        /** Открылся заблокированный сайт (соцсеть): обход реально бьёт DPI. */
        UNBLOCKS,
        /** Открываются только обычные сайты, заблокированные — нет. */
        BASIC,
        /** Не открывается ничего. */
        NONE
    }

    private static final String[] FOREIGN = {
            "https://www.google.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://www.wikipedia.org/",
    };
    private static final String[] LOCAL = {
            "https://ya.ru/",
            "https://vk.com/",
            "https://www.gosuslugi.ru/",
    };
    // Сайты, заблокированные в РФ по DPI/имени (не по IP): напрямую не открываются,
    // а через рабочий обход DPI отвечают. По ним и проверяем, что стратегия реально бьёт фильтр.
    private static final String[] CANARY = {
            "https://www.instagram.com/",
            "https://rutracker.org/",
            "https://x.com/",
    };
    private static final int TIMEOUT_MS = 7_000;

    private NetProbe() {
    }

    static Result run() {
        ExecutorService pool = Executors.newFixedThreadPool(FOREIGN.length + LOCAL.length);
        try {
            List<Future<Boolean>> foreign = submit(pool, FOREIGN);
            List<Future<Boolean>> local = submit(pool, LOCAL);
            int foreignOk = count(foreign);
            int localOk = count(local);
            DiagLog.add("Сеть напрямую: зарубежные сайты " + foreignOk + "/" + FOREIGN.length
                    + ", российские " + localOk + "/" + LOCAL.length);
            if (foreignOk > 0) return Result.OK;
            return localOk > 0 ? Result.WHITELIST : Result.OFFLINE;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Качество обхода DPI через локальный SOCKS: открылся ли заблокированный сайт (UNBLOCKS),
     * только обычный (BASIC) или ничего (NONE). Проверка идёт мимо VPN, через сам SOCKS ByeDPI.
     */
    static Bypass bypassQuality(int socksPort) {
        java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                new java.net.InetSocketAddress("127.0.0.1", socksPort));
        if (anyReachableVia(CANARY, proxy)) return Bypass.UNBLOCKS;
        if (anyReachableVia(FOREIGN, proxy)) return Bypass.BASIC;
        return Bypass.NONE;
    }

    private static boolean anyReachableVia(String[] urls, java.net.Proxy proxy) {
        ExecutorService pool = Executors.newFixedThreadPool(urls.length);
        try {
            List<Future<Boolean>> fs = new ArrayList<>();
            for (String url : urls) fs.add(pool.submit(() -> reachableVia(url, proxy)));
            return count(fs) > 0;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean reachableVia(String url, java.net.Proxy proxy) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection(proxy);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            return c.getResponseCode() > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static List<Future<Boolean>> submit(ExecutorService pool, String[] urls) {
        List<Future<Boolean>> result = new ArrayList<>();
        for (String url : urls) result.add(pool.submit(() -> reachable(url)));
        return result;
    }

    private static int count(List<Future<Boolean>> futures) {
        int ok = 0;
        for (Future<Boolean> f : futures) {
            try {
                if (f.get(TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS)) ok++;
            } catch (Exception ignored) {
                // не ответил вовремя — недоступен
            }
        }
        return ok;
    }

    /** Сайт ответил хоть каким-то HTTP-кодом (TLS прошёл). */
    private static boolean reachable(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            return c.getResponseCode() > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
