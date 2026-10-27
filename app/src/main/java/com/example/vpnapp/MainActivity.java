package com.example.vpnapp;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Главный экран (0.11, макет владельца): вкладки «Главная» (способ, страна, приложения, кнопка, статус с
 * индикатором, секундомер, тест скорости, способ и kill switch), «История» (подключения и ошибки, журнал) и
 * «Настройки» (подключение, ключ, помощь). Состояние приходит из StatusBus.
 *
 * <p>Индикатор под статусом: серый — не подключено (кнопку не нажимали), зелёный — подключено (мигает, пока
 * идёт подключение), красный — ошибка, в том числе подключение дольше минуты, с причиной.
 */
public class MainActivity extends AppCompatActivity implements StatusBus.Listener {

    @Override
    protected void attachBaseContext(Context base) {
        // Размер шрифта и масштаб экрана — не крупнее обычных (см. Ui)
        super.attachBaseContext(Ui.wrap(base));
    }

    /** Открыть экран и сразу подключиться (плитка в шторке, когда сама подключить не может). */
    public static final String EXTRA_CONNECT = "com.example.vpnapp.extra.CONNECT";

    private static final long SCREEN_FADE_MS = 500;
    private static final long TEXT_FADE_MS = 150;
    /** Подключение дольше этого — красный индикатор с причиной. */
    private static final long SLOW_MS = 60_000;

    private static final int TAB_HOME = 0;
    private static final int TAB_HISTORY = 1;
    private static final int TAB_SETTINGS = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = this::tick;
    private final Runnable slowCheck = () -> renderIndicator(true);

    private Prefs prefs;
    private KeyManager keys;
    private ConnectionState state = ConnectionState.OFF;
    private String detail = "";
    private String error = "";
    private String engine = "";
    private long connectedAt;
    private long connectingSince;
    private boolean firstStatus = true;
    private boolean started;
    private boolean speedRunning;
    private boolean keyChecking;
    private int tab;

    private PowerButtonView powerButton;
    private TextView statusView;
    private TextView detailsView;
    private TextView errorView;
    private TextView timerView;
    private View indicator;
    private ObjectAnimator pulse;
    private View speedLink;
    private TextView speedLinkText;
    private TextView speedPing;
    private TextView speedLoad;
    private TextView engineValue;
    private TextView killValue;
    private MaterialSwitch killToggle;
    private View modeRow;
    private View appsRow;
    private View keyBanner;
    private View[] pages;
    private BottomNavigationView nav;
    private boolean navSync;
    private LinearLayout historyList;
    private LinearLayout settingsList;

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
        History.init(this);
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
        indicator = findViewById(R.id.indicator);
        speedLink = findViewById(R.id.speedLink);
        speedLinkText = findViewById(R.id.speedLinkText);
        speedPing = findViewById(R.id.speedPing);
        speedLoad = findViewById(R.id.speedLoad);
        engineValue = findViewById(R.id.engineValue);
        killValue = findViewById(R.id.killValue);
        killToggle = findViewById(R.id.killSwitchToggle);
        modeRow = findViewById(R.id.modeRow);
        appsRow = findViewById(R.id.appsRow);
        keyBanner = findViewById(R.id.keyBanner);
        historyList = findViewById(R.id.historyList);
        settingsList = findViewById(R.id.settingsList);
        pages = new View[]{findViewById(R.id.pageHome), findViewById(R.id.pageHistory), findViewById(R.id.pageSettings)};
        nav = findViewById(R.id.nav);

        applyInsets(root);
        // Главная раскладывается ConstraintLayout по пропорциям (activity_main.xml): кнопка занимает свободную
        // высоту, всё помещается без прокрутки и без подгонки из кода; анимаций раскладки на главной нет —
        // они сдвигали карточки, не перекладывая содержимое (0.11.1)
        modeRow.setBackgroundResource(R.drawable.bg_cell_top);
        appsRow.setBackgroundResource(R.drawable.bg_cell_bottom);
        showRows();

