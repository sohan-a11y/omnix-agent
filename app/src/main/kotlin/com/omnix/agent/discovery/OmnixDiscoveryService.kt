package com.omnix.agent.discovery

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omnix.agent.ui.OnboardingActivity
import kotlinx.coroutines.*

class OmnixDiscoveryService : Service() {

    companion object {
        const val CHANNEL_ID = "omnix_discovery"
        const val NOTIFICATION_ID = 200
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var discoveryEngine: DiscoveryEngine

    override fun onCreate() {
        super.onCreate()
        discoveryEngine = DiscoveryEngine(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Discovering apps..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "com.omnix.agent.ACTION_BOOT_DISCOVERY" -> {
                scope.launch {
                    discoveryEngine.enumerateApps()
                    stopSelf()
                }
            }
            "com.omnix.agent.ACTION_DISCOVER_NEW" -> {
                val pkg = intent.getStringExtra("package_name") ?: return START_NOT_STICKY
                scope.launch {
                    discoveryEngine.discoverApp(pkg)
                    stopSelf()
                }
            }
            "com.omnix.agent.ACTION_DISCOVER_UPDATE" -> {
                val pkg = intent.getStringExtra("package_name") ?: return START_NOT_STICKY
                scope.launch {
                    discoveryEngine.discoverApp(pkg, forceRefresh = true)
                    stopSelf()
                }
            }
            "com.omnix.agent.ACTION_DISCOVER_ALL" -> {
                scope.launch {
                    val apps = discoveryEngine.enumerateApps()
                    updateNotification("Discovering ${apps.size} apps...")
                    discoveryEngine.discoverAllApps()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Discovery",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OMNIX Learning")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(message))
    }
}
