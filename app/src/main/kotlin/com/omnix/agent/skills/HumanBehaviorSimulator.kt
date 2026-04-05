package com.omnix.agent.skills

import kotlin.random.Random

/**
 * Anti-bot protection: simulates human-like interaction timing.
 * Randomizes delays between actions to avoid detection.
 */
object HumanBehaviorSimulator {

    /**
     * Returns a randomized typing delay per character (ms).
     * Simulates human typing speed variation.
     */
    fun typingDelayMs(charCount: Int): Long {
        val baseWpm = Random.nextInt(40, 80) // 40-80 WPM
        val msPerChar = (60_000.0 / (baseWpm * 5)).toLong()
        return msPerChar * charCount + Random.nextLong(100, 400)
    }

    /**
     * Returns a randomized tap duration (ms).
     * Human taps: 50-200ms
     */
    fun tapDurationMs(): Long = Random.nextLong(50, 200)

    /**
     * Returns a randomized inter-step delay (ms).
     * Humans pause 200-800ms between actions.
     */
    fun interStepDelayMs(): Long = Random.nextLong(200, 800)

    /**
     * Returns a slightly randomized touch offset.
     * Humans don't tap exactly the center of elements.
     */
    fun touchOffset(): Pair<Float, Float> {
        return Pair(
            Random.nextFloat() * 10 - 5, // -5 to +5 px
            Random.nextFloat() * 10 - 5
        )
    }

    /**
     * Returns a randomized scroll velocity.
     */
    fun scrollVelocity(): Float = Random.nextFloat() * 2f + 1f // 1-3x

    /**
     * Simulate reading time before action (for complex screens).
     * Returns delay in ms based on text length.
     */
    fun readingDelayMs(textLength: Int): Long {
        val readingSpeedCps = Random.nextInt(15, 25) // 15-25 chars/sec
        return (textLength * 1000L / readingSpeedCps).coerceIn(300, 3000)
    }

    /**
     * Occasionally introduce a "mistake and correct" pattern.
     * Returns true if we should simulate a typo.
     */
    fun shouldSimulateTypo(): Boolean = Random.nextFloat() < 0.05f // 5% chance
}
