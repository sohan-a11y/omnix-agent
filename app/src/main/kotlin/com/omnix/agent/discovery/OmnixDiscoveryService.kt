package com.omnix.agent.discovery

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omnix.agent.ai.GemmaInferenceEngine
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
        // Must call startForeground immediately with correct type (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification("Starting app discovery…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Starting app discovery…"))
        }
        Log.i("OmnixDisc", "OmnixDiscoveryService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("OmnixDisc", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            "com.omnix.agent.ACTION_BOOT_DISCOVERY" -> {
                scope.launch {
                    Log.i("OmnixDisc", "Boot discovery starting")
                    val apps = discoveryEngine.enumerateApps()
                    updateNotification("Indexed ${apps.size} apps. Learning details…")
                    discoveryEngine.discoverAllApps { done, total ->
                        if (total > 0) {
                            updateNotification("Learning apps: $done / $total")
                        }
                    }
                    GemmaInferenceEngine.loadAppKnowledge(applicationContext)
                    Log.i("OmnixDisc", "Boot discovery done: ${apps.size} apps indexed")
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
                // Delegate to WorkManager-based batched discovery (Samsung-safe)
                Log.i("OmnixDisc", "Delegating full discovery to AppDiscoveryWorker")
                AppDiscoveryWorker.enqueueFullDiscovery(applicationContext)
                stopSelf()
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
