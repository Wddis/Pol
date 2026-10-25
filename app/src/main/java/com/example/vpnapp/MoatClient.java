package com.example.vpnapp;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import IPtProxy.IPtProxy;

/**
 * Moat (bridges.torproject.org): Tor Project по стране подсказывает, какие мосты
 * там работают, и выдаёт свежие. Запрос идёт через meek (доменный фронтинг),
 * поэтому доходит даже при блокировке Tor. Вызывать только из фонового потока.
 */
final class MoatClient {

    /** Ответ Moat: список способов подключения по приоритету. */
    static final class Result {
        final List<BridgeSet> sets;
        /** true — Moat считает, что в этой стране Tor работает без мостов. */
        final boolean directOk;

        Result(List<BridgeSet> sets, boolean directOk) {
            this.sets = sets;
            this.directOk = directOk;
        }
    }

    /** Канал до Moat: транспорт, его параметры и адрес службы. */
    private static final class Tunnel {
        final String name;
        final String transport;
        final String args;
        final String url;
        final boolean needsCountry;
        final int timeoutMs;

        Tunnel(String name, String transport, String args, String url, boolean needsCountry, int timeoutMs) {
            this.name = name;
            this.transport = transport;
            this.args = args;
            this.url = url;
            this.needsCountry = needsCountry;
            this.timeoutMs = timeoutMs;
        }
    }

    // Tor Project через meek (CDN77), как в Tor Browser и Orbot
    private static final Tunnel TOR_PROJECT = new Tunnel("Tor Project", IPtProxy.MeekLite,
            "url=https://1723079976.rsc.cdn77.org;front=www.phpmyadmin.net",
            "https://bridges.torproject.org/moat/circumvention/settings", false, 20_000);
    // Guardian Project через DNSTT (DNS Google): страну по IP он не видит, нужна подсказка
    private static final Tunnel GUARDIAN_PROJECT = new Tunnel("Guardian Project", IPtProxy.Dnstt,
            "doh=https://dns.google/dns-query;pubkey=c07ae9dd7b86ded6121c3db173de048bfd4f41de38dd430e3dfaf83ec8f36a06;domain=t1.bypasscensorship.org",
            "https://tns1.bypasscensorship.org/moat/circumvention/settings", true, 45_000);
    private static final int MAX_BODY = 256 * 1024;
    private static final int ERROR_NEEDS_OTHER = 404;
    private static final int ERROR_NO_COUNTRY = 406;

    private MoatClient() {
    }

