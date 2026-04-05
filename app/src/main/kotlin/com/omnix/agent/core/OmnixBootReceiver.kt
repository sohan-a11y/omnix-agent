package com.omnix.agent.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.work.*
import com.omnix.agent.voice.OmnixVoiceService
import com.omnix.agent.discovery.OmnixDiscoveryService

class OmnixBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                startServices(context)
            }
        }
    }

    private fun startServices(context: Context) {
        // Request battery optimization exclusion
        requestBatteryExclusion(context)

        // Start voice service (microphone foreground service)
        val voiceIntent = Intent(context, OmnixVoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(voiceIntent)
        } else {
            context.startService(voiceIntent)
        }

        // Schedule discovery for installed apps via WorkManager
        val discoveryWork = OneTimeWorkRequestBuilder<BootDiscoveryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresBatteryNotLow(false) // Run even on low battery at boot
                    .build()
            )
            .setInitialDelay(30, java.util.concurrent.TimeUnit.SECONDS) // Wait for system
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("boot_discovery", ExistingWorkPolicy.KEEP, discoveryWork)
    }

    private fun requestBatteryExclusion(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            // Will be handled via onboarding flow - can't request directly without intent
        }
    }
}

class BootDiscoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Trigger incremental discovery for all installed apps not yet discovered
        val intent = Intent(applicationContext, OmnixDiscoveryService::class.java).apply {
            action = "com.omnix.agent.ACTION_BOOT_DISCOVERY"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
        return Result.success()
    }
}
