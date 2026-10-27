package com.example.vpnapp;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Настройки: страна, режим мостов, свои мосты, последний рабочий способ, приложения мимо VPN. */
public final class Prefs {

    private static final String FILE = "oda_prefs";
    private static final String KEY_COUNTRY = "country";
    private static final String KEY_MODE = "bridge_mode";
    private static final String KEY_CUSTOM = "custom_bridges";
    private static final String KEY_BYEDPI = "byedpi_args";
    // До 0.8.5: название сработавшей стратегии, одно на все сети (там могла остаться «oob SNI»)
    private static final String KEY_BYPASS_GOOD_OLD = "bypass_good";
    private static final String KEY_BYPASS_BEST = "bypass_best_";  // + тип сети
    private static final String KEY_LAST_GOOD = "last_good";
    private static final String KEY_MOAT = "moat_cache";
    private static final String KEY_DIRECT_FAILED = "direct_failed";
    private static final String KEY_OBFS4_FAILED = "obfs4_failed";
    private static final String KEY_EXCLUDED_APPS = "excluded_apps";
    private static final String KEY_TILE_OFFERED = "tile_offered";
    private static final String KEY_KILL_SWITCH = "kill_switch";
    private static final String KEY_TAB = "tab";
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

    /** Свои аргументы ByeDPI для обхода DPI (пусто — встроенный перебор стратегий). */
    String getByedpiArgs() {
        return sp.getString(KEY_BYEDPI, "");
    }

    void setByedpiArgs(String text) {
        sp.edit().putString(KEY_BYEDPI, text).apply();
    }

    /** Стратегия обхода DPI, выбранная в прошлый раз в этой сети. */
    static final class BypassBest {
        final BypassProfile profile;
        /** Она пробила заблокированные сайты (иначе — только обычный интернет). */
        final boolean full;
        /** Когда выбрана (System.currentTimeMillis). */
        final long time;

        BypassBest(BypassProfile profile, boolean full, long time) {
            this.profile = profile;
            this.full = full;
            this.time = time;
        }
    }

    /** Стратегия обхода DPI, выбранная в прошлый раз в сети networkId (WIFI, MOBILE-25001, …). */
    @Nullable
    BypassBest getBypassBest(String networkId) {
        if (sp.contains(KEY_BYPASS_GOOD_OLD)) sp.edit().remove(KEY_BYPASS_GOOD_OLD).apply();
        String raw = sp.getString(KEY_BYPASS_BEST + networkId, null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            BypassProfile p = BypassProfile.parse(o.getString("label"), o.getString("args"));
            return p != null ? new BypassBest(p, o.getBoolean("full"), o.getLong("time")) : null;
        } catch (JSONException e) {
            return null;
        }
    }

    void setBypassBest(String networkId, BypassProfile profile, boolean full) {
        try {
            JSONObject o = new JSONObject()
                    .put("label", profile.label)
                    .put("args", profile.argsLine())
                    .put("full", full)
                    .put("time", System.currentTimeMillis());
            sp.edit().putString(KEY_BYPASS_BEST + networkId, o.toString()).apply();
        } catch (JSONException ignored) {
            // не запомнили — в следующий раз будет полный перебор
        }
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

    /** Встроенные obfs4 в прошлый раз не прошли — пробуем их в конце. */
    boolean isObfs4Failed() {
        return sp.getBoolean(KEY_OBFS4_FAILED, false);
    }

    void setObfs4Failed(boolean failed) {
        sp.edit().putBoolean(KEY_OBFS4_FAILED, failed).apply();
    }

    /**
     * Приложения, которые идут мимо VPN (обычная сеть). Пусто — через VPN все (по умолчанию).
     * Копия: набор из SharedPreferences менять нельзя.
     */
    Set<String> getExcludedApps() {
        return new HashSet<>(sp.getStringSet(KEY_EXCLUDED_APPS, new HashSet<>()));
    }

    void setExcludedApps(Set<String> packages) {
        sp.edit().putStringSet(KEY_EXCLUDED_APPS, new HashSet<>(packages)).apply();
    }

    /** Добавить плитку в шторку уже предлагали (Android 13+, один раз). */
    boolean isTileOffered() {
        return sp.getBoolean(KEY_TILE_OFFERED, false);
    }

    void setTileOffered() {
        sp.edit().putBoolean(KEY_TILE_OFFERED, true).apply();
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

    /** Kill switch по данным системы при последнем подключении: -1 — неизвестно, 0 — нет, 1 — «Постоянная VPN», 2 — ещё и блокировка без VPN. */
    int getKillSwitch() {
        return sp.getInt(KEY_KILL_SWITCH, -1);
    }

    void setKillSwitch(int state) {
        sp.edit().putInt(KEY_KILL_SWITCH, state).apply();
    }

    /** Открытая вкладка главного экрана. */
    int getTab() {
        return sp.getInt(KEY_TAB, 0);
    }

    void setTab(int tab) {
        sp.edit().putInt(KEY_TAB, tab).apply();
    }
}
