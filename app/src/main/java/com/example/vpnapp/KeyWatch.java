package com.example.vpnapp;

import android.content.Context;
import android.os.Handler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ключ, пока VPN включён (живёт в {@link VpnTunnelService}, вызывается из его потока): сигнал серверу ключей
 * при подключении, смене способа и раз в 15 минут (по нему администратор видит «Сейчас в VPN»), «выключен» —
 * при отключении. Раз в минуту — проверка лицензии на телефоне. Ответ «заблокирован», «удалён», «срок вышел»
 * или конец срока по лицензии — VPN отключается сразу ({@link Callback#onLocked}).
 */
final class KeyWatch {

    interface Callback {
        /** Доступ закрыт: отключить VPN и показать причину. Вызывается в потоке сервиса. */
        void onLocked(String message);
    }

    private static final long TICK_MS = 60_000;
    private static final long BEAT_S = 15 * 60;

    private final KeyManager keys;
    private final Handler worker;
    private final Callback callback;
    private final ExecutorService net = Executors.newSingleThreadExecutor(r -> new Thread(r, "oda-key"));
    private final Runnable ticker = this::tick;

    private boolean running;
    private String mode = "";
    private int socksPort;
    private long lastBeat;

    KeyWatch(Context context, Handler worker, Callback callback) {
        this.keys = KeyManager.get(context);
        this.worker = worker;
        this.callback = callback;
    }

    /** VPN подключён (или сменился способ). */
    void connected(String engine, int port) {
        socksPort = port;
        boolean changed = !running || !engine.equals(mode);
        mode = engine;
        if (!running) {
            running = true;
            worker.postDelayed(ticker, TICK_MS);
        }
        if (changed) beat();
    }

    /** VPN отключён: сигнал «выключен» (без ожидания ответа). */
    void stopped() {
        if (!running) return;
        running = false;
        worker.removeCallbacks(ticker);
        net.execute(() -> keys.check(false, "", 0));
    }

    private void tick() {
        if (!running) return;
        KeyManager.Gate g = keys.gate();
        if (g != KeyManager.Gate.OK && g != KeyManager.Gate.NEED_CHECK) {
            lock(g);
            return;
        }
        long since = keys.now() - lastBeat;
        // Без связи с сервером — повтор раз в 2 минуты (лицензия ещё действует, VPN работает)
        if (since >= BEAT_S || (g == KeyManager.Gate.NEED_CHECK && since >= 120)) beat();
        worker.postDelayed(ticker, TICK_MS);
    }

    private void beat() {
        lastBeat = keys.now();
        final String m = mode;
        final int port = socksPort;
        net.execute(() -> {
            keys.check(true, m, port);
            worker.post(() -> {
                if (!running) return;
                KeyManager.Gate g = keys.gate();
                if (g == KeyManager.Gate.OK) {
                    keys.maybeWarn();
                } else if (g != KeyManager.Gate.NEED_CHECK) {
                    lock(g);
                }
            });
        });
    }

    private void lock(KeyManager.Gate g) {
        running = false;
        worker.removeCallbacks(ticker);
        String text = keys.lockText(g);
        keys.notifyLocked(text);
        callback.onLocked(text);
    }
}
