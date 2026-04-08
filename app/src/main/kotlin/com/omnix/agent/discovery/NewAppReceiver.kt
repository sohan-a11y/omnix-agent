package com.omnix.agent.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Listens for newly installed or updated apps and triggers single-app discovery.
 * Full (bulk) discovery is handled by [AppDiscoveryWorker] via WorkManager.
 */
class NewAppReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return

        val action = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED ->
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false))
                    "com.omnix.agent.ACTION_DISCOVER_NEW" else null
            Intent.ACTION_PACKAGE_REPLACED -> "com.omnix.agent.ACTION_DISCOVER_UPDATE"
            else -> null
        } ?: return

        val svcIntent = Intent(context, OmnixDiscoveryService::class.java).apply {
            this.action = action
            putExtra("package_name", packageName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svcIntent)
        } else {
            context.startService(svcIntent)
        }
    }
}
