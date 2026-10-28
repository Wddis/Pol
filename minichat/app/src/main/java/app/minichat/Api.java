package app.minichat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Minimal VK API client: one POST per call, server errors surfaced as {@link ApiException}. */
public final class Api {
    public static final String VERSION = "5.199";
    /** Replaced only by tests (in-process); never configurable from outside the app. */
    static String base = "https://api.vk.ru/method/";

    /** VK allows 3 requests per second for a user token. */
    private static final long MIN_INTERVAL_MS = 350;
    private static long lastCallAt;

    public interface Counter {
        void onCall();
    }

    private final String token;
    private final Counter counter;

    public Api(String token, Counter counter) {
        this.token = token;
        this.counter = counter;
    }

    public static final class ApiException extends Exception {
        public final int code;
        public String captchaSid;
        public String captchaImg;

        public ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    public static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put(kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    /** Calls a method; retries once on error 6 (too many requests per second). */
    public JSONObject call(String method, Map<String, String> params) throws IOException, ApiException {
        try {
            return callOnce(method, params);
        } catch (ApiException e) {
            if (e.code != 6) {
                throw e;
            }
            sleep(1000);
            return callOnce(method, params);
        }
    }

    private JSONObject callOnce(String method, Map<String, String> params) throws IOException, ApiException {
        throttle();
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            append(body, e.getKey(), e.getValue());
        }
        append(body, "access_token", token);
        append(body, "v", VERSION);
        if (counter != null) {
            counter.onCall();
        }
        String text = post(base + method, body.toString());
        try {
            JSONObject json = new JSONObject(text);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                ApiException e = new ApiException(error.optInt("error_code"), error.optString("error_msg"));
                e.captchaSid = error.optString("captcha_sid", null);
                e.captchaImg = error.optString("captcha_img", null);
                throw e;
            }
            return json;
        } catch (JSONException e) {
            throw new IOException("Bad response: " + e.getMessage());
        }
    }

    private static synchronized void throttle() {
        long wait = lastCallAt + MIN_INTERVAL_MS - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
        lastCallAt = System.currentTimeMillis();
    }

    private static void append(StringBuilder sb, String k, String v) {
        try {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, "UTF-8")).append('=').append(URLEncoder.encode(v, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    static String post(String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            OutputStream out = c.getOutputStream();
            out.write(body.getBytes("UTF-8"));
            out.close();
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    static String get(String url, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(readTimeoutMs);
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    static byte[] getBytes(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            return readBytes(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP " + code);
        }
        return new String(readBytes(c.getInputStream()), "UTF-8");
    }

    private static byte[] readBytes(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Human-readable text for an API error, keeping the server's code and message. */
    public static String describe(Throwable t) {
        if (t instanceof ApiException) {
            ApiException e = (ApiException) t;
            String hint = "";
            if (e.code == 9) {
                hint = "\nСервер VK ограничил частоту действий (или исчерпан лимит вызовов вашего приложения).";
            } else if (e.code == 15 || e.code == 7) {
                hint = "\nУ приложения нет доступа к этому действию (нужно право messages).";
            } else if (e.code == 29) {
                hint = "\nИсчерпан лимит вызовов этого метода.";
            }
            return "Ошибка VK " + e.code + ": " + e.getMessage() + hint;
        }
        return "Сеть: " + t.getMessage();
    }
}
