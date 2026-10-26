package com.example.vpnapp;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VPN: весь трафик телефона → tun → tun2socks → локальный SOCKS5 (Psiphon или Tor).
 * В режиме «Авто» Psiphon и Tor подключаются одновременно, работает тот, кто успел первым,
 * второй останавливается. Tor по очереди пробует мосты (см. ConnectPlan).
 * Вся логика идёт в потоке worker, UI узнаёт о состоянии через StatusBus.
 */
public class VpnTunnelService extends VpnService
        implements TorController.Listener, PsiphonController.Listener, BypassController.Listener {

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
    // Psiphon сам перебирает серверы и способы маскировки: столько ждём первого туннеля
    private static final long PSIPHON_TIMEOUT_MS = 8 * 60_000;
    // Перезапуски Psiphon, если его процесс завершился после подключения
    private static final int PSIPHON_RESTARTS = 3;

    private HandlerThread thread;
    private Handler worker;
    private TorController tor;
    private PsiphonController psiphon;
    private BypassController bypass;
    private PtController pt;
    private Prefs prefs;

    // Поля ниже меняются только в потоке worker
    @Nullable private ParcelFileDescriptor tun;
    private boolean active;
    private boolean tunnelRunning;
    private int socksPort;
    private int generation;  // отсекает устаревшие результаты фоновых задач
    private int connection;  // номер подключения: отсекает ответы прошлого подключения
    private List<BridgeSet> plan = new ArrayList<>();
    private int attempt;
    @Nullable private String lastReason;
    // Чем подключены (StatusBus.ENGINE_*); пока не подключены — null
    @Nullable private String engine;
    private boolean torActive;      // Tor пробует способы или подключён
    private boolean psiphonActive;  // Psiphon подключается или подключён
    private boolean psiphonUsed;    // в этом подключении пробовали Psiphon
    private int psiphonRestarts;
    private String psiphonText = "";
    private boolean bypassActive;   // обход DPI пробует стратегии или подключён
    private int bypassProfile;      // индекс текущей стратегии в bypassPlan
    private List<BypassController.Profile> bypassPlan = new ArrayList<>();
    private String bypassText = "";
    private int torPercent;
    private int torError = R.string.error_blocked;
    private String networkId = "UNKNOWN";
    @Nullable private String netCountry;
    @Nullable private NetProbe.Result probe;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        DiagLog.init(this);
        thread = new HandlerThread("oda-vpn");
        thread.start();
        worker = new Handler(thread.getLooper());
        tor = new TorController(this, worker);
        psiphon = new PsiphonController(this, worker);
        bypass = new BypassController(this, worker);
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
            StatusBus.post(ConnectionState.ERROR, "", getString(R.string.error_foreground), 0, "");
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
        netCountry = networkCountry();
        // Тип сети определяем до подъёма VPN, пока активна сеть оператора
        networkId = networkId();
        engine = null;
        probe = null;
        psiphonRestarts = 0;
        psiphonText = "";
        bypassText = "";
        bypassProfile = 0;
        torPercent = 0;
        lastReason = null;
        torError = R.string.error_blocked;
        BridgeMode mode = prefs.getMode();
        boolean psiphonBuilt = PsiphonController.isAvailable(this);
        boolean bypassBuilt = BypassController.isAvailable(this);
        psiphonUsed = (mode == BridgeMode.AUTO || mode == BridgeMode.PSIPHON) && psiphonBuilt;
        boolean bypassUsed = (mode == BridgeMode.AUTO || mode == BridgeMode.BYPASS) && bypassBuilt;
        DiagLog.add("========== новое подключение ==========");
        DiagLog.add("ODA " + appVersion() + ", Android " + Build.VERSION.RELEASE
                + ", режим " + mode + ", страна " + prefs.getCountry()
                + ", сеть " + networkId.split("-")[0] + " " + netCountry);
        // Интерфейс поднимаем сразу: пока туннель не готов, трафик блокируется, а не уходит мимо
        try {
            tun = establishTun();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "establish", e);
            fail(R.string.error_tun);
            return;
        }
        probeNetwork(id);
        if (mode == BridgeMode.PSIPHON && !psiphonBuilt) {
            fail(R.string.error_psiphon_missing);
            return;
        }
        if (mode == BridgeMode.BYPASS && !bypassBuilt) {
            fail(R.string.error_bypass_missing);
            return;
        }
        // Обход DPI запускаем первым: когда срабатывает, он самый быстрый (прямое соединение)
        if (bypassUsed) startBypass();
        if (psiphonUsed) {
            startPsiphon();
        } else if (mode == BridgeMode.AUTO && !bypassBuilt) {
            DiagLog.add("Psiphon нет в этой сборке: только Tor");
        }
        if (mode == BridgeMode.AUTO) startTor(id);
    }

    private void startBypass() {
        bypassActive = true;
        bypassProfile = 0;
        bypassPlan = buildBypassPlan();
        startBypassProfile();
    }

    /** План стратегий обхода: своя строка, иначе встроенные с сработавшей ранее — первой. */
    private List<BypassController.Profile> buildBypassPlan() {
        BypassController.Profile custom = BypassController.customProfile(prefs.getByedpiArgs());
        if (custom != null) return new ArrayList<>(java.util.Collections.singletonList(custom));
        List<BypassController.Profile> plan = new ArrayList<>(BypassController.PROFILES);
        String good = prefs.getBypassGood();
        if (good != null) {
            // Сработавшую в прошлый раз стратегию пробуем первой — обычно это сразу успех
            for (int i = 0; i < plan.size(); i++) {
                if (plan.get(i).label.equals(good)) {
                    plan.add(0, plan.remove(i));
                    break;
                }
            }
        }
        return plan;
    }

    private void startBypassProfile() {
        BypassController.Profile profile = bypassPlan.get(bypassProfile);
        bypassText = getString(R.string.bypass_trying, profile.label);
        if (engine == null) publish(ConnectionState.CONNECTING, progressText(), "");
        bypass.start(profile, this);
    }

    private void startPsiphon() {
        psiphonActive = true;
        psiphon.start(prefs.getCountry(), networkId, netCountry, PSIPHON_TIMEOUT_MS, this);
    }

    /** Tor: план мостов; в «Авто» — с запросом рабочих мостов у Moat. */
    private void startTor(int id) {
        torActive = true;
        String country = netCountry;
        if (!ConnectPlan.needsMoat(prefs.getMode())) {
            startPlan(ConnectPlan.build(this, prefs, null, country));
            return;
        }
        // Ждём ответа Moat, только если начать не с чего: нет прошлого рабочего моста,
        // нет мостов Moat с прошлого раза и сеть без известных блокировок
        boolean waitMoat = prefs.getLastGood() == null && prefs.getMoatCache().isEmpty()
                && !ConnectPlan.isCensored(country);
        if (waitMoat) {
            publish(ConnectionState.CONNECTING, progressText(), "");
            DiagLog.add("Moat: запрос рабочих мостов…");
        } else {
            DiagLog.add("Moat: запрос в фоне, подключение его не ждёт");
            startPlan(ConnectPlan.build(this, prefs, null, country));
            if (!active || !torActive) return;
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
        // Уже подключены (например, через Psiphon) или Tor остановлен — мосты не нужны
        if (!active || id != connection || !torActive || engine != null) return;
        if (waited) {
            startPlan(ConnectPlan.build(this, prefs, result, country));
            return;
        }
        // Подключение уже идёт: свежие мосты Moat встают в план после текущей попытки
        if (result == null || socksPort != 0 || plan.isEmpty()) return;
        plan = ConnectPlan.merge(plan, attempt, ConnectPlan.build(this, prefs, result, country));
        logPlan("План обновлён по ответу Moat:");
    }

    /** Проверка сети напрямую: если закрыто всё зарубежное, подскажем про «белые списки». */
    private void probeNetwork(int id) {
        new Thread(() -> {
            NetProbe.Result r = NetProbe.run();
            worker.post(() -> {
                if (!active || id != connection) return;
                probe = r;
                if (r == NetProbe.Result.OK) return;
                DiagLog.add(r == NetProbe.Result.WHITELIST
                        ? "Похоже на «белые списки»: зарубежные сайты закрыты даже напрямую"
                        : "Нет доступа в интернет даже напрямую");
                if (engine == null) publish(ConnectionState.CONNECTING, progressText(), "");
            });
        }, "oda-probe").start();
    }

    private void reconnect() {
        if (active) teardown();
        connect();
    }

    private void startPlan(List<BridgeSet> newPlan) {
        if (newPlan.isEmpty()) {
            torGaveUp(prefs.getMode() == BridgeMode.CUSTOM
                    ? R.string.error_no_custom_bridges : R.string.error_tor_start);
            return;
        }
        plan = newPlan;
        attempt = 0;
        logPlan("План Tor:");
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
        torPercent = 0;
        DiagLog.add("Попытка Tor " + (attempt + 1) + "/" + plan.size() + ": " + set.label);
        publish(ConnectionState.CONNECTING, progressText(), "");
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
        torGaveUp(lastError == R.string.error_timeout ? R.string.error_blocked : lastError);
    }

    /** Tor перебрал все способы: ждём Psiphon, если он ещё пробует, иначе ошибка. */
    private void torGaveUp(int error) {
        torActive = false;
        torError = error;
        tor.stop();
        pt.stop();
        // Сбрасываем «последний рабочий» мост: в этой сети он не прошёл
        prefs.setLastGood(null);
        if (psiphonActive || bypassActive) {
            DiagLog.add("Tor не подключился, другие способы продолжают");
            publish(ConnectionState.CONNECTING, progressText(), "");
            return;
        }
        failAll();
    }

    /** Не сработал ни один способ: ошибка с самой полезной подсказкой. */
    private void failAll() {
        DiagLog.add("Все способы не сработали");
        if (probe == NetProbe.Result.WHITELIST) {
            fail(R.string.error_whitelist);
        } else if (probe == NetProbe.Result.OFFLINE) {
            fail(R.string.error_offline);
        } else if (prefs.getMode() == BridgeMode.PSIPHON) {
            fail(R.string.error_psiphon_failed);
        } else if (prefs.getMode() == BridgeMode.BYPASS) {
            fail(R.string.error_bypass_failed);
        } else {
            fail(psiphonUsed ? R.string.error_all_blocked : torError);
        }
    }

    private void changeCountry() {
        if (!active) {
            connect();
            return;
        }
        if (StatusBus.ENGINE_BYPASS.equals(engine)) {
            // Обход DPI — прямое соединение, страна выхода к нему не применяется
            DiagLog.add("Обход DPI: смена страны не применяется (прямое соединение)");
            return;
        }
        if (StatusBus.ENGINE_PSIPHON.equals(engine)) {
            // Psiphon: перезапуск с новой страной (серверы уже известны, это быстро)
            engine = null;
            generation++;
            stopTunnel();
            socksPort = 0;
            publish(ConnectionState.CONNECTING, getString(R.string.detail_switching), "");
            startPsiphon();
            return;
        }
        if (engine == null) {
            // Ещё подключаемся: перезапускаем текущие попытки с новой страной
            if (psiphonActive) startPsiphon();
            if (torActive && !plan.isEmpty()) startAttempt();
            // обход DPI страну не использует — его не трогаем
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

    // ---------- Tor ----------

    @Override
    public void onProgress(int percent, @Nullable String reason) {
        torPercent = percent;
        lastReason = reason;
        if (engine == null) publish(ConnectionState.CONNECTING, progressText(), "");
    }

    @Override
    public void onReady(int port) {
        if (!active || engine != null) return;
        engine = StatusBus.ENGINE_TOR;
        stopOtherEngines(StatusBus.ENGINE_TOR);
        socksPort = port;
        DiagLog.add("Tor 100%, SOCKS порт " + port);
        if (startTunnel()) checkIp();
    }

    @Override
    public void onError(int messageRes, boolean retry) {
        // Tor упал после подключения: туннель остановит следующая попытка
        if (StatusBus.ENGINE_TOR.equals(engine)) engine = null;
        if (retry) {
            nextAttempt(messageRes);
        } else {
            torGaveUp(messageRes);
        }
    }

    // ---------- Psiphon ----------

    @Override
    public void onPsiphonProgress(String text) {
        psiphonText = text;
        if (engine == null) publish(ConnectionState.CONNECTING, progressText(), "");
    }

    @Override
    public void onPsiphonReady(int port, @Nullable String serverRegion) {
        if (!active) return;
        if (engine != null) {
            // Уже подключены через Tor
            psiphonActive = false;
            psiphon.stop();
            return;
        }
        engine = StatusBus.ENGINE_PSIPHON;
        stopOtherEngines(StatusBus.ENGINE_PSIPHON);
        socksPort = port;
        if (startTunnel()) checkIp();
    }

    @Override
    public void onPsiphonTunnel(boolean up) {
        if (!StatusBus.ENGINE_PSIPHON.equals(engine)) return;
        if (up) {
            DiagLog.add("Psiphon: туннель восстановлен");
            checkIp();
        } else {
            DiagLog.add("Psiphon: туннель пропал, Psiphon переподключается");
            generation++;
            publish(ConnectionState.CONNECTING, getString(R.string.psiphon_reconnecting), "");
        }
    }

    @Override
    public void onPsiphonError(String reason) {
        if (!active) return;
        DiagLog.add("Psiphon: " + reason);
        if (StatusBus.ENGINE_PSIPHON.equals(engine)) {
            // Psiphon был подключён и завершился: перезапускаем его
            engine = null;
            generation++;
            stopTunnel();
            socksPort = 0;
            if (psiphonRestarts++ < PSIPHON_RESTARTS) {
                publish(ConnectionState.CONNECTING, getString(R.string.psiphon_reconnecting), "");
                startPsiphon();
                return;
            }
            psiphonActive = false;
            if (prefs.getMode() == BridgeMode.PSIPHON) {
                fail(R.string.error_psiphon_stopped);
                return;
            }
            // Psiphon не держится: пробуем Tor
            DiagLog.add("Psiphon не держится, пробую Tor");
            startTor(connection);
            return;
        }
        psiphonActive = false;
        if (engine != null) return;
        if (torActive || bypassActive) {
            publish(ConnectionState.CONNECTING, progressText(), "");
            return;
        }
        failAll();
    }

    // ---------- Обход DPI (ByeDPI) ----------

    @Override
    public void onBypassListening(int port, String profileLabel) {
        if (!active || !bypassActive) return;
        if (engine != null) {
            // Кто-то уже подключился раньше
            bypassActive = false;
            bypass.stop();
            return;
        }
        // Проверяем реальным запросом: открылся ли зарубежный сайт через обход
        int id = connection;
        bypassText = getString(R.string.bypass_checking, profileLabel);
        publish(ConnectionState.CONNECTING, progressText(), "");
        new Thread(() -> {
            boolean ok = NetProbe.foreignReachableVia(port);
            worker.post(() -> onBypassVerified(id, port, profileLabel, ok));
        }, "oda-bypass-check").start();
    }

    private void onBypassVerified(int id, int port, String label, boolean ok) {
        if (!active || id != connection || !bypassActive || engine != null) return;
        if (ok) {
            DiagLog.add("Обход DPI сработал (" + label + "): зарубежный сайт открылся");
            // Запоминаем рабочую стратегию: в следующий раз применится сразу, без перебора
            if (BypassController.customProfile(prefs.getByedpiArgs()) == null) prefs.setBypassGood(label);
            engine = StatusBus.ENGINE_BYPASS;
            stopOtherEngines(StatusBus.ENGINE_BYPASS);
            socksPort = port;
            if (startTunnel()) checkIp();
            return;
        }
        DiagLog.add("Обход DPI (" + label + "): зарубежный сайт не открылся");
        nextBypassProfile();
    }

    @Override
    public void onBypassError(String reason) {
        if (!active || !bypassActive) return;
        nextBypassProfile();
    }

    /** Следующая стратегия обхода; если кончились — обход сдаётся. */
    private void nextBypassProfile() {
        bypass.stop();
        bypassActive = true;  // stop() сбросил слушателя, но перебор продолжается
        bypassProfile++;
        if (bypassProfile < bypassPlan.size()) {
            startBypassProfile();
            return;
        }
        bypassActive = false;
        bypassText = "";
        DiagLog.add("Обход DPI не помог ни одной стратегией");
        if (engine == null && !torActive && !psiphonActive) {
            failAll();
        } else if (engine == null) {
            publish(ConnectionState.CONNECTING, progressText(), "");
        }
    }

    /** Остановить все способы, кроме победившего. */
    private void stopOtherEngines(String winner) {
        DiagLog.add("Подключено через " + winner + ": остальные способы останавливаются");
        if (!StatusBus.ENGINE_TOR.equals(winner) && torActive) {
            torActive = false;
            tor.stop();
            pt.stop();
        }
        if (!StatusBus.ENGINE_PSIPHON.equals(winner) && psiphonActive) {
            psiphonActive = false;
            psiphon.stop();
        }
        if (!StatusBus.ENGINE_BYPASS.equals(winner) && bypassActive) {
            bypassActive = false;
            bypass.stop();
        }
    }

    // ---------- Туннель и проверка IP ----------

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
        boolean viaTrace = StatusBus.ENGINE_PSIPHON.equals(engine)
                || StatusBus.ENGINE_BYPASS.equals(engine);
        new Thread(() -> {
            String ip = null;
            String cc = null;
            for (int i = 0; i < IP_CHECK_ATTEMPTS && ip == null; i++) {
                try {
                    if (viaTrace) {
                        IpChecker.Exit exit = IpChecker.fetchTrace(port);
                        ip = exit.ip;
                        cc = exit.country;
                    } else {
                        ip = IpChecker.fetchExitIp(port);
                    }
                } catch (IOException e) {
                    Log.w(TAG, "ip check: " + e);
                }
            }
            String fp = null;
            if (!viaTrace) {
                cc = ip != null ? tor.countryOf(ip) : null;
                fp = tor.firstHopFingerprint();
            }
            String exitIp = ip;
            String exitCc = cc;
            String hop = fp;
            worker.post(() -> onIpChecked(gen, exitIp, exitCc, hop));
        }, "oda-ipcheck").start();
    }

    private void onIpChecked(int gen, @Nullable String ip, @Nullable String cc, @Nullable String fp) {
        if (!active || gen != generation) return;
        DiagLog.add("Проверка IP: " + (ip != null ? ip + " (" + cc + ")" : "не удалась"));
        if (StatusBus.ENGINE_BYPASS.equals(engine)) {
            publish(ConnectionState.ON, bypassDetail(ip, cc), "");
            return;
        }
        if (StatusBus.ENGINE_PSIPHON.equals(engine)) {
            publish(ConnectionState.ON, psiphonDetail(ip, cc), "");
            return;
        }
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
            detail = "Tor · " + good.label + " · " + detail;
        } else {
            if (set != null && prefs.getMode() == BridgeMode.AUTO) {
                prefs.setLastGood(set);
                prefs.setDirectFailed(false);
            }
            detail = "Tor · " + detail;
        }
        publish(ConnectionState.ON, detail, "");
    }

    /** Строка под статусом при подключении через Psiphon. */
    private String psiphonDetail(@Nullable String ip, @Nullable String cc) {
        String proto = psiphon.protocol();
        StringBuilder sb = new StringBuilder("Psiphon");
        if (proto != null && !proto.isEmpty()) sb.append(" (").append(proto).append(')');
        String code = cc != null ? cc.toUpperCase(Locale.ROOT) : psiphon.serverRegion();
        sb.append(" · ");
        if (ip == null) {
            sb.append(getString(R.string.detail_ip_unknown));
        } else if (code == null || code.isEmpty()) {
            sb.append(getString(R.string.detail_ip_only, ip));
        } else {
            sb.append(getString(R.string.detail_ip, ip, Country.nameOf(this, code)));
        }
        if (psiphon.isRegionFallback()) sb.append('\n').append(getString(R.string.detail_region_fallback));
        return sb.toString();
    }

    /** Строка под статусом при подключении через обход DPI (прямое соединение, свой IP). */
    private String bypassDetail(@Nullable String ip, @Nullable String cc) {
        StringBuilder sb = new StringBuilder(getString(R.string.bypass_connected));
        sb.append(" · ");
        if (ip == null) {
            sb.append(getString(R.string.detail_ip_unknown));
        } else if (cc == null || cc.isEmpty()) {
            sb.append(getString(R.string.detail_ip_only, ip));
        } else {
            sb.append(getString(R.string.detail_ip, ip, Country.nameOf(this, cc.toUpperCase(Locale.ROOT))));
        }
        return sb.toString();
    }

    // ---------- Текст статуса ----------

    /** Подсказка о сети и ход подключения каждого способа (по строке на способ). */
    private String progressText() {
        List<String> lines = new ArrayList<>();
        if (probe == NetProbe.Result.WHITELIST) {
            lines.add(getString(R.string.hint_whitelist));
        } else if (probe == NetProbe.Result.OFFLINE) {
            lines.add(getString(R.string.hint_offline));
        }
        if (bypassActive && !bypassText.isEmpty()) lines.add(bypassText);
        if (psiphonActive && !psiphonText.isEmpty()) lines.add(psiphonText);
        if (torActive) lines.add(torText());
        return TextUtils.join("\n", lines);
    }

    private String torText() {
        // План ещё не готов: ждём ответа Moat
        if (plan.isEmpty() || attempt >= plan.size()) return getString(R.string.detail_moat);
        String tor = getString(R.string.detail_bootstrap, torPercent);
        String why = reasonText(lastReason);
        if (why != null) tor = tor + " · " + why;
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

    // ---------- Сеть ----------

    /** Код страны сети оператора (подсказка для Moat и Psiphon), без разрешений. */
    @Nullable
    private String networkCountry() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) return null;
        String cc = tm.getNetworkCountryIso();
        if (cc == null || cc.isEmpty()) cc = tm.getSimCountryIso();
        return cc == null || cc.isEmpty() ? null : cc;
    }

    /** Тип сети для Psiphon: "WIFI", "MOBILE-25001" (код оператора), … */
    private String networkId() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return "UNKNOWN";
        try {
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
            if (caps == null) return "UNKNOWN";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WIFI";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                String op = tm != null ? tm.getNetworkOperator() : null;
                return op != null && !op.isEmpty() ? "MOBILE-" + op : "MOBILE";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "WIRED";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
        } catch (RuntimeException e) {
            Log.w(TAG, "network type: " + e);
        }
        return "UNKNOWN";
    }

    private ParcelFileDescriptor establishTun() throws IOException {
        Builder b = new Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(Tun2Socks.MTU)
                .addAddress(Tun2Socks.TUN_IPV4, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(Tun2Socks.FAKE_DNS)
                // IPv6 тоже в туннель, чтобы он не утекал мимо
                .addAddress(Tun2Socks.TUN_IPV6, 128)
                .addRoute("::", 0)
                .setConfigureIntent(VpnNotification.openApp(this));
        try {
            // Само приложение (Tor, мосты и Psiphon) ходит в сеть напрямую
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
        engine = null;
        if (psiphonActive) {
            psiphonActive = false;
            psiphon.stop();
        }
        if (bypassActive) {
            bypassActive = false;
            bypass.stop();
        }
        torActive = false;
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
        boolean on = state == ConnectionState.ON;
        StatusBus.post(state, detail, error, on ? socksPort : 0, on && engine != null ? engine : "");
        if (active) {
            String title = getString(on ? R.string.status_on : R.string.status_connecting);
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
