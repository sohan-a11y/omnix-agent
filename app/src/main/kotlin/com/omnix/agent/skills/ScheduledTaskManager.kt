package com.omnix.agent.skills

import android.content.Context
import androidx.work.*
import com.omnix.agent.executor.SkillExecutor
import com.omnix.agent.database.OmnixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Scheduled Task Manager — Task 25.
 * Schedules skill execution using WorkManager.
 * Supports: one_time, recurring_daily, recurring_interval, conditional.
 */
object ScheduledTaskManager {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ScheduledTask(
        val id: String = UUID.randomUUID().toString(),
        val skillId: String,
        val params: Map<String, String> = emptyMap(),
        val type: String, // "one_time" | "recurring_daily" | "recurring_interval" | "conditional"
        val triggerAtMs: Long = 0L,    // for one_time
        val dailyHour: Int = 8,        // for recurring_daily (24h)
        val dailyMinute: Int = 0,
        val intervalMinutes: Long = 60, // for recurring_interval
        val condition: String = "",     // for conditional — e.g. "battery<20"
        val label: String = ""
    )

    /**
     * Schedule a one-time skill execution at a specific epoch ms.
     */
    fun scheduleOneTime(context: Context, skillId: String, atMs: Long, params: Map<String, String> = emptyMap()): String {
        val task = ScheduledTask(skillId = skillId, type = "one_time", triggerAtMs = atMs, params = params)
        val delayMs = (atMs - System.currentTimeMillis()).coerceAtLeast(0L)

        val request = OneTimeWorkRequestBuilder<SkillWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(buildWorkData(task))
            .addTag(task.id)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            task.id, ExistingWorkPolicy.REPLACE, request
        )
        return task.id
    }

    /**
     * Schedule a skill to run every day at a specific hour:minute.
     */
    fun scheduleDaily(context: Context, skillId: String, hour: Int, minute: Int, params: Map<String, String> = emptyMap()): String {
        val task = ScheduledTask(skillId = skillId, type = "recurring_daily", dailyHour = hour, dailyMinute = minute, params = params)
        val delayMs = msUntilNextTime(hour, minute)

        val request = PeriodicWorkRequestBuilder<SkillWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(buildWorkData(task))
            .addTag(task.id)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            task.id, ExistingPeriodicWorkPolicy.KEEP, request
        )
        return task.id
    }

    /**
     * Schedule a skill to run every N minutes.
     */
    fun scheduleInterval(context: Context, skillId: String, intervalMinutes: Long, params: Map<String, String> = emptyMap()): String {
        val actual = intervalMinutes.coerceAtLeast(15L) // WorkManager minimum is 15 min
        val task = ScheduledTask(skillId = skillId, type = "recurring_interval", intervalMinutes = actual, params = params)

        val request = PeriodicWorkRequestBuilder<SkillWorker>(actual, TimeUnit.MINUTES)
            .setInputData(buildWorkData(task))
            .addTag(task.id)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            task.id, ExistingPeriodicWorkPolicy.KEEP, request
        )
        return task.id
    }

    /** Cancel a scheduled task by ID. */
    fun cancel(context: Context, taskId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(taskId)
    }

    private fun buildWorkData(task: ScheduledTask) = workDataOf(
        "task_json" to json.encodeToString(task)
    )

    private fun msUntilNextTime(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis - now.timeInMillis
    }
}

/**
 * WorkManager worker that executes a scheduled skill.
 */
class SkillWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskJson = inputData.getString("task_json") ?: return@withContext Result.failure()
        val task = try {
            json.decodeFromString<ScheduledTaskManager.ScheduledTask>(taskJson)
        } catch (_: Exception) { return@withContext Result.failure() }

        val db = OmnixDatabase.getInstance(applicationContext)
        val skill = db.skillDao().getById(task.skillId) ?: return@withContext Result.failure()

        try {
            val a11y = com.omnix.agent.core.OmnixAccessibilityService.instance
                ?: return@withContext Result.failure()
            val executor = SkillExecutor(a11y, applicationContext)
            executor.executeSkill(skill, task.params)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