        modeRow.setOnClickListener(v -> openBridges());
        appsRow.setOnClickListener(v -> openApps());
        keyBanner.setOnClickListener(v -> OdaSheet.openBot(this, "renew"));
        findViewById(R.id.menuButton).setOnClickListener(v -> OdaSheet.open(this, OdaSheet.MENU));
        powerButton.setOnClickListener(v -> {
            haptic(v);
            onPowerClick();
        });
        speedLink.setOnClickListener(v -> runSpeedTest());
        findViewById(R.id.engineCell).setOnClickListener(v -> openBridges());
        findViewById(R.id.killSwitch).setOnClickListener(v -> openKillSwitchSettings());
        findViewById(R.id.historyLog).setOnClickListener(v -> openLog());
        findViewById(R.id.historyClear).setOnClickListener(v -> {
            History.clear();
            Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show();
            buildHistory();
        });
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (!navSync) showTab(id == R.id.navHistory ? TAB_HISTORY : id == R.id.navSettings ? TAB_SETTINGS : TAB_HOME, true);
            return true;
        });
        nav.setOnItemReselectedListener(item -> { });
        // Назад с «Истории» и «Настроек» — на главную
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (tab != TAB_HOME) {
                    showTab(TAB_HOME, true);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        getSupportFragmentManager().setFragmentResultListener(
                CountryPickerSheet.REQUEST_KEY, this, (key, result) -> {
                    String code = result.getString(CountryPickerSheet.RESULT_CODE);
                    if (code != null) onCountryPicked(code);
                });
        getSupportFragmentManager().setFragmentResultListener(
                BridgesSheet.REQUEST_KEY, this, (key, result) -> {
                    showRows();
                    // Новый режим мостов при включённом VPN — переподключение
                    boolean changed = result.getBoolean(BridgesSheet.RESULT_CHANGED);
                    if (changed && (state == ConnectionState.ON || state == ConnectionState.CONNECTING)) {
                        sendToService(VpnTunnelService.ACTION_RECONNECT);
                    }
                });
        getSupportFragmentManager().setFragmentResultListener(
                AppsSheet.REQUEST_KEY, this, (key, result) -> {
                    showRows();
                    // При включённом VPN список применяется сразу: пересоздаётся только интерфейс
                    if (state == ConnectionState.ON || state == ConnectionState.CONNECTING) {
                        sendToService(VpnTunnelService.ACTION_APPS);
                    }
                });

        showTab(savedInstanceState == null ? TAB_HOME : prefs.getTab(), false);
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
        showRows();
        if (tab == TAB_SETTINGS) buildSettings();
        if (tab == TAB_HISTORY) buildHistory();
        keys.maybeWarn();
        if (keys.checkDue()) checkKeyInBackground();
    }

    @Override
    protected void onStop() {
        started = false;
        main.removeCallbacks(ticker);
        main.removeCallbacks(slowCheck);
        stopPulse();
        StatusBus.removeListener(this);
        super.onStop();
    }

    // ---------- ключ ----------

    /** Ключ изменился (проверка, смена ключа): нет доступа — экран ключа, иначе обновить срок. */
    void onKeyChanged() {
        if (!keyAllowsMain()) return;
        renderKey();
        if (tab == TAB_SETTINGS) buildSettings();
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

    /** Полоса «Ключ: осталось …» за 2 дня до конца. */
    private void renderKey() {
        boolean soon = !keys.forever() && keys.secondsLeft() <= 2 * 86400L;
        if (soon) ((TextView) findViewById(R.id.keyBannerText)).setText(getString(R.string.banner_text, keys.leftText()));
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

    // ---------- состояние ----------

    @Override
    public void onStatus(@NonNull StatusBus.Status status) {
        // Первый снимок после открытия экрана — без анимации
        boolean animate = !firstStatus;
        firstStatus = false;
        connectedAt = status.connectedAt;
        connectingSince = status.connectingSince;
        engine = status.engine;
        render(status.state, status.detail, status.error, animate);
        if (tab == TAB_HISTORY && (status.state == ConnectionState.ON || status.state == ConnectionState.OFF
                || status.state == ConnectionState.ERROR)) {
            buildHistory();
        }
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
                connectingSince = 0;
                render(ConnectionState.OFF, "", "", true);
                break;
        }
    }

    private void onCountryPicked(String code) {
        if (code.equals(prefs.getCountry())) return;
        prefs.setCountry(code);
        showRows();
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
        connectingSince = SystemClock.elapsedRealtime();
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

    private void render(ConnectionState newState, String newDetail, String newError, boolean animate) {
        boolean becameOn = newState == ConnectionState.ON && state != ConnectionState.ON;
        state = newState;
        detail = newDetail != null ? newDetail : "";
        error = newError != null ? newError : "";
        powerButton.setState(newState, animate);

        int status;
        switch (newState) {
            case CONNECTING:
                status = R.string.status_connecting;
                break;
            case ON:
                status = R.string.status_on;
                break;
            case ERROR:
                status = R.string.status_error;
                break;
            default:
                status = R.string.status_off;
        }
        setText(statusView, getString(status), animate);
        renderIndicator(animate);

        boolean on = newState == ConnectionState.ON;
        speedLink.setAlpha(on ? 1f : 0.45f);
        if (!on && !speedRunning) {
            speedPing.setText(R.string.speed_ping_none);
            speedLoad.setText(R.string.speed_load_none);
        }
        engineValue.setText(engineTitle());
        renderKillSwitch();
        // Секундомер — и в коротком «Подключение…» после подключения (переход способа), с прежнего времени
        // Место под секундомер занято всегда: экран не «прыгает» при подключении
        timerView.setVisibility(connectedAt > 0 ? View.VISIBLE : View.INVISIBLE);
        main.removeCallbacks(ticker);
        if (connectedAt > 0 && started) tick();
    }

    /**
     * Индикатор и строки под статусом: серый — не подключено, зелёный — подключено (мигает при подключении),
     * красный — ошибка или подключение дольше минуты (с причиной).
     */
    private void renderIndicator(boolean animate) {
        main.removeCallbacks(slowCheck);
        String[] lines = detail.split("\n", 2);
        String first = lines[0];
        String rest = lines.length > 1 ? lines[1] : "";
        long connectingFor = connectingSince > 0 ? SystemClock.elapsedRealtime() - connectingSince : 0;
        boolean slow = state == ConnectionState.CONNECTING && connectedAt == 0 && connectingSince > 0 && connectingFor >= SLOW_MS;
        int color;
        String line;
        String note;
        boolean noteDanger = false;
        switch (state) {
            case ON:
                color = R.color.accent;
                line = first;
                note = rest;
                break;
            case CONNECTING:
                color = slow ? R.color.danger : R.color.accent;
                line = slow ? getString(R.string.ind_slow) + (first.isEmpty() ? "" : " · " + first) : first;
                note = slow ? getString(R.string.ind_slow_hint, first.isEmpty() ? getString(R.string.status_connecting) : first) : rest;
                noteDanger = slow;
                if (!slow && connectingSince > 0 && connectedAt == 0 && started) {
                    main.postDelayed(slowCheck, SLOW_MS - connectingFor + 50);
                }
                break;
            case ERROR:
                color = R.color.danger;
                line = getString(R.string.history_error);
                note = error;
                noteDanger = true;
                break;
            default:
                color = R.color.idle;
                line = getString(R.string.ind_off);
                note = "";
        }
        powerButton.setAlarm(slow);
        indicator.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, color)));
        setText(detailsView, line, animate);
        errorView.setTextColor(ContextCompat.getColor(this, noteDanger ? R.color.danger : R.color.text_secondary));
        errorView.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
        setText(errorView, note, animate);
        if (state == ConnectionState.CONNECTING && !slow) startPulse();
        else stopPulse();
    }

    private void startPulse() {
        if (pulse != null && pulse.isRunning()) return;
        pulse = ObjectAnimator.ofFloat(indicator, View.ALPHA, 1f, 0.25f);
        pulse.setDuration(700);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) pulse.cancel();
        if (indicator != null) indicator.setAlpha(1f);
    }

    /** Чем подключены сейчас, иначе — выбранный способ. */
    private String engineTitle() {
        if (state == ConnectionState.ON || state == ConnectionState.CONNECTING) {
            if (StatusBus.ENGINE_BYPASS.equals(engine)) return getString(R.string.engine_bypass);
            if (StatusBus.ENGINE_PSIPHON.equals(engine)) return getString(R.string.engine_psiphon);
            if (StatusBus.ENGINE_TOR.equals(engine)) return getString(R.string.engine_tor);
        }
        return getString(prefs.getMode().title);
    }

    private void renderKillSwitch() {
        int k = prefs.getKillSwitch();
        killToggle.setChecked(k >= 1);
        killValue.setText(k == 2 ? R.string.kill_on : k == 1 ? R.string.kill_partial : R.string.kill_off);
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
     * Итог — в карточке «Пинг» и в журнале.
     */
    private void runSpeedTest() {
        StatusBus.Status s = StatusBus.current();
        if (speedRunning) return;
        if (s.state != ConnectionState.ON || s.socksPort <= 0) {
            Toast.makeText(this, R.string.speed_need_on, Toast.LENGTH_SHORT).show();
            return;
        }
        speedRunning = true;
        speedLinkText.setText(R.string.speed_test_running);
        speedLink.setEnabled(false);
        speedPing.setText(getString(R.string.speed_ping_value, "…"));
        speedLoad.setText(R.string.speed_test_running);
        String eng = s.engine;
        int port = s.socksPort;
        new Thread(() -> {
            SpeedTest.Result r = SpeedTest.run(port);
            DiagLog.add("Тест скорости (" + eng + "): " + r.summary() + ", принято "
                    + r.bytes / 1024 + " КБ" + (r.error != null ? " · не прошло: " + r.error : ""));
            main.post(() -> {
                speedRunning = false;
                if (isDestroyed()) return;
                speedLinkText.setText(R.string.speed_test);
                speedLink.setEnabled(true);
                if (state != ConnectionState.ON) return;
                if (r.pingMs < 0 && r.mbps < 0) {
                    speedPing.setText(R.string.speed_ping_none);
                    speedLoad.setText(R.string.speed_failed_short);
                    return;
                }
                speedPing.setText(getString(R.string.speed_ping_value,
                        r.pingMs > 0 ? r.pingMs + " мс" : r.pingMs == 0 ? "<1 мс" : "—"));
                speedLoad.setText(getString(R.string.speed_load, r.mbps >= 0
                        ? String.format(Locale.forLanguageTag("ru"), "%.1f Мбит/с", r.mbps) : "—"));
            });
        }, "oda-speed").start();
    }

    // ---------- строки главной ----------

    private void showRows() {
        BridgeMode mode = prefs.getMode();
        bindRow(modeRow, R.drawable.ic_bolt, true, getString(mode.title),
                getString(mode == BridgeMode.AUTO ? R.string.mode_row_sub_auto : R.string.mode_row_sub), null);
        bindRow(appsRow, R.drawable.ic_globe, false, getString(R.string.apps_row), appsText(), null);
        // На главной подписи в одну строку: высота строк не меняется при смене способа
        for (View row : new View[]{modeRow, appsRow}) {
            TextView sub = row.findViewById(R.id.subtitle);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);
        }
        if (engineValue != null) engineValue.setText(engineTitle());
    }

    private String appsText() {
        int n = prefs.getExcludedApps().size();
        return n == 0 ? getString(R.string.apps_row_sub_all)
                : Ui.plural(this, R.plurals.apps_value_except, n);
    }

    /** Заполнить строку-карточку (item_row). accent — зелёная иконка на зелёной подложке. */
    private void bindRow(View row, @DrawableRes int icon, boolean accent, String title, @Nullable String sub,
                         @Nullable String value) {
        ImageView iv = row.findViewById(R.id.icon);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, accent ? R.color.accent : R.color.text)));
        row.findViewById(R.id.iconBg).setBackgroundResource(accent ? R.drawable.bg_circle_accent : R.drawable.bg_circle);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView s = row.findViewById(R.id.subtitle);
        s.setText(sub);
        s.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
        TextView v = row.findViewById(R.id.value);
        v.setText(value);
        v.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---------- вкладки ----------

    /** Вкладка: плавная смена (затухание и лёгкий подъём); прежние анимации отменяются, чтобы ничего не «застревало». */
    private void showTab(int t, boolean animate) {
        tab = t;
        prefs.setTab(t);
        for (int i = 0; i < pages.length; i++) {
            View p = pages[i];
            p.animate().cancel();
            if (i == t) {
                boolean wasHidden = p.getVisibility() != View.VISIBLE;
                p.setVisibility(View.VISIBLE);
                if (animate && wasHidden) {
                    p.setAlpha(0f);
                    p.setTranslationY(dp(12));
                    p.animate().alpha(1f).translationY(0f).setDuration(220)
                            .setInterpolator(new DecelerateInterpolator()).start();
                } else {
                    p.setAlpha(1f);
                    p.setTranslationY(0f);
                }
            } else {
                p.setVisibility(View.GONE);
                p.setAlpha(1f);
                p.setTranslationY(0f);
            }
        }
        int id = t == TAB_HISTORY ? R.id.navHistory : t == TAB_SETTINGS ? R.id.navSettings : R.id.navHome;
        if (nav.getSelectedItemId() != id) {
            navSync = true;
            nav.setSelectedItemId(id);
            navSync = false;
        }
        if (t == TAB_HISTORY) buildHistory();
        if (t == TAB_SETTINGS) buildSettings();
    }

    private void buildHistory() {
        historyList.removeAllViews();
        List<History.Entry> list = History.list();
        if (list.isEmpty()) {
            TextView empty = new TextView(this, null, 0, R.style.Text_Oda_CardSub);
            empty.setText(R.string.history_empty);
            empty.setPadding(0, dp(16), 0, 0);
            historyList.addView(empty);
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM, HH:mm", Locale.ROOT);
        boolean first = true;
        for (History.Entry e : list) {
            View row = getLayoutInflater().inflate(R.layout.item_row, historyList, false);
            row.setClickable(false);
            row.setFocusable(false);
            row.findViewById(R.id.chevron).setVisibility(View.GONE);
            String when = fmt.format(new Date(e.start));
            if (e.error) {
                bindRow(row, R.drawable.ic_close, false, getString(R.string.history_error), when + "\n" + e.detail, null);
                ((ImageView) row.findViewById(R.id.icon)).setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger)));
                row.findViewById(R.id.iconBg).setBackgroundResource(R.drawable.bg_circle_danger);
            } else {
                String dur = e.end > 0 ? duration((e.end - e.start) / 1000)
                        : getString(first && History.isOpen() ? R.string.history_now : R.string.history_interrupted);
                bindRow(row, R.drawable.ic_done, true, engineName(e.engine), when + " · " + dur + "\n" + e.detail, null);
            }
            first = false;
            historyList.addView(row);
        }
    }

    private String engineName(String eng) {
        if (StatusBus.ENGINE_BYPASS.equals(eng)) return getString(R.string.engine_bypass);
        if (StatusBus.ENGINE_PSIPHON.equals(eng)) return getString(R.string.engine_psiphon);
        if (StatusBus.ENGINE_TOR.equals(eng)) return getString(R.string.engine_tor);
        return getString(R.string.status_on);
    }

    private static String duration(long s) {
        if (s < 60) return "меньше минуты";
        long h = s / 3600, m = s / 60 % 60;
        return h > 0 ? h + " ч " + m + " мин" : m + " мин";
    }

    /**
     * «Настройки» — только то, что настраивает подключение и телефон. Ключ, справка, поддержка и бот — в меню ☰
     * (OdaSheet), чтобы разделы не повторяли друг друга.
     */
    private void buildSettings() {
        settingsList.removeAllViews();
        BridgeMode mode = prefs.getMode();
        section(R.string.section_connection);
        row(R.drawable.ic_bolt, true, getString(R.string.set_mode), getString(mode.hint), getString(mode.title), v -> openBridges());
        if (mode != BridgeMode.BYPASS) {
            Country c = Country.find(this, prefs.getCountry());
            row(R.drawable.ic_place, false, getString(R.string.set_country), getString(R.string.set_country_sub),
                    c.name + (Country.isAny(c.code) ? "" : " " + Country.flag(c.code)), v -> openPicker());
        }
        row(R.drawable.ic_globe, false, getString(R.string.set_apps), appsText(), null, v -> openApps());

        section(R.string.section_protection);
        int k = prefs.getKillSwitch();
        row(R.drawable.ic_shield, false, getString(R.string.set_kill), getString(R.string.set_kill_sub),
                getString(k == 2 ? R.string.kill_on : k == 1 ? R.string.kill_partial : R.string.kill_off),
                v -> openKillSwitchSettings());
        row(R.drawable.ic_bolt, false, getString(R.string.set_tile), getString(R.string.set_tile_sub), null, v -> addTile());

        section(R.string.section_phone);
        row(R.drawable.ic_phone, false, getString(R.string.set_battery), getString(R.string.set_battery_sub), null,
                v -> openSystem(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        row(R.drawable.ic_info, false, getString(R.string.set_notifications), getString(R.string.set_notifications_sub), null,
                v -> openNotificationSettings());

        section(R.string.section_data);
        row(R.drawable.ic_list, false, getString(R.string.set_log), getString(R.string.set_log_sub), null, v -> openLog());
        row(R.drawable.ic_history, false, getString(R.string.set_history), getString(R.string.set_history_sub), null,
                v -> showTab(TAB_HISTORY, true));
    }

    /** Плитка в шторке: Android 13+ — системный запрос, раньше — подсказка, как добавить вручную. */
    private void addTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            StatusBarManager sbm = getSystemService(StatusBarManager.class);
            if (sbm != null) {
                try {
                    sbm.requestAddTileService(new ComponentName(this, OdaTileService.class),
                            getString(R.string.app_name), Icon.createWithResource(this, R.drawable.ic_power),
                            getMainExecutor(), result -> { });
                    return;
                } catch (RuntimeException ignored) {
                    // оболочка производителя не поддерживает — подсказка ниже
                }
            }
        }
        Toast.makeText(this, R.string.set_tile_hint, Toast.LENGTH_LONG).show();
    }

    private void openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                return;
            } catch (ActivityNotFoundException ignored) {
                // ниже — общие настройки приложения
            }
        }
        openSystem(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
    }

    private void openSystem(String action) {
        Intent i = new Intent(action);
        if (Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(action)) {
            i.setData(android.net.Uri.fromParts("package", getPackageName(), null));
        }
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void section(int title) {
        TextView t = new TextView(this, null, 0, R.style.Text_Oda_Caption);
        t.setText(title);
        t.setPadding(dp(4), dp(24), 0, dp(4));
        settingsList.addView(t);
    }

    private void row(@DrawableRes int icon, boolean accent, String title, @Nullable String sub, @Nullable String value,
                     View.OnClickListener click) {
        View r = getLayoutInflater().inflate(R.layout.item_row, settingsList, false);
        bindRow(r, icon, accent, title, sub, value);
        r.setOnClickListener(click);
        settingsList.addView(r);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- листы ----------

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

    /** Сверху — отступ под строку состояния; снизу отступ под системную панель ставит сама нижняя навигация. */
    private void applyInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            return insets;
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
