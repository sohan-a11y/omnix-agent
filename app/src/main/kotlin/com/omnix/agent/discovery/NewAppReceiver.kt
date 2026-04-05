package com.omnix.agent.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit

class NewAppReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    // New install - trigger full discovery
                    scheduleDiscovery(context, packageName, isUpdate = false)
                }
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                // App updated - trigger differential discovery
                scheduleDiscovery(context, packageName, isUpdate = true)
            }
        }
    }

    private fun scheduleDiscovery(context: Context, packageName: String, isUpdate: Boolean) {
        val work = OneTimeWorkRequestBuilder<AppDiscoveryWorker>()
            .setInputData(workDataOf(
                "package_name" to packageName,
                "is_update" to isUpdate
            ))
            .setInitialDelay(if (isUpdate) 60L else 10L, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "discover_$packageName",
                ExistingWorkPolicy.REPLACE,
                work
            )
    }
}

class AppDiscoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val packageName = inputData.getString("package_name") ?: return Result.failure()
        val isUpdate = inputData.getBoolean("is_update", false)

        val intent = Intent(applicationContext, OmnixDiscoveryService::class.java).apply {
            action = if (isUpdate) {
                "com.omnix.agent.ACTION_DISCOVER_UPDATE"
            } else {
                "com.omnix.agent.ACTION_DISCOVER_NEW"
            }
            putExtra("package_name", packageName)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }

        return Result.success()
    }
}
