package com.omnix.agent.executor

import android.content.Context
import android.content.Intent
import android.util.Log
import com.omnix.agent.ai.AppKnowledgeEngine
import com.omnix.agent.discovery.DiscoveryEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Resolves and launches Android apps from voice/chat intent hints.
 *
 * Resolution order:
 *  1. Exact package name if provided and installed.
 *  2. AppKnowledgeEngine (discovered user apps, fuzzy label match).
 *  3. Full scan of installed apps by display label.
 */
class AppLauncher(private val context: Context, private val scope: CoroutineScope) {

    private val TAG = "AppLauncher"

    /**
     * Attempt to launch an app and return a (packageName, displayLabel) pair, or null on failure.
     */
    fun resolveAndLaunch(
        rawQuery: String,
        packageHint: String?,
        appHint: String?
    ): Pair<String, String>? {
        // 1. Direct package name hint
        packageHint?.trim()?.takeIf { it.isNotBlank() }?.let { pkg ->
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                val label = runCatching {
                    val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
                    context.packageManager.getApplicationLabel(appInfo).toString()
                }.getOrDefault(appHint ?: pkg.substringAfterLast('.'))
                learnInBackground(pkg)
                return pkg to label
            }
        }

        // 2. AppKnowledgeEngine — fuzzy match across discovered apps
        val resolved = AppKnowledgeEngine.resolveLaunchableApp(
            query = rawQuery,
            packageHint = packageHint,
            appHint = appHint
        )
        if (resolved != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(resolved.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                learnInBackground(resolved.packageName)
                return resolved.packageName to resolved.name
            }
        }

        // 3. Full scan by display label
        val lower = rawQuery.lowercase()
        val pm = context.packageManager
        val allApps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
        val queryCore = lower
            .replace("open ", "")
            .replace("launch ", "")
            .replace("start ", "")
            .trim()
        val labelMatch = allApps.firstOrNull { app ->
            val label = pm.getApplicationLabel(app).toString().lowercase()
            lower.contains(label) || label.contains(queryCore)
        }
        if (labelMatch != null) {
            val launchIntent = pm.getLaunchIntentForPackage(labelMatch.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                val label = pm.getApplicationLabel(labelMatch).toString()
                Log.i(TAG, "Dynamic label-matched: ${labelMatch.packageName} ($label)")
                return labelMatch.packageName to label
            }
        }

        return null
    }

    /** Queue a background deep-analysis of [packageName] without blocking the caller. */
    fun learnInBackground(packageName: String) {
        if (packageName.isBlank()) return
        scope.launch {
            runCatching { DiscoveryEngine(context).deepAnalyzeApp(packageName) }
        }
    }
}
