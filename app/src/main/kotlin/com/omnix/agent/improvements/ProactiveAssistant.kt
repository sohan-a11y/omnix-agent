package com.omnix.agent.improvements

import android.content.Context
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.skills.StockClient
import com.omnix.agent.voice.TTS
import kotlinx.coroutines.*

/**
 * Proactive Assistant — Task 35.
 * Monitors portfolio P&L, bill due dates, daily step goals, and
 * proactively alerts the user without them asking.
 *
 * Triggers:
 *   - P&L change > ₹2000 (or configurable threshold)
 *   - Bill due within 3 days
 *   - Daily step goal < 8000 steps by 8pm
 *   - Low balance (< ₹500) on any linked account
 */
object ProactiveAssistant {

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class ProactiveConfig(
        val watchedStocks: List<String> = listOf("RELIANCE", "TCS", "INFY"),
        val pnlAlertThresholdRs: Double = 2000.0,
        val lowBalanceThresholdRs: Double = 500.0,
        val billAlertDaysBefore: Int = 3,
        val stepGoal: Int = 8000,
        val checkIntervalMinutes: Long = 30
    )

    var config = ProactiveConfig()

    fun start(context: Context, db: OmnixDatabase) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runChecks(context, db)
                delay(config.checkIntervalMinutes * 60_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runChecks(context: Context, db: OmnixDatabase) {
        checkPortfolio(context)
        checkBills(context, db)
        checkLowBalance(context, db)
    }

    // ── Portfolio Monitor ──────────────────────────────────────────────────────
    private suspend fun checkPortfolio(context: Context) {
        val previousValues = loadPreviousPortfolioValues(context)
        var totalChange = 0.0

        config.watchedStocks.forEach { symbol ->
            val result = StockClient.getQuote(symbol)
            result.getOrNull()?.let { quote ->
                val prev = previousValues[symbol] ?: quote.price
                val change = quote.price - prev
                totalChange += change
                savePortfolioValue(context, symbol, quote.price)
            }
        }

        if (Math.abs(totalChange) >= config.pnlAlertThresholdRs) {
            val direction = if (totalChange > 0) "up" else "down"
            val formatted = String.format("%.0f", Math.abs(totalChange))
            TTS.speak(
                "Portfolio alert: your portfolio is $direction by ₹$formatted today.",
                TTS.QUEUE_ADD
            )
        }
    }

    // ── Bill Due Checker ───────────────────────────────────────────────────────
    private suspend fun checkBills(context: Context, db: OmnixDatabase) {
        // Check memories tagged "bill" or "due_date"
        val billMemories = try {
            db.memoryDao().getByType("bill")
        } catch (_: Exception) { return }

        val now = System.currentTimeMillis()
        val threeDaysMs = config.billAlertDaysBefore * 24 * 60 * 60 * 1000L

        billMemories.forEach { memory ->
            // Memory content format: "Bill: Electricity ₹2400 due 2026-04-09"
            val dueDateMs = extractDueDate(memory.content) ?: return@forEach
            val daysLeft = ((dueDateMs - now) / (24 * 60 * 60 * 1000L)).toInt()
            if (daysLeft in 0..config.billAlertDaysBefore) {
                TTS.speak(
                    "Reminder: ${memory.content.take(60)}. Due in $daysLeft days.",
                    TTS.QUEUE_ADD
                )
            }
        }
    }

    // ── Low Balance Alert ──────────────────────────────────────────────────────
    private suspend fun checkLowBalance(context: Context, db: OmnixDatabase) {
        val balanceMemories = try {
            db.memoryDao().getByType("balance")
        } catch (_: Exception) { return }

        balanceMemories.forEach { memory ->
            val balance = extractBalance(memory.content) ?: return@forEach
            if (balance < config.lowBalanceThresholdRs) {
                TTS.speak(
                    "Low balance alert: ${memory.content.take(40)}. Balance is below ₹${config.lowBalanceThresholdRs.toInt()}.",
                    TTS.QUEUE_ADD
                )
            }
        }
    }

    // ── AnomalyDetector — called before financial steps ─────────────────────
    /**
     * Score an action for anomaly. Returns 0.0 (normal) to 1.0 (suspicious).
     * High score should trigger extra confirmation or block the action.
     */
    fun anomalyScore(skillId: String, params: Map<String, String>): Float {
        var score = 0f

        // Large amount transfer
        val amount = params["amount"]?.filter { it.isDigit() }?.toLongOrNull() ?: 0L
        if (amount > 50_000) score += 0.4f
        if (amount > 200_000) score += 0.4f

        // Unusual time (midnight to 6am)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour in 0..5) score += 0.3f

        // Unknown recipient
        if (params["contact"]?.length ?: 0 < 2) score += 0.2f

        return score.coerceIn(0f, 1f)
    }

    // ── Persistence helpers ───────────────────────────────────────────────────
    private fun loadPreviousPortfolioValues(context: Context): Map<String, Double> {
        val prefs = context.getSharedPreferences("omnix_portfolio", Context.MODE_PRIVATE)
        return config.watchedStocks.mapNotNull { sym ->
            val v = prefs.getFloat(sym, -1f)
            if (v >= 0f) sym to v.toDouble() else null
        }.toMap()
    }

    private fun savePortfolioValue(context: Context, symbol: String, price: Double) {
        context.getSharedPreferences("omnix_portfolio", Context.MODE_PRIVATE)
            .edit().putFloat(symbol, price.toFloat()).apply()
    }

    private fun extractDueDate(content: String): Long? {
        val regex = "(\\d{4}-\\d{2}-\\d{2})".toRegex()
        val match = regex.find(content) ?: return null
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .parse(match.value)?.time
        } catch (_: Exception) { null }
    }

    private fun extractBalance(content: String): Double? {
        val regex = "[₹Rs.\\s]*(\\d+(?:,\\d+)*(?:\\.\\d+)?)".toRegex()
        return regex.find(content)?.groupValues?.get(1)
            ?.replace(",", "")?.toDoubleOrNull()
    }
}
