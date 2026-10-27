package com.example.vpnapp;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Ключ доступа к ODA: ввод, проверка на сервере ключей, лицензия, блокировка, предупреждения о сроке.
 *
 * <p>Сервер ключей — Cloudflare Worker бота (bot/worker); ключи выдаёт Telegram-бот. Решение «можно ли
 * пользоваться» ({@link #gate}) принимается по подписанной лицензии на телефоне ({@link License}), поэтому
 * работает и без сети; сервер спрашивается при вводе ключа, при запуске (если давно не спрашивали), при
 * подключении VPN и раз в 15 минут, пока VPN включён ({@link KeyWatch}). Ответ «заблокирован», «удалён» или
 * «срок вышел» сразу закрывает доступ: VPN отключается, экран ключа показывает причину.
 */
final class KeyManager {

    static final String SERVER = "https://oda-vpn-bot.dimazoltinsky.workers.dev";
    static final String BOT = "oda_connect_vpn_bot";
    /** Проверка при открытии приложения, если с прошлой прошло больше. */
    static final long CHECK_EVERY = 6 * 3600L;

    enum Gate { OK, NONE, EXPIRED, BANNED, NEED_CHECK }

    /** Ответ сервера ключей. */
    static final class Result {
        final boolean ok;
        final String status;
        final String message;
        /** Не дошли до сервера (сеть). */
        final boolean network;

        Result(boolean ok, String status, String message, boolean network) {
            this.ok = ok;
            this.status = status;
            this.message = message;
            this.network = network;
        }
    }

    private static final String FILE = "oda_key";
    private static final String K_KEY = "key";
    private static final String K_LICENSE = "license";
    private static final String K_LAST_OK = "last_ok";          // время сервера при последней удачной проверке
    private static final String K_LAST_TRY = "last_try";        // часы телефона при последней попытке
    private static final String K_MAX_SEEN = "max_seen";
    private static final String K_STATE = "server_state";       // "", expired, banned, not_found, device_removed, revoked
    private static final String K_MESSAGE = "server_message";
    private static final String K_BAN_REASON = "ban_reason";
    private static final String K_BAN_UNTIL = "ban_until";      // -1 — навсегда
    private static final String K_TITLE = "tariff_title";
    private static final String K_DEVICES = "devices";
    private static final String K_DEVICES_MAX = "devices_max";
    private static final String K_ACTIVATED = "activated_at";
    private static final String K_FOREVER = "forever";
    private static final String K_WARN_EXP = "warn_exp";
    private static final String K_WARN_LEVEL = "warn_level";
    private static final String K_DEVICE_ID = "device_id";

    private static final String CHANNEL = "oda_key";
    private static final int NOTIFY_ID = 7;

    @SuppressLint("StaticFieldLeak") // контекст приложения
    private static KeyManager instance;

    private final Context ctx;
    private final SharedPreferences sp;
    private String parsedToken;
    private License.Data parsed;

    private KeyManager(Context context) {
        ctx = context.getApplicationContext();
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static synchronized KeyManager get(Context context) {
        if (instance == null) instance = new KeyManager(context);
        return instance;
    }

    // ---------- состояние ----------

    private static long wallNow() {
        return System.currentTimeMillis() / 1000;
    }

    /** «Сейчас» с защитой от перевода часов назад; заодно запоминает наибольшее виденное время. */
    synchronized long now() {
        long wall = wallNow();
        long max = sp.getLong(K_MAX_SEEN, 0);
        if (wall > max) sp.edit().putLong(K_MAX_SEEN, wall).apply();
        return License.trustedNow(wall, Math.max(max, wall));
    }

    @Nullable
    synchronized License.Data license() {
        String token = sp.getString(K_LICENSE, null);
        if (token == null) return null;
        if (!token.equals(parsedToken)) {
            parsedToken = token;
            parsed = License.parse(token, License.PUBLIC_KEY);
        }
        return parsed;
    }

    /** Можно ли пользоваться ODA прямо сейчас (без сети). */
    synchronized Gate gate() {
        long now = now();
        String state = sp.getString(K_STATE, "");
        if ("banned".equals(state)) {
            long until = sp.getLong(K_BAN_UNTIL, -1);
            if (until < 0 || until > now) return Gate.BANNED;
            return Gate.NEED_CHECK; // срок блокировки прошёл — решит сервер
        }
        String key = key();
        if (key == null) return Gate.NONE;
        if ("not_found".equals(state) || "device_removed".equals(state) || "revoked".equals(state)) return Gate.NONE;
        if ("expired".equals(state)) return Gate.EXPIRED;
        switch (License.evaluate(license(), key, deviceId(), wallNow(), sp.getLong(K_MAX_SEEN, 0), sp.getLong(K_LAST_OK, 0))) {
            case OK:
                return Gate.OK;
            case EXPIRED:
                return Gate.EXPIRED;
            case NEED_CHECK:
                return Gate.NEED_CHECK;
            default:
                return Gate.NONE;
        }
    }

    @Nullable
    String key() {
        return sp.getString(K_KEY, null);
    }

    /** Сколько осталось по лицензии, секунды (0 — нет лицензии). */
    long secondsLeft() {
        License.Data d = license();
        return d == null ? 0 : Math.max(0, d.expires - now());
    }

    boolean forever() {
        return sp.getBoolean(K_FOREVER, false);
    }

    long expiresAt() {
        License.Data d = license();
        return d == null ? 0 : d.expires;
    }

    String tariffTitle() {
        return sp.getString(K_TITLE, "");
    }

    int devicesUsed() {
        return sp.getInt(K_DEVICES, 0);
    }

    int devicesMax() {
        License.Data d = license();
        return d != null ? d.devices : sp.getInt(K_DEVICES_MAX, 0);
    }

    long activatedAt() {
        return sp.getLong(K_ACTIVATED, 0);
    }

    long lastOk() {
        return sp.getLong(K_LAST_OK, 0);
    }

    String serverMessage() {
        return sp.getString(K_MESSAGE, "");
    }

    String banReason() {
        return sp.getString(K_BAN_REASON, "");
    }

    /** -1 — навсегда. */
    long banUntil() {
        return sp.getLong(K_BAN_UNTIL, -1);
    }

    /** Давно не спрашивали сервер — пора при открытии приложения. */
    boolean checkDue() {
        return wallNow() - sp.getLong(K_LAST_TRY, 0) > CHECK_EVERY;
    }

    /** «Сменить ключ»: всё о ключе забывается (отпечаток телефона остаётся). */
    synchronized void forget() {
        String id = sp.getString(K_DEVICE_ID, null);
        long maxSeen = sp.getLong(K_MAX_SEEN, 0);
        boolean banned = "banned".equals(sp.getString(K_STATE, ""));
        SharedPreferences.Editor e = sp.edit().clear().putLong(K_MAX_SEEN, maxSeen);
        if (id != null) e.putString(K_DEVICE_ID, id);
        // Блокировка устройства не снимается сменой ключа
        if (banned) {
            e.putString(K_STATE, "banned").putString(K_BAN_REASON, banReason()).putLong(K_BAN_UNTIL, banUntil());
        }
        e.apply();
        parsedToken = null;
        parsed = null;
    }

    // ---------- отпечаток телефона ----------

    /**
     * Отпечаток телефона для сервера ключей: из ANDROID_ID (у Android 8+ он свой для каждого приложения и не
     * меняется при переустановке) — переустановка не даёт ни нового места на ключе, ни обхода блокировки.
     */
    synchronized String deviceId() {
        String id = sp.getString(K_DEVICE_ID, null);
        if (id != null) return id;
        @SuppressLint("HardwareIds")
        String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        String seed = androidId != null && !androidId.isEmpty() && !"9774d56d682e549c".equals(androidId)
                ? "oda:" + androidId : "oda-random:" + System.nanoTime() + ":" + Math.random();
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            byte[] cut = new byte[18];
            System.arraycopy(h, 0, cut, 0, cut.length);
            id = License.b64(cut);
        } catch (NoSuchAlgorithmException e) {
            id = Long.toHexString(seed.hashCode()) + Long.toHexString(System.nanoTime());
        }
        sp.edit().putString(K_DEVICE_ID, id).apply();
        return id;
    }

    static String deviceName() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String name = model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT)) ? model : maker + " " + model;
        name = name.trim();
        if (name.isEmpty()) return "Android";
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    private String appVersion() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ---------- сервер ключей ----------

    /** Ввод ключа (как угодно: строчные, без дефисов, с пробелами). */
    @WorkerThread
    Result activate(String rawKey) {
        String key = normalize(rawKey);
        if (key == null) return new Result(false, "bad_key", ctx.getString(R.string.key_error_format), false);
        JSONObject body = new JSONObject();
        try {
            body.put("key", key).put("device_id", deviceId()).put("device_name", deviceName()).put("version", appVersion());
        } catch (JSONException ignored) {
            // не бывает
        }
        return handle("/v1/activate", body, key, 0);
    }

    /**
     * Проверка ключа; vpn != null — заодно сигнал «VPN включён/выключен» (для «Сейчас в VPN» у администратора).
     * socksPort > 0 — если напрямую сервер недоступен, попробовать через подключённый способ.
     */
    @WorkerThread
    Result check(@Nullable Boolean vpn, String mode, int socksPort) {
        String key = key();
        if (key == null) return new Result(false, "no_key", "", false);
        JSONObject body = new JSONObject();
        try {
            body.put("key", key).put("device_id", deviceId()).put("version", appVersion());
            if (vpn != null) body.put("vpn", vpn.booleanValue()).put("mode", mode);
        } catch (JSONException ignored) {
            // не бывает
        }
        return handle("/v1/check", body, key, socksPort);
    }

    private Result handle(String path, JSONObject body, String key, int socksPort) {
        sp.edit().putLong(K_LAST_TRY, wallNow()).apply();
        JSONObject r;
        try {
            r = post(path, body, socksPort);
        } catch (IOException | JSONException e) {
            DiagLog.add("Ключ: сервер ключей недоступен (" + e.getClass().getSimpleName() + ")");
            return new Result(false, "network", ctx.getString(R.string.key_error_network), true);
        }
        String status = r.optString("status", "");
        String message = r.optString("message", "");
        long serverTime = r.optLong("server_time", 0);
        synchronized (this) {
            SharedPreferences.Editor e = sp.edit();
            if (serverTime > sp.getLong(K_MAX_SEEN, 0)) e.putLong(K_MAX_SEEN, serverTime);
            switch (status) {
                case "ok": {
                    String token = r.optString("license", "");
                    License.Data d = License.parse(token, License.PUBLIC_KEY);
                    if (d == null || !key.equals(d.key) || !deviceId().equals(d.device)) {
                        e.apply();
                        DiagLog.add("Ключ: ответ сервера не прошёл проверку подписи");
                        return new Result(false, "bad_license", ctx.getString(R.string.key_error_license), false);
                    }
                    e.putString(K_KEY, key).putString(K_LICENSE, token).putLong(K_LAST_OK, serverTime)
                            .putString(K_STATE, "").putString(K_MESSAGE, "")
                            .remove(K_BAN_REASON).remove(K_BAN_UNTIL)
                            .putString(K_TITLE, r.optString("tariff_title", ""))
                            .putInt(K_DEVICES, r.optInt("devices_used", 0))
                            .putInt(K_DEVICES_MAX, r.optInt("devices_max", 0))
                            .putLong(K_ACTIVATED, r.optLong("activated_at", 0))
                            .putBoolean(K_FOREVER, r.optBoolean("forever", false));
                    e.apply();
                    return new Result(true, status, "", false);
                }
                case "banned":
                    e.putString(K_STATE, "banned").putString(K_MESSAGE, message)
                            .putString(K_BAN_REASON, r.optString("reason", ""))
                            .putLong(K_BAN_UNTIL, r.isNull("until") ? -1 : r.optLong("until", -1));
                    e.apply();
                    DiagLog.add("Ключ: доступ заблокирован — " + r.optString("reason", ""));
                    return new Result(false, status, message, false);
                case "expired":
                case "not_found":
                case "device_removed":
                case "revoked":
                    // Ввод чужого или неверного ключа не трогает уже работающий
                    if (key.equals(key())) e.putString(K_STATE, status).putString(K_MESSAGE, message);
                    e.apply();
                    DiagLog.add("Ключ: " + status + " — " + message);
                    return new Result(false, status, message, false);
                default:
                    e.apply();
                    return new Result(false, status, message.isEmpty() ? ctx.getString(R.string.key_error_server) : message, false);
            }
        }
    }

    /** POST JSON на сервер ключей: напрямую, а если не вышло и VPN подключён — через его SOCKS. */
    private static JSONObject post(String path, JSONObject body, int socksPort) throws IOException, JSONException {
        try {
            return post(path, body, Proxy.NO_PROXY);
        } catch (IOException direct) {
            if (socksPort <= 0) throw direct;
            return post(path, body, new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort)));
        }
    }

    private static JSONObject post(String path, JSONObject body, Proxy proxy) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(SERVER + path).openConnection(proxy);
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(out);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            try (InputStream is = in) {
                int n;
                while ((n = is.read(chunk)) > 0 && buf.size() < 65536) buf.write(chunk, 0, n);
            }
            String text = buf.toString("UTF-8");
            // Не JSON (страница блокировки провайдера, сбой Cloudflare) — как недоступность сервера
            if (!text.trim().startsWith("{")) throw new IOException("HTTP " + code + ", не JSON");
            return new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    // ---------- формат ключа (как на сервере: bot/worker/src/keys.ts) ----------

    private static final String ALPHABET = "ACDEFGHJKMNPQRTUVWXYZ234679";

    @Nullable
    static String normalize(String text) {
        if (text == null) return null;
        String raw = text.replaceAll("[\\s\\-_]+", "").toUpperCase(Locale.ROOT);
        if (raw.startsWith("ODA")) raw = raw.substring(3);
        if (raw.length() != 12) return null;
        for (int i = 0; i < raw.length(); i++) if (ALPHABET.indexOf(raw.charAt(i)) < 0) return null;
        return "ODA-" + raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8);
    }

    // ---------- Telegram-бот ----------

    /** Ссылка на бота; start — раздел: support, pay, renew, buy, trial (пусто — главное меню). */
    static Intent botIntent(String start) {
        String url = "https://t.me/" + BOT + (start == null || start.isEmpty() ? "" : "?start=" + start);
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // ---------- тексты ----------

    /** Почему нельзя подключиться (для ошибки на главном экране и уведомления). */
    String lockText(Gate g) {
        switch (g) {
            case BANNED:
                return ctx.getString(R.string.key_lock_banned, banReason());
            case EXPIRED:
                return ctx.getString(R.string.key_lock_expired);
            case NEED_CHECK:
                return ctx.getString(R.string.key_lock_check);
            default:
                String m = serverMessage();
                return m.isEmpty() ? ctx.getString(R.string.key_lock_none) : m;
        }
    }

    /** «28 дней», «5 часов», «12 минут», «бессрочно». */
    String leftText() {
        if (forever()) return ctx.getString(R.string.key_forever);
        long s = secondsLeft();
        if (s >= 86400) {
            int n = (int) ((s + 86399) / 86400);
            return Ui.plural(ctx, R.plurals.key_days, n);
        }
        if (s >= 3600) {
            int n = (int) ((s + 3599) / 3600);
            return Ui.plural(ctx, R.plurals.key_hours, n);
        }
        int n = (int) Math.max(1, (s + 59) / 60);
        return Ui.plural(ctx, R.plurals.key_minutes, n);
    }

    // ---------- уведомления ----------

    /** Ключ заканчивается: уведомление за 2 дня и за 1 день (один раз на каждый порог; ключам до 3 дней — нет). */
    void maybeWarn() {
        if (gate() != Gate.OK || forever()) return;
        long exp = expiresAt();
        long total = exp - activatedAt();
        if (activatedAt() <= 0 || total <= 3 * 86400L) return;
        long left = secondsLeft();
        int level = left <= 86400 ? 1 : left <= 2 * 86400 ? 2 : 0;
        if (level == 0) return;
        int warned = sp.getLong(K_WARN_EXP, 0) == exp ? sp.getInt(K_WARN_LEVEL, 0) : 0;
        if (warned != 0 && warned <= level) return;
        sp.edit().putLong(K_WARN_EXP, exp).putInt(K_WARN_LEVEL, level).apply();
        notify(ctx.getString(R.string.key_warn_title, leftText()), ctx.getString(R.string.key_warn_text));
    }

    /** VPN отключён из-за ключа. */
    void notifyLocked(String text) {
        notify(ctx.getString(R.string.key_locked_title), text);
    }

    private void notify(String title, String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                        ctx.getString(R.string.key_channel), NotificationManager.IMPORTANCE_DEFAULT));
            }
        }
        PendingIntent open = PendingIntent.getActivity(ctx, 3, new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_power)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFY_ID, b.build());
        } catch (SecurityException ignored) {
            // нет разрешения на уведомления — экран ключа всё равно покажет
        }
    }
}
