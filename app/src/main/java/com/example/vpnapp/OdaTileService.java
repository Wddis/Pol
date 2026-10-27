package com.example.vpnapp;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/**
 * Плитка ODA в шторке уведомлений: нажатие подключает последним выбранным способом («СПОСОБ»;
 * если его не выбирали — «Авто») или отключает. Если разрешения на VPN ещё нет (первый запуск)
 * или система не дала запустить сервис из шторки — открывается приложение и само подключается.
 */
public class OdaTileService extends TileService implements StatusBus.Listener {

    @Override
    public void onCreate() {
        super.onCreate();
        DiagLog.init(this);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        StatusBus.addListener(this);  // сразу присылает текущее состояние
    }

    @Override
    public void onStopListening() {
        StatusBus.removeListener(this);
        super.onStopListening();
    }

    @Override
    public void onStatus(@NonNull StatusBus.Status status) {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = status.state == ConnectionState.ON;
        boolean connecting = status.state == ConnectionState.CONNECTING;
        tile.setState(on || connecting ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_power));
        tile.setLabel(getString(R.string.app_name));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String mode = getString(new Prefs(this).getMode().title);
            tile.setSubtitle(on ? getString(R.string.tile_on, mode)
                    : connecting ? getString(R.string.status_connecting) : mode);
        }
        tile.updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (isLocked()) {
            unlockAndRun(this::toggle);
        } else {
            toggle();
        }
    }

    private void toggle() {
        ConnectionState state = StatusBus.current().state;
        if (state == ConnectionState.ON || state == ConnectionState.CONNECTING) {
            startService(new Intent(this, VpnTunnelService.class).setAction(VpnTunnelService.ACTION_STOP));
            return;
        }
        Intent prepare;
        try {
            prepare = VpnService.prepare(this);
        } catch (RuntimeException e) {
            prepare = new Intent();
        }
        String mode = getString(new Prefs(this).getMode().title);
        if (prepare != null) {
            // Разрешение на VPN спрашивает только экран приложения
            DiagLog.add("Плитка в шторке: нет разрешения на VPN — открываю приложение");
            openApp();
            return;
        }
        try {
            ContextCompat.startForegroundService(this,
                    new Intent(this, VpnTunnelService.class).setAction(VpnTunnelService.ACTION_START));
            DiagLog.add("Плитка в шторке: подключение, способ «" + mode + "»");
        } catch (RuntimeException e) {
            // Android 12+ может не дать запустить сервис не с экрана — подключит приложение
            DiagLog.add("Плитка в шторке: система не дала запустить VPN (" + e.getClass().getSimpleName()
                    + ") — открываю приложение");
            openApp();
        }
    }

    /** Открыть приложение, свернуть шторку; приложение само начнёт подключение. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @SuppressWarnings("deprecation")
    private void openApp() {
        Intent i = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_CONNECT, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }

    /** Обновить плитку, если шторка открыта (вызывается сервисом при смене состояния). */
    static void refresh(Context context) {
        try {
            TileService.requestListeningState(context, new ComponentName(context, OdaTileService.class));
        } catch (RuntimeException ignored) {
            // плитку не добавляли в шторку — обновлять нечего
        }
    }
}
