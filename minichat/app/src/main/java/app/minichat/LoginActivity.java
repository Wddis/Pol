package app.minichat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Login with the user's OWN VK application. The user enters the numeric ID of a
 * Standalone app they registered, then signs in; the resulting token is metered
 * against that app's quota, not a shared one. No credentials are embedded.
 */
public class LoginActivity extends Activity {
    static final String REDIRECT = "https://oauth.vk.ru/blank.html";

    private Store store;
    private LinearLayout root;
    private WebView web;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        showForm();
    }

    private void showForm() {
        destroyWeb();
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(this);
        help.setText("Вход через ваше приложение VK\n\n"
                + "1. Создайте Standalone-приложение на vk.com/apps?act=manage и включите Implicit Flow.\n"
                + "2. Укажите у него Redirect URI: " + REDIRECT + "\n"
                + "3. Введите ID приложения ниже и войдите в свой аккаунт.");
        root.addView(help);

        final EditText appId = new EditText(this);
        appId.setId(1);
        appId.setHint("ID приложения VK");
        appId.setInputType(InputType.TYPE_CLASS_NUMBER);
        appId.setText(store.appId());
        root.addView(appId);

        Button login = new Button(this);
        login.setId(2);
        login.setText("Войти");
        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String id = appId.getText().toString().trim();
                if (!id.matches("[0-9]{1,12}")) {
                    toast("Введите числовой ID приложения");
                    return;
                }
                store.saveAppId(id);
                startOauth(id);
            }
        });
        root.addView(login);

        setContentView(root);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startOauth(String appId) {
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.clearCache(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handle(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                handle(url);
            }
        });
        setContentView(web);
        web.loadUrl("https://oauth.vk.ru/authorize?client_id=" + appId
                + "&display=mobile&scope=messages,offline&redirect_uri=" + Uri.encode(REDIRECT)
                + "&response_type=token&revoke=1&v=" + Api.VERSION);
    }

    /** @return true when the URL was the OAuth result and was consumed. */
    boolean handle(String url) {
        if (url == null || !url.startsWith(REDIRECT)) {
            return false;
        }
        String fragment = Uri.parse(url.replace('#', '?')).getEncodedQuery();
        Uri data = Uri.parse("x://x?" + (fragment == null ? "" : fragment));
        String token = data.getQueryParameter("access_token");
        String userId = data.getQueryParameter("user_id");
        if (token != null && userId != null) {
            store.saveSession(token, Long.parseLong(userId));
            setResult(RESULT_OK);
            finish();
        } else {
            String error = data.getQueryParameter("error_description");
            if (error == null) {
                error = data.getQueryParameter("error");
            }
            toast("Вход не выполнен: " + error);
            showForm();
        }
        return true;
    }

    private void destroyWeb() {
        if (web != null) {
            web.destroy();
            web = null;
        }
    }

    @Override
    protected void onDestroy() {
        destroyWeb();
        super.onDestroy();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
