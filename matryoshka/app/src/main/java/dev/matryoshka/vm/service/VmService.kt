package dev.matryoshka.vm.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.matryoshka.vm.MainActivity
import dev.matryoshka.vm.MatryoshkaApp
import dev.matryoshka.vm.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps virtual devices alive while the UI is in the background. Holds a partial wake lock only
 * while at least one VM runs, and re-routes guest networking when the host switches networks.
 */
class VmService : LifecycleService() {

    private val manager get() = (application as MatryoshkaApp).vm
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var seenActive = false

    override fun onCreate() {
        super.onCreate()
        startAsForeground(buildNotification(emptyList()))
        lifecycleScope.launch {
            manager.states.collectLatest { states ->
                val active = states.filterValues { it.isActive }
                if (active.isEmpty()) {
                    // Only leave the foreground once a VM has actually run and everything stopped.
                    if (seenActive) stopSelf()
                    return@collectLatest
                }
                seenActive = true
                val names = active.keys.mapNotNull { manager.repo.get(it)?.name }
                getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(names))
                acquireWakeLock()
            }
        }
        val cm = getSystemService(ConnectivityManager::class.java)
        netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { manager.onHostNetworkChanged() }
            override fun onLost(network: Network) { manager.onHostNetworkChanged() }
        }.also { cm.registerDefaultNetworkCallback(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_ID)?.let { manager.start(it) }
            ACTION_STOP -> intent.getStringExtra(EXTRA_ID)?.let { manager.stop(it) }
            ACTION_STOP_ALL -> manager.runningIds.forEach { manager.stop(it) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "matryoshka:vm")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun startAsForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun buildNotification(names: List<String>): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAll = PendingIntent.getService(
            this, 1, Intent(this, VmService::class.java).setAction(ACTION_STOP_ALL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, MatryoshkaApp.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_vm)
            .setContentTitle(if (names.isEmpty()) getString(R.string.notif_starting) else names.joinToString())
            .setContentText(getString(R.string.notif_running))
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_stop_all), stopAll)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 11
        const val ACTION_START = "dev.matryoshka.vm.START"
        const val ACTION_STOP = "dev.matryoshka.vm.STOP"
        const val ACTION_STOP_ALL = "dev.matryoshka.vm.STOP_ALL"
        const val EXTRA_ID = "id"

        fun start(context: Context, id: String) = ContextCompat.startForegroundService(
            context, Intent(context, VmService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id),
        )

        fun stop(context: Context, id: String) = context.startService(
            Intent(context, VmService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id),
        )
    }
}
