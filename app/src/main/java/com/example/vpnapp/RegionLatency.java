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
import java.util.List;
import java.util.Map;

/** Проверки используют отдельные SOCKS-процессы и не меняют текущий VPN. */
final class RegionLatency {
    interface Listener {
        void result(String code, long milliseconds);
        void finished();
    }

    private static final int PARALLEL = 2;
    private static final long TIMEOUT_MS = 30_000;
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
    private Handler worker;
    private volatile boolean cancelled;

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
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            pending.addAll(codes);
            pump(listener);
        });
    }

    void cancel() {
        cancelled = true;
        if (worker != null) worker.post(() -> {
            for (PsiphonController controller : active.values()) controller.stop();
            active.clear();
            pending.clear();
            thread.quitSafely();
        });
    }

    private void pump(Listener listener) {
        if (cancelled) return;
        if (!networkCurrent()) {
            for (PsiphonController controller : active.values()) controller.stop();
            pending.addAll(active.keySet());
            active.clear();
            while (!pending.isEmpty()) publish(listener, pending.removeFirst(), -1);
        }
        while (active.size() < PARALLEL && !pending.isEmpty()) {
            String code = pending.removeFirst();
            PsiphonController controller = new PsiphonController(context, worker, true);
            active.put(code, controller);
            controller.start(code, networkId, null, TIMEOUT_MS, new PsiphonController.Listener() {
                @Override public void onPsiphonProgress(String text) {}
                @Override public void onPsiphonTunnel(boolean up) {}

                @Override
                public void onPsiphonReady(int port, String serverRegion) {
                    boolean sameRegion = Country.isAny(code) || code.equalsIgnoreCase(serverRegion);
                    complete(listener, code, sameRegion ? controller.latencyMs() : -1);
                }

                @Override
                public void onPsiphonError(String reason) {
                    complete(listener, code, -1);
                }
            });
        }
        if (active.isEmpty() && pending.isEmpty()) {
            thread.quitSafely();
            main.post(() -> { if (!cancelled) listener.finished(); });
        }
    }

    private void complete(Listener listener, String code, long latency) {
        PsiphonController controller = active.remove(code);
        if (controller == null) return;
        controller.stop();
        if (cancelled) return;
        long result = networkCurrent() ? latency : -1;
        if (result >= 0) {
            String key = key(code);
            cache.edit().putLong(key, result).putLong(key + ":time", System.currentTimeMillis()).apply();
        }
        publish(listener, code, result);
        pump(listener);
    }

    private boolean networkCurrent() {
        return network != null && connectivity != null && network.equals(connectivity.getActiveNetwork())
                && PsiphonController.isAvailable(context);
    }

    private void publish(Listener listener, String code, long value) {
        main.post(() -> { if (!cancelled) listener.result(code, value); });
    }

    private String key(String code) {
        return code + ":" + network.getNetworkHandle();
    }
}
