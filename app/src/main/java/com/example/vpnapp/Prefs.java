package com.example.vpnapp;

import android.content.Context;
import android.content.SharedPreferences;

/** Хранение выбранной страны. */
public final class Prefs {

    private static final String FILE = "oda_prefs";
    private static final String KEY_COUNTRY = "country";
    private static final String DEFAULT_COUNTRY = "DE";

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
}
