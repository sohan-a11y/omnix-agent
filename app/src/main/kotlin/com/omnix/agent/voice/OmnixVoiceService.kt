package com.omnix.agent.voice

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omnix.agent.R
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ui.OnboardingActivity
import java.io.File
import java.util.zip.ZipInputStream

class OmnixVoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "omnix_voice"
        const val NOTIFICATION_ID = 100
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        extractVoskModelIfNeeded()
        // Initialize Gemma brain asynchronously — runs on IO thread, non-blocking
        GemmaInferenceEngine.initialize(applicationContext)
        VoicePipeline.start(applicationContext)
    }

    /** Extract vosk-model.zip → filesDir/models/vosk/ on first run. */
    private fun extractVoskModelIfNeeded() {
        val modelDir = File(filesDir, WhisperEngine.MODEL_DIR)
        val zipFile  = File(modelDir, "vosk-model.zip")
        val extracted = File(modelDir, WhisperEngine.MODEL_FILENAME)
        if (!zipFile.exists() || extracted.exists()) return
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(modelDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().buffered().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            zipFile.delete()   // free space after extraction
        } catch (_: Exception) { /* model will stay unextracted; VoicePipeline stays dormant */ }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        VoicePipeline.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OMNIX Voice",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Wake word detection is active"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, OnboardingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val chatIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, com.omnix.agent.ui.ChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OMNIX Listening")
            .setContentText("Say \"Hi AI\" or tap Chat to type")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_send, "Chat", chatIntent)
            .setOngoing(true)
            .build()
    }
}
