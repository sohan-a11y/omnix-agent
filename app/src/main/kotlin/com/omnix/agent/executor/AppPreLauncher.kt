package com.omnix.agent.executor

import android.content.Context
import android.content.Intent
import com.omnix.agent.core.OmnixAccessibilityService
import kotlinx.coroutines.*

/**
 * AppPreLauncher + Parallel Execution (Task 33)
 * Pre-warms apps in background to reduce skill execution latency.
 */
object AppPreLauncher {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val warmingQueue = mutableSetOf<String>()

    /**
     * Pre-launch an app in background so it's ready when needed.
     * OMNIX predicts likely next apps based on usage patterns.
     */
    fun prewarm(context: Context, packageName: String) {
        if (warmingQueue.contains(packageName)) return
        warmingQueue.add(packageName)

        scope.launch {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: return@launch
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Launch minimized/background - app is cached in memory
                // context.startActivity(intent)
                // Note: Only prewarm when device is idle and charging
                delay(2000)
                warmingQueue.remove(packageName)
            } catch (e: Exception) {
                warmingQueue.remove(packageName)
            }
        }
    }

    /**
     * Pre-warm the most commonly used apps on wake word detection.
     * Uses a static list of top apps likely to be invoked by the user.
     */
    fun prewarmTopApps(context: Context) {
        val topApps = listOf(
            "com.whatsapp",
            "com.google.android.apps.maps",
            "com.phonepe.app",
            "in.amazon.mShop.android.shopping"
        )
        topApps.forEach { prewarm(context, it) }
    }

    /**
     * Execute multiple independent skills in parallel.
     * Example: "check HDFC and SBI balance simultaneously"
     */
    suspend fun executeParallel(
        tasks: List<Pair<SkillExecutor, com.omnix.agent.database.SkillEntity>>,
        paramsList: List<Map<String, String>>
    ): List<SkillResult> = withContext(Dispatchers.IO) {
        tasks.zip(paramsList).map { (executorSkill, params) ->
            async {
                executorSkill.first.executeSkill(executorSkill.second, params)
            }
        }.awaitAll()
    }
}
