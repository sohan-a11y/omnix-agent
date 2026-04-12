package com.omnix.agent.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.voice.VoicePipeline
import com.omnix.agent.executor.OmnixOrchestrator
import com.omnix.agent.skills.SkillLibraryManager
import com.omnix.agent.improvements.EventTriggerEngine

class OmnixAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        @Volatile var instance: OmnixAccessibilityService? = null

        /**
         * Check if accessibility is currently connected and functional.
         * Used by ChatActivity and AutonomyLoop for graceful degradation.
         */
        fun isConnected(): Boolean = instance != null

        /**
         * Provide diagnostic info about the accessibility state.
         */
        fun getDiagnostics(): String = buildString {
            appendLine("A11y instance: ${if (instance != null) "CONNECTED" else "NULL"}")
            if (instance != null) {
                appendLine("Root window: ${instance?.rootInActiveWindow != null}")
                appendLine("Package: ${instance?.rootInActiveWindow?.packageName ?: "N/A"}")
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    override fun onServiceConnected() {
        instance = this
        android.util.Log.i("OmnixA11y", "✅ Accessibility service connected — instance set")
        SamsungCompatibilityLayer.apply(applicationContext)
        VoicePipeline.start(applicationContext)
        SkillLibraryManager.initialize(applicationContext)
        EventTriggerEngine.start(applicationContext)
    }

    override fun onInterrupt() {
        // Required - called when service is interrupted
        android.util.Log.w("OmnixA11y", "⚠️ Accessibility service interrupted")
    }

    override fun onDestroy() {
        android.util.Log.w("OmnixA11y", "🔴 Accessibility service destroyed")
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
                OmnixOrchestrator.onContentChanged(
                    event.packageName?.toString() ?: ""
                )
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                OmnixOrchestrator.onScroll(event.packageName?.toString() ?: "")
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: ""
                val text = event.text.joinToString(" ")
                EventTriggerEngine.onTextChanged(pkg, text)
            }
        }
    }

    // ── Element Finding (works on FLAG_SECURE banking apps) ──────────────────

    fun findByResourceId(id: String): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
        } catch (e: Exception) {
            android.util.Log.w("OmnixA11y", "findByResourceId error: ${e.message}")
            null
        }
    }

    fun findByText(text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        return try {
            val results = rootInActiveWindow?.findAccessibilityNodeInfosByText(text) ?: return null
            if (exact) results.firstOrNull { it.text?.toString() == text }
            else results.firstOrNull()
        } catch (e: Exception) {
            android.util.Log.w("OmnixA11y", "findByText error: ${e.message}")
            null
        }
    }

    fun findByContentDesc(desc: String): AccessibilityNodeInfo? =
        findByDesc(rootInActiveWindow, desc)

    private fun findByDesc(node: AccessibilityNodeInfo?, desc: String): AccessibilityNodeInfo? {
        node ?: return null
        try {
            if (node.contentDescription?.contains(desc, ignoreCase = true) == true) return node
            for (i in 0 until node.childCount) {
                findByDesc(node.getChild(i), desc)?.let { return it }
            }
        } catch (e: Exception) {
            // Node may have been recycled
        }
        return null
    }

    /**
     * Fuzzy text search — finds the best matching node even if text doesn't match exactly.
     * Useful when the AI asks for "Vicky" but the screen shows "vicky" or "Vicky Kumar".
     */
    fun findByTextFuzzy(target: String): AccessibilityNodeInfo? {
        // First try exact match
        findByText(target, exact = true)?.let { return it }

        // Then try contains match
        findByText(target, exact = false)?.let { return it }

        // Then try case-insensitive walk
        val root = rootInActiveWindow ?: return null
        return findFuzzyWalk(root, target.lowercase())
    }

    private fun findFuzzyWalk(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        node ?: return null
        try {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (text.contains(target) || desc.contains(target) ||
                target.contains(text) && text.isNotEmpty()) {
                if (node.isClickable) return node
            }
            for (i in 0 until node.childCount) {
                findFuzzyWalk(node.getChild(i), target)?.let { return it }
            }
        } catch (e: Exception) {
            // Node recycled
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
    fun tap(node: AccessibilityNodeInfo): Boolean {
        // Try clicking the node itself first
        if (node.isClickable) return node.performAction(ACTION_CLICK)

        // If node isn't clickable, walk up to find a clickable parent
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current.performAction(ACTION_CLICK)
            current = current.parent
        }

        // Last resort: tap coordinates
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            tapCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            return true
        }

        return false
    }

    fun longPress(node: AccessibilityNodeInfo): Boolean =
        node.performAction(ACTION_LONG_CLICK)

    fun typeText(node: AccessibilityNodeInfo, text: String, clear: Boolean = false): Boolean {
        // Focus the node first
        node.performAction(ACTION_FOCUS)
        node.performAction(ACTION_CLICK) // some fields need a click to activate

        if (clear) {
            // Select all then replace
            val selectArgs = Bundle().apply {
                putInt(ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
            }
            node.performAction(ACTION_SET_SELECTION, selectArgs)
        }

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

    /**
     * Wait for any screen change to happen (e.g., after a click).
     */
    suspend fun waitForScreenSettle(maxMs: Long = 2000) {
        delay(maxMs.coerceAtMost(3000))
    }

    // ── Compressed screen dump for Autonomy Loop ──────────────────────────────
    /**
     * Produces a token-efficient text representation of the current screen.
     * Only includes interactive elements (clickable, editable, scrollable)
     * and visible text labels. Caps at 40 elements. Used by AutonomyLoop
     * to give Gemma a concise view of the screen state.
     */
    fun getCompressedScreenDump(): ScreenDump {
        val root = rootInActiveWindow ?: return ScreenDump("", "", emptyList())
        val pkg = root.packageName?.toString() ?: ""
        val nodes = mutableListOf<CompressedNode>()
        collectCompressedNodes(root, nodes, 0)

        // Sort: interactive first, then by depth; cap at 40 for Gemma context
        val sorted = nodes.sortedWith(
            compareByDescending<CompressedNode> { it.isInteractive }
                .thenBy { it.depth }
        ).take(40)

        val lines = sorted.map { node ->
            buildString {
                append("[${node.className.substringAfterLast('.')}]")
                if (node.resourceId.isNotEmpty())
                    append(" id:\"${node.resourceId}\"")
                if (node.text.isNotEmpty())
                    append(" text:\"${node.text.take(40)}\"")
                if (node.contentDesc.isNotEmpty())
                    append(" desc:\"${node.contentDesc.take(40)}\"")
                if (node.isClickable) append(" [clickable]")
                if (node.isEditable) append(" [editable]")
                if (node.isScrollable) append(" [scrollable]")
                // Only include bounds for interactive elements to save tokens
                if (node.isInteractive) {
                    append(" bounds:[${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}]")
                }
            }
        }

        return ScreenDump(
            packageName = pkg,
            screenText = lines.joinToString("\n"),
            nodes = sorted
        )
    }

    private fun collectCompressedNodes(
        node: AccessibilityNodeInfo?,
        acc: MutableList<CompressedNode>,
        depth: Int
    ) {
        node ?: return
        try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Skip zero-size or off-screen nodes
            val dm = resources.displayMetrics
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                for (i in 0 until node.childCount) collectCompressedNodes(node.getChild(i), acc, depth + 1)
                return
            }
            if (bounds.top > dm.heightPixels || bounds.bottom < 0 ||
                bounds.left > dm.widthPixels || bounds.right < 0) {
                for (i in 0 until node.childCount) collectCompressedNodes(node.getChild(i), acc, depth + 1)
                return
            }

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val resId = node.viewIdResourceName ?: ""
            val isInteractive = node.isClickable || node.isEditable || node.isScrollable

            // Include node if it has text, description, a resource ID, or is interactive
            if (text.isNotEmpty() || desc.isNotEmpty() || resId.isNotEmpty() || isInteractive) {
                acc.add(CompressedNode(
                    resourceId = resId,
                    text = text,
                    contentDesc = desc,
                    className = node.className?.toString() ?: "",
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isInteractive = isInteractive,
                    depth = depth
                ))
            }

            for (i in 0 until node.childCount) collectCompressedNodes(node.getChild(i), acc, depth + 1)
        } catch (e: Exception) {
            // Node was recycled — skip it
        }
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
     * Takes a screenshot using AccessibilityService.takeScreenshot() on API 31+.
     * Returns null on older APIs or if the capture fails.
     */
    @Suppress("DEPRECATION")
    suspend fun takeScreenshotCompat(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val buf = result.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(buf, null)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            buf.close()
                            if (cont.isActive) cont.resume(bmp)
                        }
                        override fun onFailure(errorCode: Int) {
                            android.util.Log.w("OmnixA11y", "Screenshot failed: errorCode=$errorCode")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("OmnixA11y", "takeScreenshot error: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
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

data class CompressedNode(
    val resourceId: String,
    val text: String,
    val contentDesc: String,
    val className: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isInteractive: Boolean,
    val depth: Int
)

data class ScreenDump(
    val packageName: String,
    val screenText: String,
    val nodes: List<CompressedNode>
)

