package com.example.vpnapp;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Настройки: страна, режим мостов, свои мосты, последний рабочий способ. */
public final class Prefs {

    private static final String FILE = "oda_prefs";
    private static final String KEY_COUNTRY = "country";
    private static final String KEY_MODE = "bridge_mode";
    private static final String KEY_CUSTOM = "custom_bridges";
    private static final String KEY_LAST_GOOD = "last_good";
    private static final String KEY_MOAT = "moat_cache";
    private static final String KEY_DIRECT_FAILED = "direct_failed";
    private static final String DEFAULT_COUNTRY = Country.ANY;

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getCountry() {
        return sp.getString(KEY_COUNTRY, DEFAULT_COUNTRY);
    }

    public void setCountry(String code) {
        sp.edit().putString(KEY_COUNTRY, code).apply();
    }

    BridgeMode getMode() {
        return BridgeMode.from(sp.getString(KEY_MODE, BridgeMode.AUTO.name()));
    }

    void setMode(BridgeMode mode) {
        sp.edit().putString(KEY_MODE, mode.name()).apply();
    }

    String getCustomBridges() {
        return sp.getString(KEY_CUSTOM, "");
    }

    void setCustomBridges(String text) {
        sp.edit().putString(KEY_CUSTOM, text).apply();
    }

    @Nullable
    BridgeSet getLastGood() {
        return BridgeSet.deserialize(sp.getString(KEY_LAST_GOOD, null));
    }

    void setLastGood(@Nullable BridgeSet set) {
        sp.edit().putString(KEY_LAST_GOOD, set != null ? set.serialize() : null).apply();
    }

    /** Прямое подключение в прошлый раз не прошло — не начинаем с него. */
    boolean isDirectFailed() {
        return sp.getBoolean(KEY_DIRECT_FAILED, false);
    }

    void setDirectFailed(boolean failed) {
        sp.edit().putBoolean(KEY_DIRECT_FAILED, failed).apply();
    }

    /** Мосты, полученные от Moat в прошлый раз (на случай, если Moat недоступен). */
    List<BridgeSet> getMoatCache() {
        List<BridgeSet> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_MOAT, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                BridgeSet set = BridgeSet.deserialize(arr.getString(i));
                if (set != null) result.add(set);
            }
        } catch (JSONException ignored) {
            // повреждённый кэш просто игнорируем
        }
        return result;
    }

    void setMoatCache(List<BridgeSet> sets) {
        JSONArray arr = new JSONArray();
        for (BridgeSet s : sets) arr.put(s.serialize());
        sp.edit().putString(KEY_MOAT, arr.toString()).apply();
    }
}
