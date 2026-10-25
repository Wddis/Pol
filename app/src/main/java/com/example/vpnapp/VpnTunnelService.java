package com.example.vpnapp;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VPN: весь трафик телефона → tun → tun2socks → SOCKS5 Tor.
 * Если Tor заблокирован, по очереди пробуются мосты (см. ConnectPlan).
 * Вся логика идёт в потоке worker, UI узнаёт о состоянии через StatusBus.
 */
public class VpnTunnelService extends VpnService implements TorController.Listener {

    public static final String ACTION_START = "com.example.vpnapp.action.START";
    public static final String ACTION_STOP = "com.example.vpnapp.action.STOP";
    public static final String ACTION_COUNTRY = "com.example.vpnapp.action.COUNTRY";
    public static final String ACTION_RECONNECT = "com.example.vpnapp.action.RECONNECT";

    private static final String TAG = "VpnTunnelService";
    private static final int IP_CHECK_ATTEMPTS = 2;
    // Сколько ждём ответа Moat (оба запроса идут параллельно)
    private static final long MOAT_DEADLINE_MS = 50_000;
    // Пределы, пока ни один мост (узел) не ответил. Когда ответил, канал рабочий,
    // и TorController сам даёт попытке больше времени на построение цепочки
    // Без мостов: 45 с на подключение, при застревании прогресса — 30 с
    private static final long DIRECT_STALL_MS = 30_000;
    private static final long DIRECT_HARD_MS = 45_000;
    private static final long BRIDGE_STALL_MS = 60_000;
    private static final long BRIDGE_HARD_MS = 120_000;
    // Snowflake дольше ищет добровольца
    private static final long SNOWFLAKE_STALL_MS = 90_000;
    private static final long SNOWFLAKE_HARD_MS = 150_000;
    // DNSTT очень медленный: даём больше времени
    private static final long DNSTT_STALL_MS = 120_000;
    private static final long DNSTT_HARD_MS = 240_000;

    private HandlerThread thread;
    private Handler worker;
    private TorController tor;
    private PtController pt;
    private Prefs prefs;

    // Поля ниже меняются только в потоке worker
    @Nullable private ParcelFileDescriptor tun;
    private boolean active;
    private boolean tunnelRunning;
    private int socksPort;
    private int generation;  // отсекает устаревшие результаты фоновых задач
    private int connection;  // номер подключения: отсекает ответ Moat прошлого подключения
    private List<BridgeSet> plan = new ArrayList<>();
    private int attempt;
    @Nullable private String lastReason;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        DiagLog.init(this);
        thread = new HandlerThread("oda-vpn");
        thread.start();
        worker = new Handler(thread.getLooper());
        tor = new TorController(this, worker);
        pt = new PtController(this);
        VpnNotification.ensureChannel(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            worker.post(this::shutdown);
            return START_NOT_STICKY;
        }
        // Foreground нужно объявить сразу, иначе система остановит сервис
        if (!goForeground(getString(R.string.status_connecting))) {
            StatusBus.post(ConnectionState.ERROR, "", getString(R.string.error_foreground), 0);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_COUNTRY.equals(action)) {
            worker.post(this::changeCountry);
        } else if (ACTION_RECONNECT.equals(action)) {
            worker.post(this::reconnect);
        } else {
            // ACTION_START или запуск системой («Постоянная VPN»)
            worker.post(this::connect);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        // Пользователь отключил VPN в системе или включил другую VPN
        worker.post(() -> {
            teardown();
            publish(ConnectionState.OFF, "", "");
            finish();
        });
    }

    @Override
    public void onDestroy() {
        worker.post(() -> {
            if (active) {
                teardown();
                publish(ConnectionState.OFF, "", "");
            }
        });
        thread.quitSafely();
        super.onDestroy();
    }

    // ---------- Работа в потоке worker ----------

