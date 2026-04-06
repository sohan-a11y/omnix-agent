package com.omnix.agent.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the Gemma 4 E2B model (~2.6 GB) via WorkManager to filesDir.
 *
 * Android DownloadManager cannot write to internal storage (SecurityException),
 * so we use a CoroutineWorker + URL.openStream() instead.
 */
object ModelDownloadManager {

    const val MODEL_URL = "https://huggingface.co/google/gemma-4-e2b-it-litert/resolve/main/gemma-4-e2b.litertlm"
    const val MODEL_FILENAME = "gemma-4-e2b.litertlm"
    private const val MODELS_DIR = "models"
    private const val WORK_TAG = "gemma_download"

    fun getModelFile(context: Context): File =
        File(context.filesDir, "$MODELS_DIR/$MODEL_FILENAME")

    fun isDownloaded(context: Context): Boolean = getModelFile(context).exists()

    /** Legacy alias kept for compatibility with OnboardingActivity. */
    fun isModelDownloaded(context: Context): Boolean = isDownloaded(context)

    fun getModelPath(context: Context): String = getModelFile(context).absolutePath

    /** Enqueues a background download (Wi-Fi only). Safe to call multiple times — only one runs. */
    fun startDownload(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val request = OneTimeWorkRequestBuilder<GemmaDownloadWorker>()
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_TAG,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}

class GemmaDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val NOTIF_CHANNEL = "omnix_download"
        private const val NOTIF_ID = 201
        private const val BUFFER_SIZE = 128 * 1024   // 128 KB
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createChannel()
        val notif = buildNotification(0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        createChannel()
        try { setForeground(getForegroundInfo()) } catch (_: Exception) { /* API < 31 fallback */ }

        val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
        val destFile  = File(modelsDir, ModelDownloadManager.MODEL_FILENAME)
        val tmpFile   = File(modelsDir, "${ModelDownloadManager.MODEL_FILENAME}.tmp")

        if (destFile.exists()) return@withContext Result.success()

        try {
            var conn = URL(ModelDownloadManager.MODEL_URL).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30_000
            conn.readTimeout    = 60_000
            conn.connect()

            // Follow redirects manually if needed (HuggingFace uses 302)
            var redirects = 0
            while (conn.responseCode in 300..399 && redirects < 5) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(location).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 30_000
                conn.readTimeout    = 60_000
                conn.connect()
                redirects++
            }

            val total = conn.contentLengthLong
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            conn.inputStream.buffered(BUFFER_SIZE).use { input ->
                tmpFile.outputStream().buffered(BUFFER_SIZE).use { output ->
                    var downloaded = 0L
                    var lastPercent = -1
                    val buf = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt()
                            if (pct != lastPercent) {
                                lastPercent = pct
                                nm.notify(NOTIF_ID, buildNotification(pct))
                            }
                        }
                    }
                }
            }

            conn.disconnect()
            tmpFile.renameTo(destFile)
            nm.cancel(NOTIF_ID)
            Result.success()
        } catch (e: Exception) {
            tmpFile.delete()
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            NOTIF_CHANNEL, "OMNIX Downloads", NotificationManager.IMPORTANCE_LOW
        )
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildNotification(percent: Int): android.app.Notification =
        NotificationCompat.Builder(context, NOTIF_CHANNEL)
            .setContentTitle("Downloading Gemma AI model")
            .setContentText(if (percent > 0) "$percent% · ~2.6 GB total" else "Starting download…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .build()
}
