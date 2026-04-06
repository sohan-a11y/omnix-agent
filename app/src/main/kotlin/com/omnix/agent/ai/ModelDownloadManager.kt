package com.omnix.agent.ai

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Downloads the Gemma 4 E2B model using Android DownloadManager.
 * Spec requires DownloadManager (not WorkManager+HTTP) for model download.
 */
object ModelDownloadManager {

    const val MODEL_URL = "https://huggingface.co/google/gemma-4-e2b-it-litert/resolve/main/gemma-4-e2b.litertlm"
    const val MODEL_FILENAME = "gemma-4-e2b.litertlm"
    private const val MODELS_DIR = "models"

    fun getModelFile(context: Context): File =
        File(context.filesDir, "$MODELS_DIR/$MODEL_FILENAME")

    fun isDownloaded(context: Context): Boolean = getModelFile(context).exists()

    /** Legacy alias kept for compatibility with OnboardingActivity. */
    fun isModelDownloaded(context: Context): Boolean = isDownloaded(context)

    fun getModelPath(context: Context): String = getModelFile(context).absolutePath

    fun startDownload(context: Context): Long {
        val modelsDir = File(context.filesDir, MODELS_DIR)
        modelsDir.mkdirs()

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(MODEL_URL)).apply {
            setTitle("OMNIX — Downloading AI model")
            setDescription("Downloading Gemma 4 E2B (~2.6 GB). Please stay connected to Wi-Fi.")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(getModelFile(context)))
            setAllowedOverMetered(false)
            setAllowedOverRoaming(false)
        }
        return dm.enqueue(request)
    }

    suspend fun awaitDownload(context: Context, downloadId: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id != downloadId) return

                    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                    val success = if (cursor.moveToFirst()) {
                        val status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                        )
                        status == DownloadManager.STATUS_SUCCESSFUL
                    } else false
                    cursor.close()

                    context.unregisterReceiver(this)
                    if (cont.isActive) cont.resume(success)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }
            cont.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (e: Exception) { /* already unregistered */ }
            }
        }

    fun getProgress(context: Context, downloadId: Long): Int {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
        if (!cursor.moveToFirst()) { cursor.close(); return -1 }
        val downloaded = cursor.getLong(
            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        )
        val total = cursor.getLong(
            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        )
        cursor.close()
        return if (total > 0) ((downloaded * 100) / total).toInt() else -1
    }
}