    private void connect() {
        if (active) return;
        active = true;
        int id = ++connection;
        String country = networkCountry();
        DiagLog.add("========== новое подключение ==========");
        DiagLog.add("ODA " + appVersion() + ", Android " + Build.VERSION.RELEASE
                + ", режим " + prefs.getMode() + ", страна " + prefs.getCountry()
                + ", сеть " + country);
        // Интерфейс поднимаем сразу: пока Tor не готов, трафик блокируется, а не уходит мимо
        try {
            tun = establishTun();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "establish", e);
            fail(R.string.error_tun);
            return;
        }
        if (!ConnectPlan.needsMoat(prefs.getMode())) {
            startPlan(ConnectPlan.build(this, prefs, null, country));
            return;
        }
        // Режим «Авто»: спрашиваем у Moat, какие мосты работают в этой сети.
        // Ждём ответа, только если начать не с чего: нет прошлого рабочего моста,
        // нет мостов Moat с прошлого раза и сеть без известных блокировок
        boolean waitMoat = prefs.getLastGood() == null && prefs.getMoatCache().isEmpty()
                && !ConnectPlan.isCensored(country);
        if (waitMoat) {
            publish(ConnectionState.CONNECTING, getString(R.string.detail_moat), "");
            DiagLog.add("Moat: запрос рабочих мостов…");
        } else {
            DiagLog.add("Moat: запрос в фоне, подключение его не ждёт");
            startPlan(ConnectPlan.build(this, prefs, null, country));
            if (!active) return;
        }
        Context app = getApplicationContext();
        new Thread(() -> {
            MoatClient.Result moat = null;
            try {
                moat = MoatClient.fetch(app, country, MOAT_DEADLINE_MS);
                if (!moat.sets.isEmpty()) prefs.setMoatCache(moat.sets);
            } catch (Exception e) {
                DiagLog.add("Moat не ответил, беру встроенные мосты");
            }
            MoatClient.Result result = moat;
            worker.post(() -> onMoat(id, waitMoat, result, country));
        }, "oda-moat").start();
    }

    private void onMoat(int id, boolean waited, @Nullable MoatClient.Result result,
                        @Nullable String country) {
        if (!active || id != connection) return;
        if (waited) {
            startPlan(ConnectPlan.build(this, prefs, result, country));
            return;
        }
        // Подключение уже идёт: свежие мосты Moat встают в план после текущей попытки
        if (result == null || socksPort != 0 || plan.isEmpty()) return;
        plan = ConnectPlan.merge(plan, attempt, ConnectPlan.build(this, prefs, result, country));
        logPlan("План обновлён по ответу Moat:");
    }

    private void reconnect() {
        if (active) teardown();
        connect();
    }

    private void startPlan(List<BridgeSet> newPlan) {
        if (newPlan.isEmpty()) {
            fail(prefs.getMode() == BridgeMode.CUSTOM
                    ? R.string.error_no_custom_bridges : R.string.error_tor_start);
            return;
        }
        plan = newPlan;
        attempt = 0;
        logPlan("План:");
        startAttempt();
    }

    private void logPlan(String title) {
        StringBuilder sb = new StringBuilder(title);
        for (BridgeSet set : plan) {
            sb.append(" [").append(set.label).append(": ")
                    .append(set.isDirect() ? "напрямую" : set.lines.size() + " мост., " + set.transports())
                    .append(']');
        }
        DiagLog.add(sb.toString());
    }

    /** Запуск Tor с текущим способом из плана. */
    private void startAttempt() {
        generation++;
        socksPort = 0;
        stopTunnel();
        BridgeSet set = plan.get(attempt);
        lastReason = null;
        DiagLog.add("Попытка " + (attempt + 1) + "/" + plan.size() + ": " + set.label);
        publish(ConnectionState.CONNECTING, progressText(0), "");
        List<String> bridgeTorrc;
        try {
            bridgeTorrc = pt.start(set);
        } catch (Exception e) {
            DiagLog.add("Транспорт не запустился: " + e);
            nextAttempt(R.string.error_tor_start);
            return;
        }
        long stall;
        long hard;
        if (set.isDirect()) {
            stall = DIRECT_STALL_MS;
            hard = DIRECT_HARD_MS;
        } else if (set.transports().contains("dnstt")) {
            stall = DNSTT_STALL_MS;
            hard = DNSTT_HARD_MS;
        } else if (set.transports().contains("snowflake")) {
            stall = SNOWFLAKE_STALL_MS;
            hard = SNOWFLAKE_HARD_MS;
        } else {
            stall = BRIDGE_STALL_MS;
            hard = BRIDGE_HARD_MS;
        }
        tor.start(prefs.getCountry(), bridgeTorrc, stall, hard, this);
    }

    private void nextAttempt(int lastError) {
        if (attempt < plan.size()) {
            // Не прошедшие в этой сети способы в следующий раз пойдут в конце
            BridgeSet failed = plan.get(attempt);
            if (failed.isDirect()) {
                prefs.setDirectFailed(true);
            } else if (getString(R.string.mode_obfs4).equals(failed.label)) {
                prefs.setObfs4Failed(true);
            }
        }
        tor.stop();
        pt.stop();
        attempt++;
        if (attempt < plan.size()) {
            startAttempt();
            return;
        }
        // Ничего не сработало: сбрасываем «последний рабочий» способ
        DiagLog.add("Все способы не сработали");
        prefs.setLastGood(null);
        fail(lastError == R.string.error_timeout ? R.string.error_blocked : lastError);
    }

    private void changeCountry() {
        if (!active) {
            connect();
            return;
        }
        if (socksPort == 0) {
            // Tor ещё загружается: перезапускаем текущий способ с новой страной
            if (!plan.isEmpty()) startAttempt();
            return;
        }
        int gen = ++generation;
        publish(ConnectionState.CONNECTING, getString(R.string.detail_switching), "");
        String country = prefs.getCountry();
        new Thread(() -> {
            boolean ok = tor.setCountry(country);
            worker.post(() -> {
                if (!active || gen != generation) return;
                if (!ok) {
                    fail(R.string.error_country);
                    return;
                }
                // Перезапуск туннеля обрывает старые соединения, новые пойдут через новую страну
                stopTunnel();
                if (startTunnel()) checkIp();
            });
        }, "oda-country").start();
    }

    @Override
    public void onProgress(int percent, @Nullable String reason) {
        lastReason = reason;
        publish(ConnectionState.CONNECTING, progressText(percent), "");
    }

    @Override
    public void onReady(int port) {
        socksPort = port;
        DiagLog.add("Tor 100%, SOCKS порт " + port);
        if (startTunnel()) checkIp();
    }

    @Override
    public void onError(int messageRes, boolean retry) {
        if (retry) {
            nextAttempt(messageRes);
        } else {
            fail(messageRes);
        }
    }

    private boolean startTunnel() {
        ParcelFileDescriptor t = tun;
        boolean started;
        try {
            started = t != null && Tun2Socks.start(this, t, socksPort);
        } catch (LinkageError e) {
            // нативная библиотека не загрузилась
            Log.e(TAG, "tun2socks", e);
            started = false;
        }
        if (!started) {
            fail(R.string.error_tun2socks);
            return false;
        }
        tunnelRunning = true;
        return true;
    }

    private void stopTunnel() {
        if (tunnelRunning) {
            Tun2Socks.stop();
            tunnelRunning = false;
        }
    }

    /** Проверка IP и страны выхода в фоне, с повтором при неудаче. */
    private void checkIp() {
        int gen = ++generation;
        publish(ConnectionState.CONNECTING, getString(R.string.detail_checking), "");
        int port = socksPort;
        new Thread(() -> {
            String ip = null;
            for (int i = 0; i < IP_CHECK_ATTEMPTS && ip == null; i++) {
                try {
                    ip = IpChecker.fetchExitIp(port);
                } catch (IOException e) {
                    Log.w(TAG, "ip check: " + e);
                }
            }
            String cc = ip != null ? tor.countryOf(ip) : null;
            String fp = tor.firstHopFingerprint();
            String exitIp = ip;
            worker.post(() -> onIpChecked(gen, exitIp, cc, fp));
        }, "oda-ipcheck").start();
    }

    private void onIpChecked(int gen, @Nullable String ip, @Nullable String cc, @Nullable String fp) {
        if (!active || gen != generation) return;
        DiagLog.add("Проверка IP: " + (ip != null ? ip + " (" + cc + ")" : "не удалась"));
        String detail;
        if (ip != null) {
            String code = cc != null && !cc.isEmpty() && !"??".equals(cc)
                    ? cc.toUpperCase(Locale.ROOT) : prefs.getCountry();
            detail = getString(R.string.detail_ip, ip, Country.nameOf(this, code));
        } else {
            detail = getString(R.string.detail_ip_unknown);
        }
        BridgeSet set = attempt < plan.size() ? plan.get(attempt) : null;
        if (set != null && !set.isDirect()) {
            // Какой мост сработал: запоминаем именно его, в следующий раз он пойдёт первым
            BridgeSet used = set.narrowTo(fp);
            BridgeSet good = used != null ? used : set;
            DiagLog.add("Сработал: " + good.label + (used != null ? " (" + fp.substring(0, 8) + "…)" : ""));
            if (prefs.getMode() == BridgeMode.AUTO) prefs.setLastGood(good);
            if (getString(R.string.mode_obfs4).equals(good.label)) prefs.setObfs4Failed(false);
            detail = good.label + " · " + detail;
        } else if (set != null && prefs.getMode() == BridgeMode.AUTO) {
            prefs.setLastGood(set);
            prefs.setDirectFailed(false);
        }
        publish(ConnectionState.ON, detail, "");
    }

    private String progressText(int percent) {
        String tor = getString(R.string.detail_bootstrap, percent);
        String why = reasonText(lastReason);
        if (why != null) tor = tor + " · " + why;
        if (plan.isEmpty() || attempt >= plan.size()) return tor;
        String label = plan.get(attempt).label;
        if (plan.size() > 1) {
            label = getString(R.string.detail_attempt, attempt + 1, plan.size(), label);
        }
        return label + " · " + tor;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** Причина от Tor понятными словами. */
    @Nullable
    private String reasonText(@Nullable String reason) {
        if (reason == null) return null;
        switch (reason) {
            case "TIMEOUT":
                return getString(R.string.reason_timeout);
            case "CONNECTRESET":
                return getString(R.string.reason_reset);
            case "CONNECTREFUSED":
                return getString(R.string.reason_refused);
            case "NOROUTE":
                return getString(R.string.reason_noroute);
            case "DONE":
            case "IOERROR":
            case "MISC":
                return getString(R.string.reason_misc);
            case "PT_MISSING":
                return getString(R.string.reason_pt);
            default:
                return reason;
        }
    }

    /** Код страны сети оператора (подсказка для Moat), без разрешений. */
    @Nullable
    private String networkCountry() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) return null;
        String cc = tm.getNetworkCountryIso();
        if (cc == null || cc.isEmpty()) cc = tm.getSimCountryIso();
        return cc == null || cc.isEmpty() ? null : cc;
    }

    private ParcelFileDescriptor establishTun() throws IOException {
        Builder b = new Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(Tun2Socks.MTU)
                .addAddress(Tun2Socks.TUN_IPV4, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(Tun2Socks.FAKE_DNS)
                // IPv6 тоже в туннель, чтобы он не утекал мимо Tor
                .addAddress(Tun2Socks.TUN_IPV6, 128)
                .addRoute("::", 0)
                .setConfigureIntent(VpnNotification.openApp(this));
        try {
            // Само приложение (Tor и мосты внутри него) ходит в сеть напрямую
            b.addDisallowedApplication(getPackageName());
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException(e);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.setMetered(false);
        }
        ParcelFileDescriptor fd = b.establish();
        if (fd == null) throw new IOException("VPN not prepared");
        return fd;
    }

    private void fail(int messageRes) {
        teardown();
        publish(ConnectionState.ERROR, "", getString(messageRes));
        finish();
    }

    private void shutdown() {
        teardown();
        publish(ConnectionState.OFF, "", "");
        finish();
    }

    private void teardown() {
        active = false;
        generation++;
        connection++;
        // Сам Tor не останавливается (повторный запуск в процессе приводил к падению),
        // у него только выключается сеть
        DiagLog.add("Отключение: сеть Tor выключена");
        tor.stop();
        DiagLog.add("Остановка мостов");
        pt.stop();
        stopTunnel();
        socksPort = 0;
        plan = new ArrayList<>();
        attempt = 0;
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
                // уже закрыт
            }
            tun = null;
        }
    }

    private void finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void publish(ConnectionState state, String detail, String error) {
        StatusBus.post(state, detail, error, state == ConnectionState.ON ? socksPort : 0);
        if (active) {
            String title = getString(state == ConnectionState.ON
                    ? R.string.status_on : R.string.status_connecting);
            VpnNotification.update(this, title + " · " + prefs.getCountry(), detail);
        }
    }

    private boolean goForeground(String title) {
        Notification n = VpnNotification.build(this, title, prefs.getCountry());
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED : 0;
        try {
            ServiceCompat.startForeground(this, VpnNotification.ID, n, type);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "startForeground", e);
            return false;
        }
    }
}
