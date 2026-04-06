package com.omnix.agent.improvements

import android.util.Log

/**
 * Timing instrumentation wrapper — Task 38.
 * Wraps every major Omnix operation for latency tracking.
 * Thread-safe singleton. Zero overhead when disabled.
 */
object OmnixProfiler {

    private const val TAG = "OmnixProfiler"
    var enabled: Boolean = true

    private data class Span(
        val name: String,
        val startMs: Long,
        var endMs: Long = 0L,
        val metadata: MutableMap<String, String> = mutableMapOf()
    ) {
        val durationMs get() = endMs - startMs
    }

    private val active = mutableMapOf<String, Span>()
    private val completed = ArrayDeque<Span>(100)

    /** Begin timing an operation. Call end(name) when done. */
    fun start(name: String, vararg tags: Pair<String, String>) {
        if (!enabled) return
        val span = Span(name, System.currentTimeMillis())
        tags.forEach { (k, v) -> span.metadata[k] = v }
        synchronized(active) { active[name] = span }
    }

    /** End timing and record result. Returns duration in ms. */
    fun end(name: String): Long {
        if (!enabled) return 0L
        val span = synchronized(active) { active.remove(name) } ?: return 0L
        span.endMs = System.currentTimeMillis()
        synchronized(completed) {
            completed.addLast(span)
            if (completed.size > 100) completed.removeFirst()
        }
        if (span.durationMs > 500) {
            Log.w(TAG, "SLOW [$name] ${span.durationMs}ms ${span.metadata}")
        } else {
            Log.d(TAG, "[$name] ${span.durationMs}ms")
        }
        return span.durationMs
    }

    /**
     * Measure a suspending block. Convenience for coroutine callers.
     *
     * Usage:
     *   val result = OmnixProfiler.measure("gemma.generate") { engine.generate(...) }
     */
    suspend fun <T> measure(name: String, block: suspend () -> T): T {
        start(name)
        return try {
            block()
        } finally {
            end(name)
        }
    }

    /** Inline measure for synchronous blocks. */
    inline fun <T> measureSync(name: String, block: () -> T): T {
        start(name)
        return try {
            block()
        } finally {
            end(name)
        }
    }

    /** Get p50/p95/p99 latencies for a named operation from recent history. */
    fun stats(name: String): ProfilerStats {
        val durations = synchronized(completed) {
            completed.filter { it.name == name && it.durationMs > 0 }.map { it.durationMs }
        }.sorted()
        if (durations.isEmpty()) return ProfilerStats(name, 0, 0, 0, 0)
        return ProfilerStats(
            name = name,
            count = durations.size,
            p50 = durations[durations.size / 2],
            p95 = durations[(durations.size * 0.95).toInt().coerceAtMost(durations.size - 1)],
            p99 = durations[(durations.size * 0.99).toInt().coerceAtMost(durations.size - 1)]
        )
    }

    data class ProfilerStats(
        val name: String,
        val count: Int,
        val p50: Long,
        val p95: Long,
        val p99: Long
    )

    fun reset() {
        synchronized(active) { active.clear() }
        synchronized(completed) { completed.clear() }
    }
}
