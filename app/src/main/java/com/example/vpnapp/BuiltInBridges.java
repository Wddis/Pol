package com.example.vpnapp;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Встроенные мосты Tor Project (assets/builtin-bridges.json, как в Tor Browser и Orbot). */
final class BuiltInBridges {

    static final String OBFS4 = "obfs4";
    static final String SNOWFLAKE = "snowflake";
    static final String MEEK = "meek";

    private static final String TAG = "BuiltInBridges";
    private static final String ASSET = "builtin-bridges.json";

    // Snowflake через AMP-кэш Google: другой путь к брокеру, если CDN77 недоступен
    private static final String AMP_BROKER = "https://snowflake-broker.torproject.net/";
    private static final String AMP_CACHE = "https://cdn.ampproject.org/";
    private static final String AMP_FRONT = "www.google.com";

    // DNSTT через DoH Google (мосты Guardian Project, как в Orbot). Медленно, но проходит
    // там, где заблокировано почти всё. Адреса 192.0.2.4:N — заглушки, так принято для DNSTT.
    private static final String[] DNSTT = {
            "dnstt 192.0.2.4:1 A998F319ADB60EE344540EC4B21524CC484F96BE doh=https://dns.google/dns-query"
                    + " pubkey=241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f domain=t.ruhnama.net",
            "dnstt 192.0.2.4:2 80EEFA4F4875ED2B7B5A86DF2D7588AD32E29F15 doh=https://dns.google/dns-query"
                    + " pubkey=a2fb71077eeaa54a02cda7a90be306af5d299ab21822a8b277d4eacbc9168631 domain=t2.bypasscensorship.org",
    };

    private BuiltInBridges() {
    }

    static List<String> get(Context context, String type) {
        List<String> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(readAsset(context)).optJSONArray(type);
            if (arr == null) return result;
            for (int i = 0; i < arr.length(); i++) result.add(arr.getString(i));
        } catch (IOException | JSONException e) {
            Log.e(TAG, "builtin bridges", e);
        }
        return result;
    }

    /** Встроенные snowflake, переписанные на рандеву через AMP-кэш Google. */
    static List<String> snowflakeAmp(Context context) {
        List<String> result = new ArrayList<>();
        List<String> base = get(context, SNOWFLAKE);
        for (int i = 0; i < base.size(); i++) {
            String[] parts = base.get(i).trim().split("\\s+");
            if (parts.length < 3) continue;
            StringBuilder sb = new StringBuilder("snowflake 192.0.2.").append(5 + i).append(":80");
            for (int j = 2; j < parts.length; j++) {
                String p = parts[j];
                if (p.startsWith("url=") || p.startsWith("front=") || p.startsWith("fronts=")
                        || p.startsWith("ampcache=")) {
                    continue;
                }
                sb.append(' ').append(p);
            }
            sb.append(" url=").append(AMP_BROKER)
                    .append(" ampcache=").append(AMP_CACHE)
                    .append(" fronts=").append(AMP_FRONT);
            result.add(sb.toString());
        }
        return result;
    }

    static List<String> dnstt() {
        List<String> result = new ArrayList<>();
        Collections.addAll(result, DNSTT);
        return result;
    }

    private static String readAsset(Context context) throws IOException {
        try (InputStream in = context.getAssets().open(ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
