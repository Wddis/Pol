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
