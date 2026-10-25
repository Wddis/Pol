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
import java.util.List;

/** Встроенные мосты Tor Project (assets/builtin-bridges.json, как в Tor Browser и Orbot). */
final class BuiltInBridges {

    static final String OBFS4 = "obfs4";
    static final String SNOWFLAKE = "snowflake";
    static final String MEEK = "meek";

    private static final String TAG = "BuiltInBridges";
    private static final String ASSET = "builtin-bridges.json";

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
