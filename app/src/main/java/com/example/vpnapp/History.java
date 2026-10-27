package com.example.vpnapp;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * История подключений (вкладка «История»): когда, сколько, каким способом и через какую страну, а также
 * ошибки подключения. Пишется из {@link StatusBus} по смене состояния; хранится на телефоне, последние 50.
 */
final class History {

    /** Запись: подключение (end = 0 — идёт сейчас или прервано) или ошибка. */
    static final class Entry {
        final boolean error;
        final long start;      // System.currentTimeMillis
        final long end;
        final String engine;   // StatusBus.ENGINE_*
        final String detail;   // первая строка под статусом, либо текст ошибки

        Entry(boolean error, long start, long end, String engine, String detail) {
            this.error = error;
            this.start = start;
            this.end = end;
            this.engine = engine;
            this.detail = detail;
        }
    }

    private static final String FILE = "oda_history";
    private static final String KEY = "entries";
    private static final int MAX = 50;

    private static SharedPreferences sp;
    private static ConnectionState last = ConnectionState.OFF;
    /** Идёт подключение — последняя запись открыта (end = 0). */
    private static boolean open;

    private History() {
    }

    static synchronized void init(Context context) {
        if (sp == null) sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static synchronized void onStatus(@NonNull StatusBus.Status s) {
        if (sp == null) return;
        ConnectionState prev = last;
        last = s.state;
        long now = System.currentTimeMillis();
        String first = s.detail.split("\n")[0];
        switch (s.state) {
            case ON:
                if (!open) {
                    add(new Entry(false, now, 0, s.engine, first));
                    open = true;
                } else if (!first.isEmpty()) {
                    replaceLast(e -> new Entry(false, e.start, 0, s.engine, first));
                }
                break;
            case OFF:
                close(now);
                break;
            case ERROR:
                close(now);
                if (prev != ConnectionState.ERROR && !s.error.isEmpty()) add(new Entry(true, now, now, "", s.error));
                break;
            default:
                break;
        }
    }

    private static void close(long now) {
        if (!open) return;
        open = false;
        replaceLast(e -> new Entry(false, e.start, now, e.engine, e.detail));
    }

    /** Новые — первыми. */
    static synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        if (sp == null) return out;
        JSONArray arr = load();
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            out.add(new Entry(o.optBoolean("err"), o.optLong("start"), o.optLong("end"), o.optString("engine"),
                    o.optString("detail")));
        }
        return out;
    }

    /** Подключение сейчас идёт (последняя запись — открытая). */
    static synchronized boolean isOpen() {
        return open;
    }

    static synchronized void clear() {
        if (sp != null) sp.edit().remove(KEY).apply();
        open = false;
    }

    private interface Edit {
        Entry apply(Entry e);
    }

    private static void add(Entry e) {
        JSONArray arr = load();
        arr.put(toJson(e));
        JSONArray out = new JSONArray();
        for (int i = Math.max(0, arr.length() - MAX); i < arr.length(); i++) out.put(arr.opt(i));
        sp.edit().putString(KEY, out.toString()).apply();
    }

    private static void replaceLast(Edit edit) {
        JSONArray arr = load();
        if (arr.length() == 0) return;
        JSONObject o = arr.optJSONObject(arr.length() - 1);
        if (o == null || o.optBoolean("err")) return;
        Entry e = new Entry(false, o.optLong("start"), o.optLong("end"), o.optString("engine"), o.optString("detail"));
        try {
            arr.put(arr.length() - 1, toJson(edit.apply(e)));
        } catch (JSONException ignored) {
            return;
        }
        sp.edit().putString(KEY, arr.toString()).apply();
    }

    private static JSONArray load() {
        try {
            return new JSONArray(sp.getString(KEY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static JSONObject toJson(Entry e) {
        JSONObject o = new JSONObject();
        try {
            o.put("err", e.error).put("start", e.start).put("end", e.end).put("engine", e.engine).put("detail", e.detail);
        } catch (JSONException ignored) {
            // не бывает
        }
        return o;
    }
}
