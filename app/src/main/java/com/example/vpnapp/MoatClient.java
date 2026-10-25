package com.example.vpnapp;

import android.util.Log;

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

    private static final String TAG = "MoatClient";
    private static final String URL_SETTINGS = "https://bridges.torproject.org/moat/circumvention/settings";
    // Параметры meek для Moat (как в Orbot / Tor Browser)
    private static final String MEEK_ARGS = "url=https://1723079976.rsc.cdn77.org;front=www.phpmyadmin.net";
    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_BODY = 256 * 1024;
    private static final int ERROR_NEEDS_OTHER = 404;
    private static final int ERROR_NO_COUNTRY = 406;

    private MoatClient() {
    }

    /** countryHint — код страны сети (например, "ru") на случай, если Moat не определит её сам. */
    static Result fetch(PtController pt, @Nullable String countryHint) throws Exception {
        long port = pt.startSingle(IPtProxy.MeekLite);
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                // Параметры meek передаются как логин SOCKS5
                return new PasswordAuthentication(MEEK_ARGS, new char[]{'\u0000'});
            }
        });
        try {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", (int) port));
            JSONObject response = post(proxy, null);
            int code = errorCode(response);
            if ((code == ERROR_NO_COUNTRY || code == ERROR_NEEDS_OTHER) && countryHint != null) {
                response = post(proxy, countryHint);
            }
            return parse(response);
        } finally {
            Authenticator.setDefault(null);
            pt.stop();
        }
    }

    private static JSONObject post(Proxy proxy, @Nullable String country) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        if (country != null) body.put("country", country.toLowerCase(Locale.ROOT));
        JSONArray transports = new JSONArray();
        transports.put(IPtProxy.Obfs4);
        transports.put(IPtProxy.Snowflake);
        transports.put(IPtProxy.Webtunnel);
        body.put("transports", transports);

        HttpURLConnection c = (HttpURLConnection) new URL(URL_SETTINGS).openConnection(proxy);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
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
        Log.i(TAG, "Moat: " + sets.size() + " options, country=" + response.optString("country"));
        return new Result(sets, false);
    }

    private static String label(String type, String source) {
        String name;
        if (IPtProxy.Snowflake.equals(type)) {
            name = "Snowflake";
        } else if (IPtProxy.Webtunnel.equals(type)) {
            name = "WebTunnel";
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
