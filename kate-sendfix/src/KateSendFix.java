import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;
import android.widget.Toast;
import com.perm.kate.KApplication;
import com.perm.kate.api.KException;
import java.net.URLEncoder;
import org.json.JSONObject;

/**
 * Sends messages.send with a token issued to the user's own VK application.
 *
 * Since 2026-09-07 VK meters API calls per application (client_id). The quota of
 * Kate's client_id 2685278 is exhausted, so the server answers error 9
 * "Flood control" to Kate's token regardless of how often the user sends. A token
 * issued to an application registered by the user is metered against that
 * user's own quota. Everything except messages.send keeps using the main token.
 */
public final class KateSendFix {
    public static final String EXTRA_SEND_TOKEN = "send_token";
    static final String PREF_API_ID = "send_api_id";
    // Separate file so the token is not included in Kate's settings export.
    static final String TOKENS = "kate_send_tokens";
    static final String REDIRECT = "https://oauth.vk.ru/blank.html";
    static final String TAG = "Kate.SendFix";

    private KateSendFix() {
    }

    static boolean isSendTokenMode(Activity activity) {
        Intent intent = activity.getIntent();
        return intent != null && intent.getStringExtra(EXTRA_SEND_TOKEN) != null;
    }

    /** LoginActivity.onCreate hook. Returns true when the activity was opened to obtain a send token. */
    public static boolean startAuth(Activity activity, WebView webView) {
        if (!isSendTokenMode(activity)) {
            return false;
        }
        String apiId = PreferenceManager.getDefaultSharedPreferences(activity).getString(PREF_API_ID, "");
        apiId = apiId == null ? "" : apiId.trim();
        if (!apiId.matches("[0-9]{1,12}")) {
            activity.getSharedPreferences(TOKENS, Context.MODE_PRIVATE).edit().clear().commit();
            toast(activity, "API ID не задан: токен отправки удалён, сообщения отправляются основным токеном Kate");
            activity.finish();
            return true;
        }
        try {
            webView.loadUrl("https://oauth.vk.ru/authorize?client_id=" + apiId
                    + "&display=mobile&scope=messages,offline&redirect_uri=" + URLEncoder.encode(REDIRECT, "utf-8")
                    + "&response_type=token&revoke=1&v=5.207");
        } catch (Exception e) {
            Log.e(TAG, "startAuth", e);
            toast(activity, e.toString());
            activity.finish();
        }
        return true;
    }

    /** LoginActivity.H hook. Returns true when the URL belongs to the send-token flow and was consumed. */
    public static boolean onUrl(Activity activity, String url) {
        if (!isSendTokenMode(activity)) {
            return false;
        }
        if (url == null || !url.startsWith(REDIRECT) || activity.isFinishing()) {
            return true;
        }
        try {
            saveToken(activity, url);
        } catch (Throwable t) {
            Log.e(TAG, "onUrl", t);
            toast(activity, t.toString());
        }
        activity.finish();
        return true;
    }

    static void saveToken(Activity activity, String url) {
        int hash = url.indexOf('#');
        int query = url.indexOf('?');
        String params = hash >= 0 ? url.substring(hash + 1) : query >= 0 ? url.substring(query + 1) : "";
        Uri uri = Uri.parse("https://localhost/?" + params);
        String token = uri.getQueryParameter("access_token");
        String uid = uri.getQueryParameter("user_id");
        if (!TextUtils.isEmpty(token) && !TextUtils.isEmpty(uid)) {
            activity.getSharedPreferences(TOKENS, Context.MODE_PRIVATE).edit().putString("token_" + uid, token).commit();
            toast(activity, "Токен отправки сохранён для id" + uid);
            activity.setResult(Activity.RESULT_OK);
        } else {
            String error = uri.getQueryParameter("error_description");
            if (TextUtils.isEmpty(error)) {
                error = uri.getQueryParameter("error");
            }
            toast(activity, "Токен отправки не получен: " + error);
        }
    }

    /** Replacement for e3.o(request, true) in the messages.send request (xr0.b). */
    public static JSONObject send(e3 api, u70 request, String uid) throws Exception {
        String token = token(uid);
        if (token != null) {
            try {
                return new e3(token).o(request, true);
            } catch (KException e) {
                // Token-level failures of the send token must not reach qt0: it would treat them as
                // problems of the main session (re-login, validation, refreshToken). Drop the send
                // token and fall back to the main one instead.
                int code = e.error_code;
                if (code != 4 && code != 5 && code != 17 && code != 25) {
                    throw explain(e, true);
                }
                Log.w(TAG, "send token rejected with code " + code + ", removed");
                KApplication.d.getSharedPreferences(TOKENS, Context.MODE_PRIVATE).edit().remove("token_" + uid).commit();
            }
        }
        try {
            return api.o(request, true);
        } catch (KException e) {
            throw explain(e, false);
        }
    }

    static String token(String uid) {
        if (TextUtils.isEmpty(uid) || KApplication.d == null) {
            return null;
        }
        SharedPreferences prefs = KApplication.d.getSharedPreferences(TOKENS, Context.MODE_PRIVATE);
        String token = prefs.getString("token_" + uid, null);
        return TextUtils.isEmpty(token) ? null : token;
    }

    /** Keeps the server's error code and text, adds which quota the error refers to. */
    static KException explain(KException e, boolean ownApp) {
        String hint = null;
        if (e.error_code == 9) {
            hint = ownApp
                    ? "токен вашего приложения VK: исчерпан его лимит вызовов или это реальный флуд-контроль"
                    : "токен Kate (client_id 2685278): VK лимитирует вызовы API этого приложения. Задайте API ID своего приложения VK: Настройки → Сообщения → Отправка через своё приложение VK";
        } else if (ownApp && (e.error_code == 7 || e.error_code == 15)) {
            hint = "вашему приложению VK не выдан доступ к сообщениям (messages)";
        }
        if (hint == null) {
            return e;
        }
        KException wrapped = new KException(e.getMessage() + "\n(" + hint + ")", e.error_code, e.url);
        wrapped.captcha_img = e.captcha_img;
        wrapped.captcha_sid = e.captcha_sid;
        wrapped.redirect_uri = e.redirect_uri;
        return wrapped;
    }

    static void toast(Activity activity, String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }
}
