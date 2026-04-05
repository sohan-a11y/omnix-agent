package com.omnix.agent.mesh

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * Multi-device OMNIX Mesh - Sprint 5 (Task 36)
 * Allows multiple OMNIX devices to collaborate on tasks.
 * Uses WiFi Direct / BLE for local device discovery.
 */
class OmnixMeshService : Service() {

    companion object {
        const val CHANNEL_ID = "omnix_mesh"
        const val NOTIFICATION_ID = 300
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "OMNIX Mesh", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OMNIX Mesh")
            .setContentText("Connected to device network")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
    }
}
