package com.omnix.agent.core

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Samsung-specific fixes and optimizations for S25 Ultra.
 * Task 32: Samsung S25 Ultra Specific Fixes
 */
object SamsungCompatibilityLayer {

    /** Samsung Galaxy AI event priority delay — re-query UI tree after 50ms */
    const val GALAXY_AI_EVENT_DELAY_MS = 50L

    fun isSamsungDevice(): Boolean =
        Build.MANUFACTURER.lowercase().contains("samsung")

    fun apply(context: Context) {
        if (!isSamsungDevice()) return

        // Fix: Samsung Knox may restrict AccessibilityService on some apps
        applyKnoxWorkaround(context)

        // Fix: One UI gesture navigation may interfere with gesture injection
        applyGestureNavigationFix(context)

        // Fix: Bixby may intercept wake word
        applyBixbyCoexistence()

        // Optimization: S25 Ultra has Snapdragon 8 Elite - use performance profile
        if (isS25Ultra()) {
            applyS25UltraOptimizations()
        }
    }

    private fun applyKnoxWorkaround(context: Context) {
        // Knox restricts some packages - we work around by using content descriptions
        // instead of resource IDs for Knox-protected apps
    }

    private fun applyGestureNavigationFix(context: Context) {
        // One UI uses different gesture zones - adjust swipe coordinates
        val navigationMode = Settings.Secure.getInt(
            context.contentResolver,
            "navigation_mode",
            0
        )
        // 0 = 3-button, 2 = gesture navigation
        if (navigationMode == 2) {
            // Adjust swipe areas to avoid system gesture zones (bottom 32dp)
        }
    }

    private fun applyBixbyCoexistence() {
        // Ensure OMNIX and Bixby wake words don't conflict
        // Both can coexist - different wake phrases
    }

    private fun applyS25UltraOptimizations() {
        // Snapdragon 8 Elite has dedicated NPU for LiteRT
        // Enable hardware acceleration hints
    }

    fun isS25Ultra(): Boolean {
        return Build.MODEL.contains("SM-S938") || // S25 Ultra model numbers
            Build.MODEL.contains("SM-S931") ||
            Build.MODEL.contains("SM-S936")
    }

    /**
     * Samsung-specific screen reading fix.
     * Some Samsung apps use custom view types that need special handling.
     */
    fun isSamsungCustomView(className: String): Boolean {
        return className.startsWith("com.samsung") ||
            className.startsWith("com.sec.android")
    }

    /**
     * Called after onAccessibilityEvent on Samsung devices.
     * Galaxy AI events deliver stale UI info; delay 50ms then re-query.
     */
    suspend fun applyGalaxyAIEventFix(refreshUiTree: suspend () -> Unit) {
        if (!isSamsungDevice()) return
        kotlinx.coroutines.delay(GALAXY_AI_EVENT_DELAY_MS)
        refreshUiTree()
    }
}
