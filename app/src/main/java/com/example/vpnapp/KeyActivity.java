package com.example.vpnapp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.content.res.ColorStateList;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.SystemBarStyle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Экран ключа — пока нет действующего ключа, дальше него в ODA не пройти:
 * «Ключ доступа» (ввод), «Тариф закончился», «Доступ заблокирован» (причина, срок, поддержка),
 * «Нужна проверка ключа» (давно не было связи с сервером ключей).
 * При открытии в состояниях «закончился», «заблокирован», «нужна проверка» сервер спрашивается сам —
 * продлили ключ в боте, вернулись в ODA, и экран пропал.
 */
public class KeyActivity extends AppCompatActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private KeyManager keys;
    private KeyManager.Gate shown;
    private boolean busy;
    /** Пользователь выбрал «Ввести другой ключ» на экране «закончился» или «заблокирован». */
    private boolean forceEnter;

    private TextView headline;
    private TextView body;
    private EditText input;
    private TextView primary;
    private TextView secondary;
    private TextView message;
    private View trialHint;
    private View getKey;

    static Intent intent(Context ctx) {
        return new Intent(ctx, KeyActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this, SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_key);
        keys = KeyManager.get(this);
        DiagLog.init(this);

        headline = findViewById(R.id.headline);
        body = findViewById(R.id.body);
        input = findViewById(R.id.keyInput);
        primary = findViewById(R.id.primary);
        secondary = findViewById(R.id.secondary);
        message = findViewById(R.id.message);
        trialHint = findViewById(R.id.trialHint);
        getKey = findViewById(R.id.getKey);

        View content = findViewById(R.id.content);
        final int top = content.getPaddingTop();
        final int bottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            content.setPadding(content.getPaddingLeft(), top + bars.top, content.getPaddingRight(), bottom + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        input.addTextChangedListener(new KeyFormatter(input));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                activate();
                return true;
            }
            return false;
        });
        getKey.setOnClickListener(v -> OdaSheet.openBot(this, "buy"));
        findViewById(R.id.howBuy).setOnClickListener(v -> OdaSheet.open(this, OdaSheet.BUY));
        findViewById(R.id.support).setOnClickListener(v -> OdaSheet.openBot(this, "support"));
        findViewById(R.id.logLink).setOnClickListener(v -> {
            if (getSupportFragmentManager().findFragmentByTag(LogSheet.TAG) == null) {
                new LogSheet().show(getSupportFragmentManager(), LogSheet.TAG);
            }
        });
        // Назад — выход из приложения (без ключа главного экрана нет)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finishAffinity();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeyManager.Gate g = keys.gate();
        if (g == KeyManager.Gate.OK) {
            openMain();
            return;
        }
        render(g);
        // Продлили или разблокировали в боте — проверить сразу, без нажатия
        if (g != KeyManager.Gate.NONE && !forceEnter) check();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Ключ скопирован в боте — вставить сам (буфер доступен, только когда окно в фокусе)
        if (hasFocus && input.getVisibility() == View.VISIBLE && input.length() == 0) pasteFromClipboard();
    }

    private void render(KeyManager.Gate g) {
        shown = forceEnter ? KeyManager.Gate.NONE : g;
        boolean enter = shown == KeyManager.Gate.NONE;
        input.setVisibility(enter ? View.VISIBLE : View.GONE);
        trialHint.setVisibility(enter ? View.VISIBLE : View.GONE);
        getKey.setVisibility(enter || shown == KeyManager.Gate.EXPIRED ? View.VISIBLE : View.GONE);
        secondary.setVisibility(View.VISIBLE);
        boolean banned = shown == KeyManager.Gate.BANNED;
        ImageView icon = findViewById(R.id.keyIcon);
        icon.setImageResource(banned ? R.drawable.ic_lock : shown == KeyManager.Gate.EXPIRED ? R.drawable.ic_hourglass : R.drawable.ic_key);
        icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, banned ? R.color.danger : R.color.accent)));
        findViewById(R.id.keyIconBg).setBackgroundResource(banned ? R.drawable.bg_circle_danger : R.drawable.bg_circle_accent);
        switch (shown) {
            case EXPIRED:
                headline.setText(R.string.key_title_expired);
                body.setText(getString(R.string.key_text_expired, keys.key() != null ? keys.key() : ""));
                primary.setText(R.string.key_renew);
                primary.setOnClickListener(v -> OdaSheet.openBot(this, "renew"));
                secondary.setText(R.string.key_recheck);
                secondary.setOnClickListener(v -> check());
                ((TextView) getKey).setText(R.string.key_other);
                getKey.setOnClickListener(v -> enterOther());
                break;
            case BANNED:
                long until = keys.banUntil();
                String when = until < 0 ? getString(R.string.key_ban_forever)
                        : getString(R.string.key_ban_until, new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(new Date(until * 1000)));
                headline.setText(R.string.key_title_banned);
                body.setText(getString(R.string.key_text_banned, keys.banReason(), when));
                primary.setText(R.string.key_write_support);
                primary.setOnClickListener(v -> OdaSheet.openBot(this, "support"));
                secondary.setText(R.string.key_recheck);
                secondary.setOnClickListener(v -> check());
                break;
            case NEED_CHECK:
                headline.setText(R.string.key_title_check);
                body.setText(R.string.key_text_check);
                primary.setText(R.string.key_recheck);
                primary.setOnClickListener(v -> check());
                secondary.setText(R.string.key_other);
                secondary.setOnClickListener(v -> enterOther());
                break;
            default:
                headline.setText(R.string.key_title_enter);
                body.setText(R.string.key_text_enter);
                primary.setText(R.string.key_activate);
                primary.setOnClickListener(v -> activate());
                secondary.setVisibility(View.GONE);
                ((TextView) getKey).setText(R.string.key_get);
                getKey.setOnClickListener(v -> OdaSheet.openBot(this, "buy"));
                // Ключ удалили, отвязали устройство — почему просим ключ снова
                if (message.length() == 0 && !keys.serverMessage().isEmpty()) message.setText(keys.serverMessage());
        }
    }

    private void enterOther() {
        forceEnter = true;
        message.setText("");
        render(keys.gate());
        input.requestFocus();
    }

    private void activate() {
        if (busy) return;
        String text = input.getText().toString();
        message.setTextColor(ContextCompat.getColor(this, R.color.danger));
        if (KeyManager.normalize(text) == null) {
            message.setText(R.string.key_error_format);
            return;
        }
        run(() -> keys.activate(text));
    }

    private void check() {
        if (busy) return;
        int port = StatusBus.current().socksPort;
        run(() -> keys.check(null, "", port));
    }

    private interface Call {
        KeyManager.Result call();
    }

    private void run(Call call) {
        busy = true;
        CharSequence label = primary.getText();
        primary.setEnabled(false);
        secondary.setEnabled(false);
        primary.setAlpha(0.6f);
        primary.setText(R.string.key_checking);
        message.setText("");
        new Thread(() -> {
            KeyManager.Result r = call.call();
            main.post(() -> {
                busy = false;
                if (isFinishing() || isDestroyed()) return;
                primary.setEnabled(true);
                secondary.setEnabled(true);
                primary.setAlpha(1f);
                primary.setText(label);
                KeyManager.Gate g = keys.gate();
                if (r.ok && g == KeyManager.Gate.OK) {
                    Toast.makeText(this, R.string.key_ok, Toast.LENGTH_SHORT).show();
                    openMain();
                    return;
                }
                // Блокировку видно всегда — и при вводе другого ключа
                if (g == KeyManager.Gate.BANNED) forceEnter = false;
                render(g);
                message.setTextColor(ContextCompat.getColor(this, R.color.danger));
                message.setText(r.message);
            });
        }, "oda-key").start();
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).getText();
        String key = text == null ? null : KeyManager.normalize(text.toString());
        if (key == null) return;
        input.setText(key);
        input.setSelection(input.length());
        message.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        message.setText(R.string.key_paste_found);
    }

    /** Ввод как угодно → «ODA-XXXX-XXXX-XXXX» на лету: заглавные, дефисы, без лишних символов. */
    private static final class KeyFormatter implements TextWatcher {
        private final EditText field;
        private boolean editing;

        KeyFormatter(EditText field) {
            this.field = field;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (editing) return;
            String raw = s.toString().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            // Начало «ODA» набирают по букве: «O», «OD» — ещё не ключ, оставить как есть
            if (raw.equals("O") || raw.equals("OD")) return;
            // Буквы O в ключе нет, так что «O…»/«OD…» в начале — недонабранное «ODA»
            raw = raw.replaceFirst("^(ODA|OD|O)", "");
            if (raw.length() > 12) raw = raw.substring(0, 12);
            StringBuilder out = new StringBuilder("ODA");
            for (int i = 0; i < raw.length(); i++) {
                if (i % 4 == 0) out.append('-');
                out.append(raw.charAt(i));
            }
            String formatted = raw.isEmpty() && s.length() == 0 ? "" : out.toString();
            if (!formatted.contentEquals(s)) {
                editing = true;
                s.replace(0, s.length(), formatted);
                editing = false;
                field.setSelection(field.length());
            }
        }
    }
}
