package com.example.vpnapp;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.SystemBarStyle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

/** Встроенный браузер для проверки: все запросы идут через SOCKS-прокси Tor. */
public class BrowserActivity extends AppCompatActivity {

    private static final String CHECK_URL = "https://check.torproject.org/";

    private WebView web;
    private boolean proxySet;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this,
                SystemBarStyle.dark(Color.TRANSPARENT),
                SystemBarStyle.dark(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        web = findViewById(R.id.web);
        web.setBackgroundColor(Color.BLACK);
        web.setWebViewClient(new WebViewClient());
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack();
                } else {
                    finish();
                }
            }
        });

        int port = StatusBus.current().socksPort;
        if (port <= 0) {
            showMessage(R.string.browser_not_connected);
            return;
        }
        // Без поддержки прокси страницу не грузим: иначе запрос уйдёт мимо Tor
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            showMessage(R.string.browser_no_proxy);
            return;
        }
        ProxyConfig config = new ProxyConfig.Builder()
                .addProxyRule("socks5://127.0.0.1:" + port)
                .build();
        ProxyController.getInstance().setProxyOverride(config,
                ContextCompat.getMainExecutor(this), () -> {
                    proxySet = true;
                    if (!isDestroyed()) web.loadUrl(CHECK_URL);
                });
    }

    @Override
    protected void onDestroy() {
        if (proxySet && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(Runnable::run, () -> { });
        }
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }

    private void showMessage(int res) {
        web.setVisibility(View.GONE);
        TextView message = findViewById(R.id.message);
        message.setText(res);
        message.setVisibility(View.VISIBLE);
    }
}
