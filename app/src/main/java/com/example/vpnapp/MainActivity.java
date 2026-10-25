package com.example.vpnapp;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;

/** Единственный экран: выбор страны, кнопка, статус. Состояние приходит из StatusBus. */
public class MainActivity extends AppCompatActivity implements StatusBus.Listener {

    private static final long SCREEN_FADE_MS = 500;
    private static final long TEXT_FADE_MS = 150;

    private Prefs prefs;
    private ConnectionState state = ConnectionState.OFF;
    private boolean firstStatus = true;

    private PowerButtonView powerButton;
    private TextView statusView;
    private TextView detailsView;
    private TextView errorView;
    private TextView checkLink;
    private TextView bridgesLink;
    private TextView countryCodeView;
    private TextView countryNameView;

    // Системный диалог «Разрешить VPN»
    private final ActivityResultLauncher<Intent> vpnPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startVpn();
                } else {
                    showLocalError(R.string.error_vpn_denied);
                }
            });

    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> { });

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
        checkLink = findViewById(R.id.checkLink);
        bridgesLink = findViewById(R.id.bridgesLink);
        countryCodeView = findViewById(R.id.countryCode);
        countryNameView = findViewById(R.id.countryName);

        applyInsets(root);
        showCountry();
        showBridgeMode();

        findViewById(R.id.countryRow).setOnClickListener(v -> openPicker());
        powerButton.setOnClickListener(v -> {
            haptic(v);
            onPowerClick();
        });
        checkLink.setOnClickListener(v -> startActivity(new Intent(this, BrowserActivity.class)));
        findViewById(R.id.killSwitch).setOnClickListener(v -> openKillSwitchSettings());
        bridgesLink.setOnClickListener(v -> openBridges());

        getSupportFragmentManager().setFragmentResultListener(
                CountryPickerSheet.REQUEST_KEY, this, (key, result) -> {
                    String code = result.getString(CountryPickerSheet.RESULT_CODE);
                    if (code != null) onCountryPicked(code);
                });
        getSupportFragmentManager().setFragmentResultListener(
                BridgesSheet.REQUEST_KEY, this, (key, result) -> {
                    showBridgeMode();
                    // Новый режим мостов при включённом VPN — переподключение
                    boolean changed = result.getBoolean(BridgesSheet.RESULT_CHANGED);
                    if (changed && (state == ConnectionState.ON || state == ConnectionState.CONNECTING)) {
                        sendToService(VpnTunnelService.ACTION_RECONNECT);
                    }
                });

        if (savedInstanceState == null) {
            // Плавное появление экрана
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(SCREEN_FADE_MS)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        firstStatus = true;
        StatusBus.addListener(this);
    }

    @Override
    protected void onStop() {
        StatusBus.removeListener(this);
        super.onStop();
    }

    @Override
    public void onStatus(@NonNull StatusBus.Status status) {
        // Первый снимок после открытия экрана — без анимации
        boolean animate = !firstStatus;
        firstStatus = false;
        render(status.state, status.detail, status.error, animate);
    }

    private void onPowerClick() {
        switch (state) {
            case OFF:
            case ERROR:
                requestVpn();
                break;
            case CONNECTING:
            case ON:
                sendToService(VpnTunnelService.ACTION_STOP);
                render(ConnectionState.OFF, "", "", true);
                break;
        }
    }

    private void onCountryPicked(String code) {
        if (code.equals(prefs.getCountry())) return;
        prefs.setCountry(code);
        showCountry();
        // При включённом режиме смена страны = переподключение
        if (state == ConnectionState.ON || state == ConnectionState.CONNECTING) {
            sendToService(VpnTunnelService.ACTION_COUNTRY);
        }
    }

    private void requestVpn() {
        Intent prepare;
        try {
            prepare = VpnService.prepare(this);
        } catch (RuntimeException e) {
            showLocalError(R.string.error_vpn_other);
            return;
        }
        if (prepare == null) {
            startVpn();
            return;
        }
        try {
            vpnPermission.launch(prepare);
        } catch (ActivityNotFoundException e) {
            showLocalError(R.string.error_vpn_other);
        }
    }

    private void startVpn() {
        render(ConnectionState.CONNECTING, getString(R.string.detail_bootstrap, 0), "", true);
        sendToService(VpnTunnelService.ACTION_START);
        askNotificationPermission();
    }

    private void sendToService(String action) {
        Intent i = new Intent(this, VpnTunnelService.class).setAction(action);
        try {
            if (VpnTunnelService.ACTION_STOP.equals(action)) {
                startService(i);
            } else {
                ContextCompat.startForegroundService(this, i);
            }
        } catch (RuntimeException e) {
            showLocalError(R.string.error_foreground);
        }
    }

    private void showLocalError(int res) {
        render(ConnectionState.ERROR, "", getString(res), true);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /** Kill switch на уровне системы: «Постоянная VPN» + «Блокировать без VPN». */
    private void openKillSwitchSettings() {
        Toast.makeText(this, R.string.kill_switch_hint, Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void render(ConnectionState newState, String detail, String error, boolean animate) {
        state = newState;
        powerButton.setState(newState, animate);

        int status;
        switch (newState) {
            case CONNECTING:
                status = R.string.status_connecting;
                break;
            case ON:
                status = R.string.status_on;
                break;
            default:
                status = R.string.status_off;
        }
        setText(statusView, getString(status), animate);
        setText(detailsView, detail, animate);
        setText(errorView, newState == ConnectionState.ERROR ? error : "", animate);
        checkLink.setVisibility(newState == ConnectionState.ON ? View.VISIBLE : View.INVISIBLE);
    }

    private void showCountry() {
        Country country = Country.find(this, prefs.getCountry());
        countryCodeView.setText(country.code);
        countryNameView.setText(country.name);
    }

    private void showBridgeMode() {
        String mode = getString(prefs.getMode().title).toUpperCase(Locale.ROOT);
        bridgesLink.setText(getString(R.string.link_bridges, mode));
    }

    private void openBridges() {
        if (getSupportFragmentManager().findFragmentByTag(BridgesSheet.TAG) != null) return;
        new BridgesSheet().show(getSupportFragmentManager(), BridgesSheet.TAG);
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
