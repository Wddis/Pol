package com.example.vpnapp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Полноэкранная страница (0.11, макет владельца): «Ключ доступа» — карточка с ключом, тарифом, сроком,
 * устройствами, проверкой и кнопки «Продлить в Telegram», «Копировать», «Обновить», «Сменить ключ»;
 * справка — как купить, инструкция, частые вопросы, конфиденциальность, о приложении.
 */
public class PageActivity extends AppCompatActivity {

    static final String EXTRA_PAGE = "page";

    private final Handler main = new Handler(Looper.getMainLooper());
    private KeyManager keys;
    private LinearLayout card;
    private LinearLayout buttons;
    private TextView body;

    static Intent intent(Context ctx, String page) {
        return new Intent(ctx, PageActivity.class).putExtra(EXTRA_PAGE, page);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this, SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_page);
        keys = KeyManager.get(this);
        card = findViewById(R.id.card);
        buttons = findViewById(R.id.buttons);
        body = findViewById(R.id.body);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        Motion.reveal((ViewGroup) findViewById(R.id.content));
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private String page() {
        String p = getIntent().getStringExtra(EXTRA_PAGE);
        return p != null ? p : OdaSheet.KEY;
    }

    private void build() {
        card.removeAllViews();
        buttons.removeAllViews();
        card.setVisibility(View.GONE);
        body.setVisibility(View.GONE);
        switch (page()) {
            case OdaSheet.BUY:
                info(R.string.menu_buy, getString(R.string.info_buy), "buy");
                break;
            case OdaSheet.HOWTO:
                info(R.string.menu_howto, getString(R.string.info_howto), null);
                break;
            case OdaSheet.FAQ:
                info(R.string.menu_faq, getString(R.string.info_faq), "support");
                break;
            case OdaSheet.PRIVACY:
                info(R.string.menu_privacy, getString(R.string.info_privacy), null);
                break;
            case OdaSheet.ABOUT:
                info(R.string.menu_about, getString(R.string.info_about, OdaSheet.appVersion(this)).replace("\n", "<br>"), "");
                break;
            default:
                buildKey();
        }
    }

    // ---------- ключ ----------

