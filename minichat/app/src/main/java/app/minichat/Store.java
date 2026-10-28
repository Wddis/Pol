package app.minichat;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Session and settings, kept in app-private SharedPreferences. */
public final class Store implements Api.Counter {
    private final SharedPreferences prefs;

    public Store(Context context) {
        prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE);
    }

    public String token() {
        return prefs.getString("token", null);
    }

    public long userId() {
        return prefs.getLong("user_id", 0);
    }

    public String appId() {
        return prefs.getString("app_id", "");
    }

    public void saveAppId(String appId) {
        prefs.edit().putString("app_id", appId).apply();
    }

    public void saveSession(String token, long userId) {
        prefs.edit().putString("token", token).putLong("user_id", userId).apply();
    }

    public void logout() {
        prefs.edit().remove("token").remove("user_id").apply();
    }

    public Api api() {
        return new Api(token(), this);
    }

    /** Counts API method calls per calendar month, to keep an eye on the app's monthly quota. */
    @Override
    public synchronized void onCall() {
        String key = "calls_" + month();
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
    }

    public int callsThisMonth() {
        return prefs.getInt("calls_" + month(), 0);
    }

    private static String month() {
        return new SimpleDateFormat("yyyyMM", Locale.US).format(new Date());
    }
}
