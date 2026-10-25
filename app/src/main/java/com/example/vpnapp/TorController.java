package com.example.vpnapp;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.freehaven.tor.control.TorControlConnection;

import org.torproject.jni.TorService;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Запуск и остановка Tor (tor-android), конфиг по стране, ожидание bootstrap 100%.
 * Все методы и колбэки выполняются в рабочем потоке (handler).
 */
final class TorController {

    interface Listener {
        void onProgress(int percent);

        void onReady(int socksPort);

        void onError(int messageRes);
    }

    private static final String TAG = "TorController";
    private static final long POLL_MS = 500;
    private static final long BOOTSTRAP_TIMEOUT_MS = 60_000;
    // Пауза, чтобы прошлый экземпляр Tor успел полностью завершиться
    private static final long RESTART_GAP_MS = 3_000;
    private static final Pattern PROGRESS = Pattern.compile("PROGRESS=(\\d+)");
    private static final Pattern PORT = Pattern.compile(":(\\d+)\"?(\\s|$)");

    private static volatile long lastStopAt;

    private final Context context;
    private final Handler handler;

    @Nullable private Listener listener;
    @Nullable private TorService service;
    private boolean bound;
    private boolean ready;
    private long startedAt;
    private int lastProgress = -1;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            TorService s = ((TorService.LocalBinder) binder).getService();
            handler.post(() -> service = s);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            handler.post(() -> {
                service = null;
                fail(R.string.error_tor_stopped);
            });
        }
    };

    // Ошибки и неожиданная остановка Tor приходят локальными broadcast-ами
    private final BroadcastReceiver torEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            String status = intent.getStringExtra(TorService.EXTRA_STATUS);
            // Пока не привязались, это могут быть события прошлого экземпляра Tor
            if (TorService.ACTION_ERROR.equals(action)) {
                Log.w(TAG, "Tor error: " + intent.getStringExtra(Intent.EXTRA_TEXT));
                handler.post(() -> {
                    if (bound) fail(R.string.error_tor_start);
                });
            } else if (TorService.STATUS_STOPPING.equals(status)) {
                handler.post(() -> {
                    if (bound) fail(R.string.error_tor_stopped);
                });
            }
        }
    };

    private final Runnable poll = this::pollBootstrap;
    private final Runnable bind = this::bindTor;

    TorController(Context context, Handler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
    }

    /** Запускает Tor с выходом в стране countryCode (например, "DE"). */
    void start(String countryCode, Listener l) {
        stop();
        listener = l;
        ready = false;
        lastProgress = -1;
        try {
            writeTorrc(countryCode);
        } catch (IOException e) {
            Log.e(TAG, "torrc", e);
            fail(R.string.error_tor_start);
            return;
        }
        IntentFilter filter = new IntentFilter(TorService.ACTION_STATUS);
        filter.addAction(TorService.ACTION_ERROR);
        LocalBroadcastManager.getInstance(context).registerReceiver(torEvents, filter);

        long wait = lastStopAt + RESTART_GAP_MS - SystemClock.elapsedRealtime();
        handler.postDelayed(bind, Math.max(0, wait));
    }

    /** Смена страны без перезапуска процесса: SETCONF ExitNodes + новые цепочки. */
    boolean setCountry(String countryCode) {
        try {
            writeTorrc(countryCode);
            TorControlConnection conn = control();
            if (conn == null) return false;
            conn.setConf("ExitNodes", "{" + countryCode.toLowerCase(Locale.ROOT) + "}");
            conn.signal("NEWNYM");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "setCountry", e);
            return false;
        }
    }

    /** Код страны по IP из базы GeoIP самого Tor, например "de". */
    @Nullable
    String countryOf(String ip) {
        try {
            TorControlConnection conn = control();
            return conn != null ? conn.getInfo("ip-to-country/" + ip) : null;
        } catch (IOException e) {
            return null;
        }
    }

    void stop() {
        listener = null;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(bind);
        try {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(torEvents);
        } catch (IllegalArgumentException ignored) {
            // не был зарегистрирован
        }
        if (bound) {
            bound = false;
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // сервис уже отвязан
            }
            lastStopAt = SystemClock.elapsedRealtime();
        }
        service = null;
        ready = false;
    }

    private void bindTor() {
        if (listener == null) return;
        Intent intent = new Intent(context, TorService.class);
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            fail(R.string.error_tor_start);
            return;
        }
        startedAt = SystemClock.elapsedRealtime();
        handler.postDelayed(poll, POLL_MS);
    }

    private void pollBootstrap() {
        Listener l = listener;
        if (l == null || ready) return;
        if (SystemClock.elapsedRealtime() - startedAt > BOOTSTRAP_TIMEOUT_MS) {
            fail(R.string.error_timeout);
            return;
        }
        try {
            TorControlConnection conn = control();
            if (conn != null) {
                int progress = parseProgress(conn.getInfo("status/bootstrap-phase"));
                if (progress != lastProgress) {
                    lastProgress = progress;
                    l.onProgress(progress);
                }
                if (progress >= 100) {
                    int port = parsePort(conn.getInfo("net/listeners/socks"));
                    if (port <= 0) {
                        fail(R.string.error_tor_start);
                        return;
                    }
                    ready = true;
                    l.onReady(port);
                    return;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "poll: " + e);
        }
        handler.postDelayed(poll, POLL_MS);
    }

    @Nullable
    private TorControlConnection control() {
        TorService s = service;
        return s != null ? s.getTorControlConnection() : null;
    }

    /** Ошибка: останавливаем Tor и сообщаем слушателю один раз. */
    private void fail(int messageRes) {
        Listener l = listener;
        if (l == null) return;
        stop();
        l.onError(messageRes);
    }

    private void writeTorrc(String countryCode) throws IOException {
        File geoip = GeoIp.install(context);
        String cc = countryCode.toLowerCase(Locale.ROOT);
        String torrc = "SocksPort auto IPv6Traffic\n"
                + "ExitNodes {" + cc + "}\n"
                + "StrictNodes 1\n"
                + "GeoIPFile " + geoip.getAbsolutePath() + "\n"
                + "ClientOnly 1\n"
                + "AvoidDiskWrites 1\n";
        try (Writer w = new FileWriter(TorService.getTorrc(context), false)) {
            w.write(torrc);
        }
    }

    private static int parseProgress(@Nullable String phase) {
        if (phase == null) return 0;
        Matcher m = PROGRESS.matcher(phase);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static int parsePort(@Nullable String listeners) {
        if (listeners == null) return 0;
        Matcher m = PORT.matcher(listeners);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
