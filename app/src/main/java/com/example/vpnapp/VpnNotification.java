package com.example.vpnapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/** Чёрно-белое уведомление foreground-сервиса. */
final class VpnNotification {

    static final int ID = 1;

    private static final String CHANNEL = "vpn";
    private static final int REQ_OPEN = 1;
    private static final int REQ_STOP = 2;

    private VpnNotification() {
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    static Notification build(Context context, String title, String text) {
        return new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_power)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(context))
                .addAction(0, context.getString(R.string.notif_stop), stopVpn(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    static void update(Context context, String title, String text) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(ID, build(context, title, text));
        } catch (SecurityException ignored) {
            // нет разрешения на уведомления: сервис всё равно работает
        }
    }

    static PendingIntent openApp(Context context) {
        Intent i = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, REQ_OPEN, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent stopVpn(Context context) {
        Intent i = new Intent(context, VpnTunnelService.class)
                .setAction(VpnTunnelService.ACTION_STOP);
        return PendingIntent.getService(context, REQ_STOP, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