    private void buildKey() {
        title(R.string.keyinfo_title, R.string.page_key_sub);
        String key = keys.key();
        if (key == null || keys.gate() == KeyManager.Gate.NONE) {
            // Ключ сменили или удалили — на экран ключа
            startActivity(KeyActivity.intent(this));
            finish();
            return;
        }
        card.setVisibility(View.VISIBLE);
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT);
        long left = keys.secondsLeft();
        View k = row(R.drawable.ic_key, true, R.string.keyinfo_key, key);
        ImageView copy = k.findViewById(R.id.action);
        copy.setImageResource(R.drawable.ic_copy);
        copy.setContentDescription(getString(R.string.cd_copy));
        copy.setVisibility(View.VISIBLE);
        copy.setOnClickListener(v -> copy(key));
        if (!keys.tariffTitle().isEmpty()) row(R.drawable.ic_history, false, R.string.keyinfo_tariff, keys.tariffTitle());
        row(R.drawable.ic_hourglass, false, R.string.keyinfo_left, left > 0 ? keys.leftText() : getString(R.string.key_title_expired));
        if (!keys.forever() && keys.expiresAt() > 0) {
            row(R.drawable.ic_calendar, false, R.string.keyinfo_until, fmt.format(new Date(keys.expiresAt() * 1000)));
        }
        if (keys.devicesMax() > 0) {
            row(R.drawable.ic_devices, false, R.string.keyinfo_devices,
                    getString(R.string.keyinfo_devices_value, keys.devicesUsed(), keys.devicesMax()));
        }
        row(R.drawable.ic_phone, false, R.string.keyinfo_device, KeyManager.deviceName());
        View checked = row(R.drawable.ic_shield_check, true, R.string.keyinfo_checked,
                keys.lastOk() > 0 ? fmt.format(new Date(keys.lastOk() * 1000)) : getString(R.string.keyinfo_checked_never));
        View dot = checked.findViewById(R.id.dot);
        dot.setVisibility(View.VISIBLE);
        boolean fresh = keys.lastOk() > 0 && keys.now() - keys.lastOk() < 2 * KeyManager.CHECK_EVERY;
        dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, fresh ? R.color.accent : R.color.idle)));

        button(R.string.keyinfo_renew, R.drawable.ic_send, true, v -> OdaSheet.openBot(this, "renew"));
        button(R.string.keyinfo_copy, R.drawable.ic_copy, false, v -> copy(key));
        button(R.string.keyinfo_refresh, R.drawable.ic_refresh, false, v -> refresh((MaterialButton) v));
        button(R.string.keyinfo_change, R.drawable.ic_close, false, v -> confirmChange());
    }

    private View row(@DrawableRes int icon, boolean accent, int label, String value) {
        View r = getLayoutInflater().inflate(R.layout.item_info, card, false);
        ImageView iv = r.findViewById(R.id.icon);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, accent ? R.color.accent : R.color.text_secondary)));
        r.findViewById(R.id.iconBg).setBackgroundResource(accent ? R.drawable.bg_circle_accent : R.drawable.bg_circle);
        ((TextView) r.findViewById(R.id.label)).setText(label);
        ((TextView) r.findViewById(R.id.value)).setText(value);
        card.addView(r);
        return r;
    }

    private void refresh(MaterialButton b) {
        b.setEnabled(false);
        b.setAlpha(0.5f);
        b.setText(R.string.key_checking);
        Context app = getApplicationContext();
        int port = StatusBus.current().socksPort;
        new Thread(() -> {
            KeyManager.Result r = keys.check(null, "", port);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(app, r.ok ? getString(R.string.key_checked) : r.message, Toast.LENGTH_LONG).show();
                if (keys.gate() != KeyManager.Gate.OK) {
                    startActivity(KeyActivity.intent(this));
                    finish();
                    return;
                }
                build();
            });
        }, "oda-key-refresh").start();
    }

    private void confirmChange() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.keyinfo_change_title)
                .setMessage(R.string.keyinfo_change_text)
                .setPositiveButton(R.string.keyinfo_change_ok, (d, w) -> changeKey())
                .setNegativeButton(R.string.keyinfo_cancel, null)
                .show();
    }

    private void changeKey() {
        DiagLog.add("Ключ: смена ключа пользователем");
        keys.forget();
        try {
            startService(new Intent(this, VpnTunnelService.class).setAction(VpnTunnelService.ACTION_STOP));
        } catch (RuntimeException ignored) {
            // сервис не запущен
        }
        startActivity(KeyActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private void copy(String key) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("ODA", key));
        Toast.makeText(this, R.string.keyinfo_copied, Toast.LENGTH_SHORT).show();
    }

    // ---------- справка ----------

    /** botStart != null — кнопка «Открыть бота» (раздел бота; "" — главное меню). */
    private void info(int titleRes, String html, @Nullable String botStart) {
        title(titleRes, R.string.page_info_sub);
        body.setVisibility(View.VISIBLE);
        body.setText(Html.fromHtml(html.trim(), Html.FROM_HTML_MODE_COMPACT));
        if (botStart != null) button(R.string.info_open_bot, R.drawable.ic_send, true, v -> OdaSheet.openBot(this, botStart));
    }

    private void title(int title, int sub) {
        ((TextView) findViewById(R.id.pageTitle)).setText(title);
        ((TextView) findViewById(R.id.pageSub)).setText(sub);
    }

    private void button(int text, @DrawableRes int icon, boolean primary, View.OnClickListener click) {
        MaterialButton b = (MaterialButton) getLayoutInflater().inflate(
                primary ? R.layout.item_button_primary : R.layout.item_button_outline, buttons, false);
        b.setText(text);
        b.setIconResource(icon);
        b.setOnClickListener(click);
        buttons.addView(b);
    }
}
