package com.omnix.agent.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.*
import kotlinx.coroutines.*
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.voice.VoicePipeline
import com.omnix.agent.executor.OmnixOrchestrator
import com.omnix.agent.skills.SkillLibraryManager
import com.omnix.agent.improvements.EventTriggerEngine

class OmnixAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        @Volatile var instance: OmnixAccessibilityService? = null
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    override fun onServiceConnected() {
        instance = this
        VoicePipeline.start(applicationContext)
        SkillLibraryManager.initialize(applicationContext)
        EventTriggerEngine.start(applicationContext)
    }

    override fun onInterrupt() {
        // Required - called when service is interrupted
    }

    override fun onDestroy() {
        instance = null
        VoicePipeline.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                OmnixOrchestrator.onScreenChanged(
                    event.packageName?.toString() ?: "",
                    event.className?.toString() ?: ""
                )
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Notify orchestrator of content changes for dynamic apps
                OmnixOrchestrator.onContentChanged(
                    event.packageName?.toString() ?: ""
                )
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                OmnixOrchestrator.onScroll(event.packageName?.toString() ?: "")
            }
        }
    }

    // ── Element Finding (works on FLAG_SECURE banking apps) ──────────────────
    fun findByResourceId(id: String): AccessibilityNodeInfo? =
        rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()

    fun findByText(text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val results = rootInActiveWindow?.findAccessibilityNodeInfosByText(text) ?: return null
        return if (exact) results.firstOrNull { it.text?.toString() == text }
        else results.firstOrNull()
    }

    fun findByContentDesc(desc: String): AccessibilityNodeInfo? =
        findByDesc(rootInActiveWindow, desc)

    private fun findByDesc(node: AccessibilityNodeInfo?, desc: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.contentDescription?.contains(desc, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) {
            findByDesc(node.getChild(i), desc)?.let { return it }
        }
        return null
    }

    fun getAllText(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        collectText(rootInActiveWindow, result)
        return result
    }

    private fun collectText(node: AccessibilityNodeInfo?, acc: MutableList<Pair<String, String>>) {
        node ?: return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrBlank()) acc.add(Pair(node.viewIdResourceName ?: "", t))
        for (i in 0 until node.childCount) collectText(node.getChild(i), acc)
    }

    fun dumpScreenTree(): List<NodeInfo> {
        val result = mutableListOf<NodeInfo>()
        dumpNode(rootInActiveWindow, result, 0)
        return result
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, acc: MutableList<NodeInfo>, depth: Int) {
        node ?: return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        acc.add(NodeInfo(
            resourceId = node.viewIdResourceName ?: "",
            text = node.text?.toString() ?: "",
            contentDesc = node.contentDescription?.toString() ?: "",
            className = node.className?.toString() ?: "",
            bounds = bounds,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            depth = depth
        ))
        for (i in 0 until node.childCount) dumpNode(node.getChild(i), acc, depth + 1)
    }

    // ── Actions ────────────────────────────────────────────────────────────────
    fun tap(node: AccessibilityNodeInfo): Boolean =
        node.performAction(ACTION_CLICK)

    fun longPress(node: AccessibilityNodeInfo): Boolean =
        node.performAction(ACTION_LONG_CLICK)

    fun typeText(node: AccessibilityNodeInfo, text: String, clear: Boolean = false): Boolean {
        if (clear) node.performAction(ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(ACTION_SET_TEXT, args)
    }

    fun scrollDown(node: AccessibilityNodeInfo): Boolean =
        node.performAction(ACTION_SCROLL_FORWARD)

    fun scrollUp(node: AccessibilityNodeInfo): Boolean =
        node.performAction(ACTION_SCROLL_BACKWARD)

    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun tapCoordinates(x: Float, y: Float, durationMs: Long = 50) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // ── Wait for element ───────────────────────────────────────────────────────
    suspend fun waitForElement(id: String, timeoutMs: Long = 8000): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            findByResourceId(id)?.let { return it }
            delay(150)
        }
        return null
    }

    suspend fun waitForText(text: String, timeoutMs: Long = 8000): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            findByText(text)?.let { return it }
            delay(150)
        }
        return null
    }

    // ── Vision-based element finding ──────────────────────────────────────────
    suspend fun findByVisionLabel(label: String): AccessibilityNodeInfo? {
        val bmp = takeScreenshotCompat() ?: return null
        val coords = GemmaInferenceEngine.findElementByVision(bmp, label) ?: return null
        val dm = resources.displayMetrics
        val x = (coords.xPct * dm.widthPixels / 100).toInt()
        val y = (coords.yPct * dm.heightPixels / 100).toInt()
        return findNodeNearCoordinates(x, y)
    }

    /**
     * Takes a screenshot using AccessibilityService API (Android 12+ / API 31+).
     * Result delivered asynchronously via callback on main thread.
     * Currently stubbed — use takeScreenshotCompat() (no-arg) for synchronous callers.
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
    fun takeScreenshotCompat(callback: (Bitmap?) -> Unit) {
        callback(null)
    }

    private fun takeScreenshotCompat(): Bitmap? {
        // Synchronous compat shim — callers that need a result use the callback
        // overload takeScreenshotCompat(callback) on API 31+ devices.
        return null
    }

    private fun findNodeNearCoordinates(x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            node.getBoundsInScreen(bounds)
            if (bounds.contains(x, y) && (node.isClickable || node.isEditable)) return node
            for (i in 0 until node.childCount) {
                search(node.getChild(i))?.let { return it }
            }
            return null
        }
        return search(rootInActiveWindow)
    }
}

// ── Data class for screen tree dumps ─────────────────────────────────────────
data class NodeInfo(
    val resourceId: String,
    val text: String,
    val contentDesc: String,
    val className: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val depth: Int
)
