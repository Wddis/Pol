package com.example.vpnapp;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Единственный экран: выбор страны, кнопка, статус. */
public class MainActivity extends AppCompatActivity {

    // Этап 1: подключение имитируется задержкой, сеть не используется
    private static final long DEMO_CONNECT_MS = 2500;
    private static final long SCREEN_FADE_MS = 500;
    private static final long TEXT_FADE_MS = 150;
    private static final String KEY_STATE = "state";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable demoConnected = () -> setState(ConnectionState.ON, true);

    private Prefs prefs;
    private ConnectionState state = ConnectionState.OFF;

    private PowerButtonView powerButton;
    private TextView statusView;
    private TextView detailsView;
    private TextView errorView;
    private TextView countryCodeView;
    private TextView countryNameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this,
                SystemBarStyle.dark(Color.TRANSPARENT),
                SystemBarStyle.dark(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        View root = findViewById(R.id.root);
        powerButton = findViewById(R.id.powerButton);
        statusView = findViewById(R.id.status);
        detailsView = findViewById(R.id.details);
        errorView = findViewById(R.id.error);
        countryCodeView = findViewById(R.id.countryCode);
        countryNameView = findViewById(R.id.countryName);

        applyInsets(root);
        showCountry();

        findViewById(R.id.countryRow).setOnClickListener(v -> openPicker());
        powerButton.setOnClickListener(v -> {
            haptic(v);
            onPowerClick();
        });

        getSupportFragmentManager().setFragmentResultListener(
                CountryPickerSheet.REQUEST_KEY, this, (key, result) -> {
                    String code = result.getString(CountryPickerSheet.RESULT_CODE);
                    if (code != null) onCountryPicked(code);
                });

        if (savedInstanceState != null) {
            String saved = savedInstanceState.getString(KEY_STATE);
            ConnectionState restored = saved != null
                    ? ConnectionState.valueOf(saved) : ConnectionState.OFF;
            setState(restored, false);
            if (restored == ConnectionState.CONNECTING) startDemoConnect();
        } else {
            setState(ConnectionState.OFF, false);
            // Плавное появление экрана
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(SCREEN_FADE_MS)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_STATE, state.name());
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void onPowerClick() {
        switch (state) {
            case OFF:
            case ERROR:
                startDemoConnect();
                break;
            case CONNECTING:
            case ON:
                handler.removeCallbacks(demoConnected);
                setState(ConnectionState.OFF, true);
                break;
        }
    }

    private void onCountryPicked(String code) {
        if (code.equals(prefs.getCountry())) return;
        prefs.setCountry(code);
        showCountry();
        // При включённом режиме смена страны = переподключение
        if (state == ConnectionState.ON || state == ConnectionState.CONNECTING) {
            startDemoConnect();
        }
    }

    private void startDemoConnect() {
        handler.removeCallbacks(demoConnected);
        setState(ConnectionState.CONNECTING, true);
        handler.postDelayed(demoConnected, DEMO_CONNECT_MS);
    }

    private void setState(ConnectionState newState, boolean animate) {
        state = newState;
        powerButton.setState(newState, animate);

        CharSequence status;
        CharSequence details = "";
        switch (newState) {
            case CONNECTING:
                status = getString(R.string.status_connecting);
                break;
            case ON:
                status = getString(R.string.status_on);
                details = getString(R.string.demo_mode);
                break;
            case ERROR:
                status = getString(R.string.status_error);
                break;
            default:
                status = getString(R.string.status_off);
        }
        setText(statusView, status, animate);
        setText(detailsView, details, animate);
        if (newState != ConnectionState.ERROR) setText(errorView, "", animate);
    }

    private void showCountry() {
        Country country = Country.find(this, prefs.getCountry());
        countryCodeView.setText(country.code);
        countryNameView.setText(country.name);
    }

    private void openPicker() {
        if (getSupportFragmentManager().findFragmentByTag(CountryPickerSheet.TAG) != null) return;
        CountryPickerSheet.newInstance(prefs.getCountry())
                .show(getSupportFragmentManager(), CountryPickerSheet.TAG);
    }

    /** Смена текста с коротким затуханием. Целевой текст хранится в tag. */
    private void setText(TextView view, CharSequence text, boolean animate) {
        if (TextUtils.equals((CharSequence) view.getTag(), text)) return;
        view.setTag(text);
        view.animate().cancel();
        if (!animate) {
            view.setText(text);
            view.setAlpha(1f);
            return;
        }
        view.animate().alpha(0f).setDuration(TEXT_FADE_MS).withEndAction(() -> {
            view.setText(text);
            view.animate().alpha(1f).setDuration(TEXT_FADE_MS)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }).start();
    }

    private void applyInsets(View root) {
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /** Лёгкий тактильный отклик без разрешения VIBRATE. */
    private static void haptic(View v) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.VIRTUAL_KEY;
        v.performHapticFeedback(type);
    }
}
