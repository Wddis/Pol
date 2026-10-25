package com.example.vpnapp;

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

/** Запрос IP выхода через SOCKS-прокси Tor. Вызывать только из фонового потока. */
final class IpChecker {

    private static final String CHECK_URL = "https://check.torproject.org/api/ip";
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
