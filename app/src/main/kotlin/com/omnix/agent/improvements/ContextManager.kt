package com.omnix.agent.improvements

import com.omnix.agent.ai.GemmaInferenceEngine

/**
 * Context window manager — Task 21.
 * Tracks conversation token budget and compacts when approaching limits.
 *
 * Thresholds:
 *   80% → soft warning, summarize oldest half of history
 *   90% → hard compact, keep only last 3 turns + summary
 *
 * Gemma 4 E2B context window: 128,000 tokens (LiteRT-LM).
 * Conservative limit used: 32,000 tokens for safety margin.
 */
object ContextManager {

    private const val MAX_TOKENS = 32_000
    private const val SOFT_THRESHOLD = 0.80f
    private const val HARD_THRESHOLD = 0.90f

    private val history = mutableListOf<String>()
    private var summarySnapshot: String = ""
    private var currentTokens: Int = 0
    var goal: String = ""

    /** Append a new turn to context. Compacts automatically if needed. */
    suspend fun addTurn(turn: String) {
        history.add(turn)
        currentTokens += estimateTokenCount(turn)
        compactContextIfNeeded()
    }

    /** Returns the current effective context for a Gemma call. */
    fun buildContext(): String = buildString {
        if (summarySnapshot.isNotEmpty()) {
            append("[SUMMARY]\n$summarySnapshot\n[/SUMMARY]\n\n")
        }
        append(history.joinToString("\n"))
    }

    /** Rough estimate: 1 token ≈ 4 characters (English). */
    fun estimateTokenCount(text: String): Int = (text.length / 4).coerceAtLeast(1)

    /** Returns 0.0–1.0 fraction of context window used. */
    fun usageFraction(): Float = currentTokens.toFloat() / MAX_TOKENS

    suspend fun compactContextIfNeeded() {
        val fraction = usageFraction()
        when {
            fraction >= HARD_THRESHOLD -> hardCompact()
            fraction >= SOFT_THRESHOLD -> softCompact()
        }
    }

    /**
     * Soft compact: summarize oldest 50% of history, keep the rest.
     * Called at 80% threshold.
     */
    private suspend fun softCompact() {
        if (history.size < 4) return
        val halfIdx = history.size / 2
        val toSummarize = history.take(halfIdx)
        val summary = summarize(toSummarize)

        summarySnapshot = if (summarySnapshot.isNotEmpty()) {
            summarize(listOf(summarySnapshot, summary))
        } else {
            summary
        }

        repeat(halfIdx) { history.removeAt(0) }
        recalculateTokens()
    }

    /**
     * Hard compact: summarize everything, keep only last 3 turns.
     * Called at 90% threshold.
     */
    private suspend fun hardCompact() {
        val keepLast = history.takeLast(3)
        val toSummarize = history.dropLast(3)

        val summary = summarize(
            listOf(summarySnapshot) + toSummarize,
            prefix = "goal: $goal"
        )
        summarySnapshot = summary

        history.clear()
        history.addAll(keepLast)
        recalculateTokens()
    }

    private suspend fun summarize(
        parts: List<String>,
        prefix: String = ""
    ): String = GemmaInferenceEngine.compactContext(
        messages = parts.filter { it.isNotBlank() },
        goal = prefix.ifBlank { goal }
    )

    private fun recalculateTokens() {
        currentTokens = estimateTokenCount(summarySnapshot) +
            history.sumOf { estimateTokenCount(it) }
    }

    fun reset() {
        history.clear()
        summarySnapshot = ""
        currentTokens = 0
        goal = ""
    }
}
