package com.omnix.agent.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_MODEL_URL = "model_url"
        const val CHANNEL_ID = "model_download"
        const val NOTIFICATION_ID = 1001

        fun enqueue(context: Context, modelUrl: String) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL_URL to modelUrl))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("model_download", ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelUrl = inputData.getString(KEY_MODEL_URL) ?: return@withContext Result.failure()

        ModelDownloadManager.ensureModelDir(applicationContext)
        val outputFile = File(
            applicationContext.filesDir,
            "models/gemma-4-e2b.litertlm.tmp"
        )
        val finalFile = File(
            applicationContext.filesDir,
            "models/gemma-4-e2b.litertlm"
        )

        try {
            setForeground(createForegroundInfo(0))

            val connection = URL(modelUrl).openConnection() as HttpURLConnection
            connection.connect()
            val total = connection.contentLengthLong

            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var lastProgress = -1

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count

                        val progress = if (total > 0) (downloaded * 100 / total).toInt() else 0
                        if (progress != lastProgress) {
                            lastProgress = progress
                            setForeground(createForegroundInfo(progress))
                            setProgress(workDataOf("progress" to progress))
                        }
                    }
                }
            }

            outputFile.renameTo(finalFile)
            GemmaInferenceEngine.initialize(applicationContext)
            Result.success()
        } catch (e: Exception) {
            outputFile.delete()
            Result.retry()
        }
    }

    private fun createForegroundInfo(progress: Int): ForegroundInfo {
        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Model Download",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading Gemma 4 Model")
            .setContentText(if (progress < 100) "Downloading... $progress%" else "Complete!")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