    /**
     * Запрашивает оба Moat параллельно (Tor Project и Guardian Project) и ждёт не дольше
     * deadlineMs. Ответ Tor Project в приоритете. countryHint — код страны сети (например,
     * "ru") на случай, если Moat не определит её сам.
     */
    static Result fetch(Context context, @Nullable String countryHint, long deadlineMs) throws Exception {
        long until = SystemClock.elapsedRealtime() + deadlineMs;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Result> tp = pool.submit(() -> query(new PtController(context), TOR_PROJECT, countryHint));
            Future<Result> gp = countryHint != null
                    ? pool.submit(() -> query(new PtController(context), GUARDIAN_PROJECT, countryHint))
                    : null;
            Result first = await(tp, TOR_PROJECT, until);
            if (useful(first)) return first;
            Result second = gp != null ? await(gp, GUARDIAN_PROJECT, until) : null;
            if (useful(second)) return second;
            if (first != null) return first;
            if (second != null) return second;
            throw new IOException("Moat unavailable");
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean useful(@Nullable Result r) {
        return r != null && (!r.sets.isEmpty() || r.directOk);
    }

    @Nullable
    private static Result await(Future<Result> future, Tunnel tunnel, long until) {
        try {
            long left = Math.max(1, until - SystemClock.elapsedRealtime());
            return future.get(left, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            DiagLog.add("Moat " + tunnel.name + ": нет ответа вовремя");
        } catch (ExecutionException e) {
            DiagLog.add("Moat " + tunnel.name + " недоступен: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        future.cancel(true);
        return null;
    }

    private static Result query(PtController pt, Tunnel tunnel, @Nullable String countryHint) throws Exception {
        long port = pt.startSingle(tunnel.transport);
        // Параметры транспорта передаются как логин SOCKS5; запросы к разным Moat
        // идут параллельно, поэтому логин выбирается по порту локального прокси
        SocksAuth.put((int) port, tunnel.args);
        try {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", (int) port));
            JSONObject response = post(proxy, tunnel, tunnel.needsCountry ? countryHint : null);
            int code = errorCode(response);
            if (!tunnel.needsCountry && countryHint != null
                    && (code == ERROR_NO_COUNTRY || code == ERROR_NEEDS_OTHER)) {
                response = post(proxy, tunnel, countryHint);
            }
            Result result = parse(response);
            // Пустой ответ Guardian Project не значит «мосты не нужны»: он не видит нашу сеть,
            // верим в это только Tor Project
            if (tunnel.needsCountry && result.directOk) result = new Result(result.sets, false);
            DiagLog.add("Moat " + tunnel.name + ": вариантов " + result.sets.size()
                    + ", страна " + response.optString("country", "?")
                    + (result.directOk ? ", мосты не нужны" : ""));
            return result;
        } finally {
            SocksAuth.remove((int) port);
            pt.stop();
        }
    }

    /** Общий Authenticator: логин SOCKS5 по порту прокси (java.net.Authenticator глобален). */
    private static final class SocksAuth extends Authenticator {
        private static final Map<Integer, String> LOGINS = new ConcurrentHashMap<>();
        private static boolean installed;

        static synchronized void put(int port, String login) {
            LOGINS.put(port, login);
            if (!installed) {
                Authenticator.setDefault(new SocksAuth());
                installed = true;
            }
        }

        static void remove(int port) {
            LOGINS.remove(port);
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            String login = LOGINS.get(getRequestingPort());
            return login != null ? new PasswordAuthentication(login, new char[]{'\u0000'}) : null;
        }
    }

    private static JSONObject post(Proxy proxy, Tunnel tunnel, @Nullable String country)
            throws IOException, JSONException {
        JSONObject body = new JSONObject();
        if (country != null) body.put("country", country.toLowerCase(Locale.ROOT));
        JSONArray transports = new JSONArray();
        transports.put(IPtProxy.Obfs4);
        transports.put(IPtProxy.Snowflake);
        transports.put(IPtProxy.Webtunnel);
        transports.put(IPtProxy.Dnstt);
        body.put("transports", transports);

        HttpURLConnection c = (HttpURLConnection) new URL(tunnel.url).openConnection(proxy);
        c.setConnectTimeout(tunnel.timeoutMs);
        c.setReadTimeout(tunnel.timeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/vnd.api+json");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
            if (in == null) throw new IOException("HTTP " + status);
            return new JSONObject(read(in));
        } finally {
            c.disconnect();
        }
    }

    private static int errorCode(JSONObject response) {
        JSONArray errors = response.optJSONArray("errors");
        if (errors == null || errors.length() == 0) return 0;
        JSONObject first = errors.optJSONObject(0);
        return first != null ? first.optInt("code", 0) : 0;
    }

    private static Result parse(JSONObject response) throws JSONException {
        List<BridgeSet> sets = new ArrayList<>();
        JSONArray settings = response.optJSONArray("settings");
        int code = errorCode(response);
        if (settings == null || settings.length() == 0) {
            // Нет настроек и нет ошибки: мосты в этой стране не нужны
            return new Result(sets, code == 0);
        }
        for (int i = 0; i < settings.length(); i++) {
            JSONObject bridges = settings.getJSONObject(i).optJSONObject("bridges");
            if (bridges == null) continue;
            String type = bridges.optString("type");
            String source = bridges.optString("source");
            JSONArray strings = bridges.optJSONArray("bridge_strings");
            List<String> lines = new ArrayList<>();
            if (strings != null) {
                for (int j = 0; j < strings.length(); j++) lines.add(strings.getString(j));
            }
            if (lines.isEmpty()) continue;
            sets.add(new BridgeSet(label(type, source), lines));
        }
        return new Result(sets, false);
    }

    private static String label(String type, String source) {
        String name;
        if (IPtProxy.Snowflake.equals(type)) {
            name = "Snowflake";
        } else if (IPtProxy.Webtunnel.equals(type)) {
            name = "WebTunnel";
        } else if (IPtProxy.Dnstt.equals(type)) {
            name = "DNSTT";
        } else {
            name = type;
        }
        return "builtin".equals(source) ? name : name + " (Moat)";
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0 && out.size() < MAX_BODY) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
