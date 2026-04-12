package com.omnix.agent.improvements

import android.content.Context
import android.util.Log
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.database.OmnixDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Proactive Intelligence — monitors patterns in action history and suggests automations.
 *
 * Runs every 30 minutes:
 * 1. Reads last 50 action history entries from Room
 * 2. Sends compact summary to Gemma for anomaly/pattern detection
 * 3. Logs suggestions; future: surface via notification or overlay
 */
class ProactiveIntelligence(private val context: Context) {

    private val TAG = "ProactiveIntelligence"
    private val db = OmnixDatabase.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    analyze()
                } catch (e: Exception) {
                    Log.w(TAG, "analyze() failed: ${e.message}")
                }
                delay(30 * 60 * 1000L) // every 30 minutes
            }
        }
    }

    private suspend fun analyze() {
        if (!GemmaInferenceEngine.isReady()) {
            Log.d(TAG, "Gemma not ready — skipping proactive analysis")
            return
        }

        // Fetch recent action history from DB
        val recentActions = try {
            db.historyDao().getRecent(50).first()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read action history: ${e.message}")
            return
        }

        if (recentActions.isEmpty()) {
            Log.d(TAG, "No action history yet — skipping proactive analysis")
            return
        }

        // Build a compact summary of recent actions
        val actionSummary = recentActions.take(30).joinToString("\n") { action ->
            "${action.skillName} [${action.appId}] → ${action.outcome}"
        }

        Log.d(TAG, "Running proactive analysis on ${recentActions.size} recent actions")

        val response = GemmaInferenceEngine.generate(
            system = """You are a smart assistant analyzing an Android user's app usage.
Given the last 30 actions, detect:
1. Repetitive tasks that could be automated
2. Unusual activity patterns (financial anomalies, off-hours usage)
3. Upcoming bill/payment reminders based on history

Respond with valid JSON only:
{"anomalies":["<description>"],"suggestions":["<automation idea>"],"reminders":["<reminder>"],"priority":"low|medium|high"}""",
            user = "Recent actions:\n$actionSummary"
        )

        try {
            val raw = response.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val json = JSONObject(raw)

            val priority = json.optString("priority", "low")
            val anomalies = json.optJSONArray("anomalies")
            val suggestions = json.optJSONArray("suggestions")
            val reminders = json.optJSONArray("reminders")

            if (priority != "low") {
                Log.i(TAG, "⚠ Proactive intelligence — priority=$priority")
                anomalies?.let { arr ->
                    for (i in 0 until arr.length()) Log.i(TAG, "  anomaly: ${arr.getString(i)}")
                }
                suggestions?.let { arr ->
                    for (i in 0 until arr.length()) Log.i(TAG, "  suggestion: ${arr.getString(i)}")
                }
                reminders?.let { arr ->
                    for (i in 0 until arr.length()) Log.i(TAG, "  reminder: ${arr.getString(i)}")
                }
            } else {
                Log.d(TAG, "Proactive analysis complete — no high-priority items")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse proactive analysis response: ${e.message}")
        }
    }

    fun stop() {
        scope.cancel()
    }
}
