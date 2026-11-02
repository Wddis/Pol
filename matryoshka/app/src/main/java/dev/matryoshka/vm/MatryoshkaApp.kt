package dev.matryoshka.vm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.matryoshka.vm.core.VmManager

class MatryoshkaApp : Application() {

    lateinit var vm: VmManager
        private set

    override fun onCreate() {
        super.onCreate()
        vm = VmManager(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, getString(R.string.channel_running), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_RUNNING = "running"
    }
}
