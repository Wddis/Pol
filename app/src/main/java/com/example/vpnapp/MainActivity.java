package com.example.vpnapp;

import android.Manifest;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
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

    /** Открыть экран и сразу подключиться (плитка в шторке, когда сама подключить не может). */
    public static final String EXTRA_CONNECT = "com.example.vpnapp.extra.CONNECT";

    private static final long SCREEN_FADE_MS = 500;
    private static final long TEXT_FADE_MS = 150;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = this::tick;

    private Prefs prefs;
    private ConnectionState state = ConnectionState.OFF;
    private long connectedAt;
    private boolean firstStatus = true;
    private boolean started;
    private boolean speedRunning;

    private PowerButtonView powerButton;
    private TextView statusView;
    private TextView detailsView;
    private TextView errorView;
    private TextView timerView;
    private TextView speedLink;
    private TextView speedResult;
    private TextView bridgesLink;
    private TextView appsValue;
    private TextView countryCodeView;
    private TextView countryNameView;
    private TextView keyChip;
    private TextView keyBanner;
    private KeyManager keys;
    private boolean keyChecking;

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
        keys = KeyManager.get(this);
        DiagLog.init(this);
        // Без действующего ключа — сразу экран ключа (главный экран даже не строится)
        if (keys.gate() != KeyManager.Gate.OK) {
            startActivity(KeyActivity.intent(this));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        ViewGroup root = findViewById(R.id.root);
        powerButton = findViewById(R.id.powerButton);
        statusView = findViewById(R.id.status);
        detailsView = findViewById(R.id.details);
        errorView = findViewById(R.id.error);
        timerView = findViewById(R.id.timer);
        speedLink = findViewById(R.id.speedLink);
        speedResult = findViewById(R.id.speedResult);
        bridgesLink = findViewById(R.id.bridgesLink);
        appsValue = findViewById(R.id.appsValue);
        countryCodeView = findViewById(R.id.countryCode);
        countryNameView = findViewById(R.id.countryName);
        keyChip = findViewById(R.id.keyChip);
        keyBanner = findViewById(R.id.keyBanner);
        keyChip.setOnClickListener(v -> OdaSheet.showPage(getSupportFragmentManager(), OdaSheet.KEY));
        keyBanner.setOnClickListener(v -> OdaSheet.openBot(this, "renew"));
        findViewById(R.id.menuButton).setOnClickListener(v -> OdaSheet.showPage(getSupportFragmentManager(), OdaSheet.MENU));

        applyInsets(root);
        // Появление/исчезновение секундомера, теста скорости и строки страны — плавно
        Motion.animateLayout(root);
        Motion.animateLayout(findViewById(R.id.links));
        showCountry();
        showBridgeMode();
        showApps();

        findViewById(R.id.countryRow).setOnClickListener(v -> openPicker());
        findViewById(R.id.appsRow).setOnClickListener(v -> openApps());
        powerButton.setOnClickListener(v -> {
            haptic(v);
            onPowerClick();
        });
        // Журнал доступен всегда: и до, и после подключения
        findViewById(R.id.logLink).setOnClickListener(v -> openLog());
        speedLink.setOnClickListener(v -> runSpeedTest());
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
        getSupportFragmentManager().setFragmentResultListener(
                AppsSheet.REQUEST_KEY, this, (key, result) -> {
                    showApps();
                    // При включённом VPN список применяется сразу: пересоздаётся только интерфейс
                    if (state == ConnectionState.ON || state == ConnectionState.CONNECTING) {
                        sendToService(VpnTunnelService.ACTION_APPS);
                    }
                });

        if (savedInstanceState == null) {
            // Плавное появление экрана
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(SCREEN_FADE_MS)
                    .setInterpolator(new DecelerateInterpolator()).start();
            handleConnectIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleConnectIntent(intent);
    }

    /** Плитка в шторке попросила подключиться (нет разрешения на VPN или сервис не запустился). */
    private void handleConnectIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_CONNECT, false)) return;
        intent.removeExtra(EXTRA_CONNECT);
        if (!keyAllowsMain()) return;
        ConnectionState now = StatusBus.current().state;
        if (now == ConnectionState.OFF || now == ConnectionState.ERROR) requestVpn();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        firstStatus = true;
        StatusBus.addListener(this);
        if (!keyAllowsMain()) return;
        renderKey();
        keys.maybeWarn();
        if (keys.checkDue()) checkKeyInBackground();
    }

    /** Ключ изменился (проверка, смена ключа): нет доступа — экран ключа, иначе обновить срок. */
    void onKeyChanged() {
        if (keyAllowsMain()) renderKey();
    }

    /** Без действующего ключа главного экрана нет: открывается экран ключа. */
    private boolean keyAllowsMain() {
        if (isFinishing()) return false;
        KeyManager.Gate g = keys.gate();
        if (g == KeyManager.Gate.OK) return true;
        startActivity(KeyActivity.intent(this));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        return false;
    }

    /** Срок ключа вверху и полоса «заканчивается» за 2 дня. */
    private void renderKey() {
        String left = keys.leftText();
        keyChip.setText(getString(R.string.key_chip, left.toUpperCase(Locale.getDefault())));
        boolean soon = !keys.forever() && keys.secondsLeft() <= 2 * 86400L;
        keyBanner.setText(getString(R.string.key_banner, left));
        keyBanner.setVisibility(soon ? View.VISIBLE : View.GONE);
    }

    /** Проверка ключа при открытии, если давно не проверяли (продлили, заблокировали, удалили в боте). */
    private void checkKeyInBackground() {
        if (keyChecking) return;
        keyChecking = true;
        int port = StatusBus.current().socksPort;
        new Thread(() -> {
            keys.check(null, "", port);
            main.post(() -> {
                keyChecking = false;
                if (!isDestroyed() && started) onKeyChanged();
            });
        }, "oda-key-check").start();
    }

    @Override
    protected void onStop() {
        started = false;
        main.removeCallbacks(ticker);
        StatusBus.removeListener(this);
        super.onStop();
    }

    @Override
    public void onStatus(@NonNull StatusBus.Status status) {
        // Первый снимок после открытия экрана — без анимации
        boolean animate = !firstStatus;
        firstStatus = false;
        connectedAt = status.connectedAt;
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
                connectedAt = 0;
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
        if (!keyAllowsMain()) return;
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
        render(ConnectionState.CONNECTING, getString(R.string.detail_starting), "", true);
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
        boolean becameOn = newState == ConnectionState.ON && state != ConnectionState.ON;
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

        boolean on = newState == ConnectionState.ON;
        speedLink.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) speedResult.setVisibility(View.GONE);
        // Секундомер — и в коротком «Подключение…» после подключения (переход способа), с прежнего времени
        timerView.setVisibility(connectedAt > 0 ? View.VISIBLE : View.GONE);
        main.removeCallbacks(ticker);
        if (connectedAt > 0 && started) tick();
        if (becameOn && animate) offerTile();
    }

    /** Секундомер: обновление ровно на смене секунды. */
    private void tick() {
        if (connectedAt <= 0) return;
        long ms = SystemClock.elapsedRealtime() - connectedAt;
        timerView.setText(formatElapsed(ms / 1000));
        main.postDelayed(ticker, 1000 - ms % 1000);
    }

    /** «05:42», после часа — «1:05:42». */
    static String formatElapsed(long seconds) {
        long h = seconds / 3600;
        long m = seconds / 60 % 60;
        long s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    /**
     * Тест скорости через тот способ, которым подключены (его SOCKS) — см. {@link SpeedTest}.
     * Итог — под кнопкой и в журнале.
     */
    private void runSpeedTest() {
        StatusBus.Status s = StatusBus.current();
        if (speedRunning || s.state != ConnectionState.ON || s.socksPort <= 0) return;
        speedRunning = true;
        speedLink.setText(R.string.link_speed_running);
        speedLink.setEnabled(false);
        speedResult.setVisibility(View.VISIBLE);
        setText(speedResult, getString(R.string.speed_running), true);
        String engine = s.engine;
        int port = s.socksPort;
        new Thread(() -> {
            SpeedTest.Result r = SpeedTest.run(port);
            DiagLog.add("Тест скорости (" + engine + "): " + r.summary() + ", принято "
                    + r.bytes / 1024 + " КБ" + (r.error != null ? " · не прошло: " + r.error : ""));
            main.post(() -> {
                speedRunning = false;
                if (isDestroyed()) return;
                speedLink.setText(R.string.link_speed);
                speedLink.setEnabled(true);
                if (state != ConnectionState.ON) return;
                String text = r.pingMs < 0 && r.mbps < 0
                        ? getString(R.string.speed_failed) : r.summary();
                setText(speedResult, text, true);
            });
        }, "oda-speed").start();
    }

    /** Android 13+: один раз после первого подключения предложить плитку в шторку. */
    private void offerTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || prefs.isTileOffered()) return;
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        if (sbm == null) return;
        prefs.setTileOffered();
        try {
            sbm.requestAddTileService(new ComponentName(this, OdaTileService.class),
                    getString(R.string.app_name), Icon.createWithResource(this, R.drawable.ic_power),
                    getMainExecutor(), result -> { });
        } catch (RuntimeException ignored) {
            // оболочка производителя не поддерживает — плитку можно добавить вручную
        }
    }

    private void showCountry() {
        Country country = Country.find(this, prefs.getCountry());
        countryCodeView.setText(country.shortLabel());
        countryNameView.setText(country.name);
    }

    private void showBridgeMode() {
        String mode = getString(prefs.getMode().title).toUpperCase(Locale.ROOT);
        bridgesLink.setText(getString(R.string.link_bridges, mode));
        // Обход DPI идёт напрямую, своей страны выхода нет — прячем выбор страны
        boolean hasCountry = prefs.getMode() != BridgeMode.BYPASS;
        findViewById(R.id.countryRow).setVisibility(hasCountry ? View.VISIBLE : View.GONE);
    }

    private void showApps() {
        int n = prefs.getExcludedApps().size();
        appsValue.setText(n == 0 ? getString(R.string.apps_value_all)
                : getResources().getQuantityString(R.plurals.apps_value_except, n, n));
    }

    private void openLog() {
        if (getSupportFragmentManager().findFragmentByTag(LogSheet.TAG) != null) return;
        new LogSheet().show(getSupportFragmentManager(), LogSheet.TAG);
    }

    private void openBridges() {
        if (getSupportFragmentManager().findFragmentByTag(BridgesSheet.TAG) != null) return;
        new BridgesSheet().show(getSupportFragmentManager(), BridgesSheet.TAG);
    }

    private void openApps() {
        if (getSupportFragmentManager().findFragmentByTag(AppsSheet.TAG) != null) return;
        new AppsSheet().show(getSupportFragmentManager(), AppsSheet.TAG);
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
