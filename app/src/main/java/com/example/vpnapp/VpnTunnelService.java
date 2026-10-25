package com.example.vpnapp;

import android.app.Notification;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;

import java.io.IOException;
import java.util.Locale;

/**
 * VPN: весь трафик телефона → tun → tun2socks → SOCKS5 Tor.
 * Вся работа идёт в отдельном потоке worker, UI узнаёт о состоянии через StatusBus.
 */
public class VpnTunnelService extends VpnService implements TorController.Listener {

    public static final String ACTION_START = "com.example.vpnapp.action.START";
    public static final String ACTION_STOP = "com.example.vpnapp.action.STOP";
    public static final String ACTION_COUNTRY = "com.example.vpnapp.action.COUNTRY";

    private static final String TAG = "VpnTunnelService";
    private static final int IP_CHECK_ATTEMPTS = 2;

    private HandlerThread thread;
    private Handler worker;
    private TorController tor;
    private Prefs prefs;

    // Поля ниже меняются только в потоке worker
    @Nullable private ParcelFileDescriptor tun;
    private boolean active;
    private boolean tunnelRunning;
    private int socksPort;
    private int generation;  // защита от устаревших результатов проверки IP

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        thread = new HandlerThread("oda-vpn");
        thread.start();
        worker = new Handler(thread.getLooper());
        tor = new TorController(this, worker);
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
        generation++;
        publish(ConnectionState.CONNECTING, getString(R.string.detail_bootstrap, 0), "");
        // Интерфейс поднимаем сразу: пока Tor не готов, трафик блокируется, а не уходит мимо
        try {
            tun = establishTun();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "establish", e);
            fail(R.string.error_tun);
            return;
        }
        tor.start(prefs.getCountry(), this);
    }

    private void changeCountry() {
        if (!active) {
            connect();
            return;
        }
        generation++;
        publish(ConnectionState.CONNECTING, getString(R.string.detail_switching), "");
        if (socksPort == 0) {
            // Tor ещё загружается: просто перезапускаем с новой страной
            tor.start(prefs.getCountry(), this);
            return;
        }
        if (!tor.setCountry(prefs.getCountry())) {
            fail(R.string.error_country);
            return;
        }
        // Перезапуск туннеля обрывает старые соединения, новые пойдут через новую страну
        stopTunnel();
        if (!startTunnel()) return;
        checkIp(generation);
    }

    @Override
    public void onProgress(int percent) {
        publish(ConnectionState.CONNECTING, getString(R.string.detail_bootstrap, percent), "");
    }

    @Override
    public void onReady(int port) {
        socksPort = port;
        if (!startTunnel()) return;
        checkIp(generation);
    }

    @Override
    public void onError(int messageRes) {
        fail(messageRes);
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

    /** Проверка IP и страны выхода; сетевой запрос в фоне, повтор при неудаче. */
    private void checkIp(int gen) {
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
            String exitIp = ip;
            worker.post(() -> onIpChecked(gen, exitIp));
        }, "oda-ipcheck").start();
    }

    private void onIpChecked(int gen, @Nullable String ip) {
        if (!active || gen != generation) return;
        String detail;
        if (ip != null) {
            String cc = tor.countryOf(ip);
            String code = cc != null && !cc.isEmpty() && !"??".equals(cc)
                    ? cc.toUpperCase(Locale.ROOT) : prefs.getCountry();
            detail = getString(R.string.detail_ip, ip, Country.nameOf(this, code));
        } else {
            detail = getString(R.string.detail_ip_unknown);
        }
        publish(ConnectionState.ON, detail, "");
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
            // Само приложение (и Tor внутри него) ходит в сеть напрямую
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
        tor.stop();
        stopTunnel();
        socksPort = 0;
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
