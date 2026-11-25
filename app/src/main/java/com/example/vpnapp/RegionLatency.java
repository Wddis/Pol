package com.example.vpnapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Проверки используют отдельные SOCKS-процессы и не меняют текущий VPN. */
final class RegionLatency {
    enum State { RUNNING, VERIFIED, TIMEOUT, OUTDATED }

    interface Listener {
        void update(String code, State state, long milliseconds);
        void finished();
    }

    private static final int PARALLEL = 2;
    private static final long TIMEOUT_MS = 30_000;
    private static final long BATCH_MS = 180_000;
    private static final long CACHE_MS = 30 * 60_000L;

    private final Context context;
    private final ConnectivityManager connectivity;
    private final Network network;
    private final String networkId;
    private final SharedPreferences cache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("oda-region-latency");
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final Map<String, PsiphonController> active = new HashMap<>();
    private final Set<String> retry = new HashSet<>();
    private Handler worker;
    private Listener listener;
    private List<String> codes;
    private boolean registered;
    private boolean finished;
    private volatile boolean cancelled;
    private final Runnable deadline = () -> finish(State.TIMEOUT);
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network ignored) { checkNetwork(); }
                @Override public void onLost(Network ignored) { checkNetwork(); }
            };

    RegionLatency(Context context) {
        this.context = context.getApplicationContext();
        connectivity = this.context.getSystemService(ConnectivityManager.class);
        network = connectivity != null ? connectivity.getActiveNetwork() : null;
        networkId = VpnTunnelService.networkId(this.context);
        cache = this.context.getSharedPreferences("region_latency", Context.MODE_PRIVATE);
    }

    long cached(String code) {
        if (network == null) return -1;
        String key = key(code);
        long age = System.currentTimeMillis() - cache.getLong(key + ":time", 0);
        return age >= 0 && age < CACHE_MS ? cache.getLong(key, -1) : -1;
    }

    void start(List<String> codes, Listener listener) {
        this.codes = codes;
        this.listener = listener;
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            pending.addAll(codes);
            if (connectivity != null) {
                try {
                    connectivity.registerDefaultNetworkCallback(networkCallback);
                    registered = true;
                } catch (RuntimeException ignored) {
                    // The current network is also checked before using any result.
                }
            }
            worker.postDelayed(deadline, BATCH_MS);
            pump();
        });
    }

    void cancel() {
        cancelled = true;
        if (worker != null) worker.post(() -> finish(null));
    }

    private void checkNetwork() {
        worker.post(() -> {
            if (!finished && !networkCurrent()) finish(State.OUTDATED);
        });
    }

    private void pump() {
        if (cancelled || finished) return;
        if (!networkCurrent()) {
            finish(State.OUTDATED);
            return;
        }
        if (!PsiphonController.isAvailable(context)) {
            finish(State.TIMEOUT);
            return;
        }
        while (active.size() < PARALLEL && !pending.isEmpty()) {
            String code = pending.removeFirst();
            PsiphonController controller = new PsiphonController(context, worker, true);
            active.put(code, controller);
            publish(code, State.RUNNING, -1);
            controller.start(code, networkId, null, TIMEOUT_MS, new PsiphonController.Listener() {
                @Override public void onPsiphonProgress(String text) {}
                @Override public void onPsiphonTunnel(boolean up) {}

                @Override
                public void onPsiphonReady(int port, String serverRegion) {
                    complete(code, controller,
                            code.equalsIgnoreCase(serverRegion) ? controller.latencyMs() : -1);
                }

                @Override
                public void onPsiphonError(String reason) {
                    complete(code, controller, -1);
                }
            });
        }
        if (active.isEmpty()) finish(null);
    }

    private void complete(String code, PsiphonController controller, long latency) {
        if (finished || active.get(code) != controller) return;
        active.remove(code);
        controller.stop();
        if (cancelled) return;
        if (!networkCurrent()) {
            finish(State.OUTDATED);
            return;
        }
        if (latency >= 0) {
            String key = key(code);
            cache.edit().putLong(key, latency).putLong(key + ":time", System.currentTimeMillis()).apply();
            publish(code, State.VERIFIED, latency);
        } else if (retry.add(code)) {
            pending.addLast(code);
        } else {
            publish(code, State.TIMEOUT, -1);
        }
        pump();
    }

    private void finish(State remaining) {
        if (finished) return;
        finished = true;
        worker.removeCallbacks(deadline);
        if (registered) connectivity.unregisterNetworkCallback(networkCallback);
        for (PsiphonController controller : active.values()) controller.stop();
        if (remaining == State.OUTDATED) {
            for (String code : codes) publish(code, remaining, -1);
        } else if (remaining == State.TIMEOUT) {
            for (String code : active.keySet()) publish(code, remaining, -1);
            for (String code : pending) publish(code, remaining, -1);
        }
        active.clear();
        pending.clear();
        thread.quitSafely();
        main.post(() -> { if (!cancelled) listener.finished(); });
    }

    private boolean networkCurrent() {
        return network != null && network.equals(connectivity.getActiveNetwork());
    }

    private void publish(String code, State state, long value) {
        main.post(() -> { if (!cancelled) listener.update(code, state, value); });
    }

    private String key(String code) {
        return code + ":" + network.getNetworkHandle();
    }
}
