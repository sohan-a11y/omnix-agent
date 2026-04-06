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
 * Downloads the Gemma 4 E2B model (~2.6 GB) to filesDir via WorkManager.
 *
 * Why WorkManager and not DownloadManager:
 *   DownloadManager.setDestinationUri(Uri.fromFile(filesDir/...)) throws
 *   SecurityException on Android 10+ — internal storage is not allowed.
 *   WorkManager + URL.openStream() writes directly to filesDir without issues.
 *
 * Why not Android AICore:
 *   LlmInference.createFromAiCore() is available in MediaPipe tasks-genai 0.10.22
 *   but only works on devices with the Gemma model pre-loaded into AICore
 *   (currently Pixel 9+ / Android 15 dev preview).  Samsung S25 Ultra has its
 *   own Galaxy AI stack, which is NOT the same Android AICore. We therefore
 *   always try the local file path and fall back to "not ready" when absent.
 */
object ModelDownloadManager {

    const val MODEL_URL      = "https://huggingface.co/google/gemma-4-e2b-it-litert/resolve/main/gemma-4-e2b.litertlm"
    const val MODEL_FILENAME = "gemma-4-e2b.litertlm"
    private const val MODELS_DIR = "models"
    internal const val WORK_TAG  = "gemma_download"

    fun getModelFile(context: Context): File =
        File(context.filesDir, "$MODELS_DIR/$MODEL_FILENAME")

    fun isDownloaded(context: Context): Boolean = getModelFile(context).exists()

    /** Alias kept for call-site compatibility. */
    fun isModelDownloaded(context: Context): Boolean = isDownloaded(context)

    fun getModelPath(context: Context): String = getModelFile(context).absolutePath

    /**
     * Enqueues a background download constrained to unmetered (Wi-Fi) network.
     * Calling multiple times is safe — KEEP policy means only one runs.
     */
    fun startDownload(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val request = OneTimeWorkRequestBuilder<GemmaDownloadWorker>()
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_TAG, ExistingWorkPolicy.KEEP, request)
    }
}

class GemmaDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val NOTIF_CHANNEL = "omnix_download"
        private const val NOTIF_ID      = 201
        private const val BUFFER_SIZE   = 128 * 1024   // 128 KB
        // HuggingFace redirects to CDN — allow up to 10 hops
        private const val MAX_REDIRECTS = 10
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createChannel()
        val notif = buildNotification(0, "Starting…")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        createChannel()
        try { setForeground(getForegroundInfo()) } catch (_: Exception) {}

        val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
        val destFile  = File(modelsDir, ModelDownloadManager.MODEL_FILENAME)
        val tmpFile   = File(modelsDir, "${ModelDownloadManager.MODEL_FILENAME}.tmp")

        if (destFile.exists()) return@withContext Result.success()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        return@withContext try {
            val conn = openConnectionWithRedirects(ModelDownloadManager.MODEL_URL)
            val responseCode = conn.responseCode

            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                nm.notify(NOTIF_ID, buildNotification(-1,
                    "Download failed: HuggingFace login required. " +
                    "Accept Gemma terms at huggingface.co, then retry."))
                conn.disconnect()
                return@withContext Result.failure()   // don't retry auth errors
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
            }

            val total = conn.contentLengthLong

            conn.inputStream.buffered(BUFFER_SIZE).use { input ->
                tmpFile.outputStream().buffered(BUFFER_SIZE).use { output ->
                    var downloaded = 0L
                    var lastPct    = -1
                    val buf = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                nm.notify(NOTIF_ID, buildNotification(pct,
                                    "$pct% · ${mb(downloaded)} / ${mb(total)} MB"))
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
            nm.notify(NOTIF_ID, buildNotification(-1, "Download error: ${e.message?.take(60)}"))
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun openConnectionWithRedirects(startUrl: String): HttpURLConnection {
        var url = startUrl
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false   // manual redirect to handle http→https
            conn.connectTimeout = 30_000
            conn.readTimeout    = 60_000
            // HuggingFace needs a browser-like User-Agent to avoid 403
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Android; OMNIX-Agent/1.0) AppleWebKit/537.36")
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399 && redirects < MAX_REDIRECTS) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = if (location.startsWith("http")) location
                      else URL(URL(url), location).toString()
                redirects++
            } else {
                break
            }
        }
        return conn
    }

    private fun mb(bytes: Long) = bytes / 1_048_576L

    private fun createChannel() {
        val ch = NotificationChannel(
            NOTIF_CHANNEL, "OMNIX Downloads", NotificationManager.IMPORTANCE_LOW
        )
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildNotification(percent: Int, statusText: String): android.app.Notification =
        NotificationCompat.Builder(context, NOTIF_CHANNEL)
            .setContentTitle("Downloading Gemma AI model")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(percent >= 0)   // dismiss automatically on error
            .build()
}
