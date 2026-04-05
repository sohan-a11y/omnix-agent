package com.omnix.agent.skills

import android.content.Context
import androidx.work.*
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.voice.TTS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.Calendar

/**
 * Morning Briefing - scheduled daily task at user-configured time.
 * Reads balance, agenda, market update via OMNIX skills.
 */
class MorningBriefingSkill(private val context: Context) {

    fun schedule(hourOfDay: Int = 8, minute: Int = 0) {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
        }

        // If target time has passed today, schedule for tomorrow
        if (target.before(now)) target.add(Calendar.DATE, 1)

        val delayMs = target.timeInMillis - now.timeInMillis

        val work = OneTimeWorkRequestBuilder<MorningBriefingWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("morning_briefing", ExistingWorkPolicy.REPLACE, work)
    }
}

class MorningBriefingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = OmnixDatabase.getInstance(applicationContext)

        // Build morning summary
        val summary = buildString {
            append("Good morning! Here's your briefing. ")

            // Check scheduled tasks for today
            val tasks = db.taskDao().getActive()
            if (tasks.isNotEmpty()) {
                append("You have ${tasks.size} pending tasks. ")
            }

            // Recent action history
            append("Ready for your commands.")
        }

        TTS.speak(summary, TTS.QUEUE_FLUSH)

        // Reschedule for tomorrow
        MorningBriefingSkill(applicationContext).schedule()

        Result.success()
    }
}
