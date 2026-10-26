package com.example.vpnapp;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Запрос IP выхода через локальный SOCKS-прокси (Tor или Psiphon). Вызывать только из фонового потока. */
final class IpChecker {

    /** IP выхода и код страны (или null). */
    static final class Exit {
        final String ip;
        @Nullable final String country;

        Exit(String ip, @Nullable String country) {
            this.ip = ip;
            this.country = country;
        }
    }

    private static final String CHECK_URL = "https://check.torproject.org/api/ip";
    // Служебная страница Cloudflare: ip=… и loc=… (страна по IP); сначала по IP, без DNS
    private static final String[] TRACE_URLS = {
            "https://1.1.1.1/cdn-cgi/trace",
            "https://www.cloudflare.com/cdn-cgi/trace",
    };
    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_BODY = 4096;

    private IpChecker() {
    }

    /** Возвращает IP выхода; бросает исключение, если запрос не прошёл через Tor. */
    static String fetchExitIp(int socksPort) throws IOException {
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
        HttpURLConnection c = (HttpURLConnection) new URL(CHECK_URL).openConnection(proxy);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            JSONObject json = new JSONObject(read(c.getInputStream()));
            if (!json.optBoolean("IsTor", false)) {
                throw new IOException("Not Tor");
            }
            return json.getString("IP");
        } catch (JSONException e) {
            throw new IOException("Bad response", e);
        } finally {
            c.disconnect();
        }
    }

    /** IP и страна выхода через любой SOCKS-прокси (для Psiphon). */
    static Exit fetchTrace(int socksPort) throws IOException {
        IOException last = null;
        for (String url : TRACE_URLS) {
            try {
                return trace(url, socksPort);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no trace");
    }

    private static Exit trace(String url, int socksPort) throws IOException {
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            String ip = null;
            String loc = null;
            for (String line : read(c.getInputStream()).split("\n")) {
                if (line.startsWith("ip=")) ip = line.substring(3).trim();
                if (line.startsWith("loc=")) loc = line.substring(4).trim();
            }
            if (ip == null || ip.isEmpty()) throw new IOException("Bad response");
            return new Exit(ip, loc == null || loc.isEmpty() || "XX".equals(loc) ? null : loc);
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) > 0 && out.size() < MAX_BODY) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
