package com.omnix.agent.executor

import android.content.Context
import android.util.Log
import com.omnix.agent.core.OmnixAccessibilityService
import com.omnix.agent.skills.HumanBehaviorSimulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Executes L1 UI-control commands via [OmnixAccessibilityService].
 *
 * Supports: click, click_text, click_desc, type, type_focused, tap, swipe, back, home, wait.
 * Uses a 4-tier self-healing click chain to maximise reliability across app UI changes.
 *
 * Extracted from AutonomyLoop to keep the main loop focused on ReAct coordination.
 */
class UICommandExecutor(
    private val a11y: OmnixAccessibilityService?,
    private val context: Context
) {
    private val TAG = "UICommandExecutor"

    suspend fun execute(cmd: String): String {
        val verb = cmd.split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: return "Empty command"
        return when (verb) {
            "click" -> {
                if (a11y == null) return missingA11y("click")
                val id = ResponseParser.extractArg1(cmd, "click")
                delay(HumanBehaviorSimulator.interStepDelayMs())
                smartClick(resourceId = id, text = null, desc = null)
            }
            "click_text" -> {
                if (a11y == null) return missingA11y("click_text")
                val text = ResponseParser.extractArg1(cmd, "click_text")
                delay(HumanBehaviorSimulator.interStepDelayMs())
                smartClick(resourceId = null, text = text, desc = null)
            }
            "click_desc" -> {
                if (a11y == null) return missingA11y("click_desc")
                val desc = ResponseParser.extractArg1(cmd, "click_desc")
                delay(HumanBehaviorSimulator.interStepDelayMs())
                smartClick(resourceId = null, text = null, desc = desc)
            }
            "type" -> {
                if (a11y == null) return missingA11y("type")
                val parts = Regex("""type\s+"?([^"\s]+)"?\s+"([^"]*)"""").find(cmd)
                if (parts != null) {
                    val id = parts.groupValues[1]
                    val text = parts.groupValues[2]
                    val node = withContext(Dispatchers.Main) { a11y.findByResourceId(id) }
                    if (node != null) {
                        delay(HumanBehaviorSimulator.interStepDelayMs())
                        withContext(Dispatchers.Main) { a11y.typeText(node, text, clear = true) }
                        delay(HumanBehaviorSimulator.typingDelayMs(text.length))
                        "Typed '$text' into $id"
                    } else elementNotFound("type", id)
                } else "Invalid type format. Use: type \"field_id\" \"text\""
            }
            "type_focused" -> {
                if (a11y == null) return missingA11y("type_focused")
                val text = ResponseParser.extractArg1(cmd, "type_focused")
                val dump = withContext(Dispatchers.Main) { a11y.getCompressedScreenDump() }
                val editable = dump.nodes.firstOrNull { it.isEditable }
                if (editable != null) {
                    val node = withContext(Dispatchers.Main) {
                        a11y.findByResourceId(editable.resourceId) ?: a11y.findByText(editable.text)
                    }
                    if (node != null) {
                        delay(HumanBehaviorSimulator.interStepDelayMs())
                        withContext(Dispatchers.Main) { a11y.typeText(node, text, clear = true) }
                        delay(HumanBehaviorSimulator.typingDelayMs(text.length))
                        "Typed '$text' into focused field"
                    } else elementNotFound("type_focused", "editable field")
                } else "No editable field on screen. Try clicking a text field first."
            }
            "tap" -> {
                val parts = cmd.removePrefix("tap").trim().split("\\s+".toRegex())
                if (parts.size >= 2 && a11y != null) {
                    val x = parts[0].toFloatOrNull()
                    val y = parts[1].toFloatOrNull()
                    if (x != null && y != null) {
                        delay(HumanBehaviorSimulator.interStepDelayMs())
                        val (jx, jy) = HumanBehaviorSimulator.touchOffset()
                        withContext(Dispatchers.Main) {
                            a11y.tapCoordinates(x + jx, y + jy, HumanBehaviorSimulator.tapDurationMs())
                        }
                        "Tapped ($x, $y)"
                    } else "Invalid coordinates"
                } else "tap needs x y + accessibility"
            }
            "swipe" -> {
                if (a11y == null) return "Swipe needs accessibility"
                val dir = cmd.removePrefix("swipe").trim().lowercase()
                val dm = context.resources.displayMetrics
                val cx = dm.widthPixels / 2f; val cy = dm.heightPixels / 2f
                delay(HumanBehaviorSimulator.interStepDelayMs())
                withContext(Dispatchers.Main) {
                    when (dir) {
                        "up" -> a11y.swipe(cx, cy + 400, cx, cy - 400, 300)
                        "down" -> a11y.swipe(cx, cy - 400, cx, cy + 400, 300)
                        "left" -> a11y.swipe(cx + 400, cy, cx - 400, cy, 300)
                        "right" -> a11y.swipe(cx - 400, cy, cx + 400, cy, 300)
                        else -> a11y.swipe(cx, cy + 400, cx, cy - 400, 300)
                    }
                }
                "Swiped $dir"
            }
            "back" -> {
                if (a11y != null) withContext(Dispatchers.Main) { a11y.pressBack() }
                else withContext(Dispatchers.IO) { Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 4")) }
                "Pressed back"
            }
            "home" -> {
                if (a11y != null) withContext(Dispatchers.Main) { a11y.pressHome() }
                else withContext(Dispatchers.IO) { Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 3")) }
                "Pressed home"
            }
            "wait" -> {
                delay(1500)
                "Waited 1.5s"
            }
            else -> "Unknown UI command: $cmd"
        }
    }

    /**
     * 4-tier self-healing click:
     *  1. Resource ID
     *  2. Fuzzy text match
     *  3. Content description
     *  4. Coordinate tap from screen dump bounds
     */
    private suspend fun smartClick(resourceId: String?, text: String?, desc: String?): String {
        val a = a11y ?: return missingA11y("click")

        if (!resourceId.isNullOrBlank()) {
            val node = withContext(Dispatchers.Main) { a.findByResourceId(resourceId) }
            if (node != null) {
                withContext(Dispatchers.Main) { a.tap(node) }
                return "Clicked $resourceId"
            }
            Log.w(TAG, "Self-heal: '$resourceId' not found, trying fallbacks")
        }

        val searchText = text ?: resourceId?.substringAfterLast("/")?.replace("_", " ")
        if (!searchText.isNullOrBlank()) {
            val node = withContext(Dispatchers.Main) { a.findByTextFuzzy(searchText) }
            if (node != null) {
                withContext(Dispatchers.Main) { a.tap(node) }
                return "Clicked text '$searchText' (self-healed)"
            }
        }

        val searchDesc = desc ?: text ?: resourceId?.substringAfterLast("/")?.replace("_", " ")
        if (!searchDesc.isNullOrBlank()) {
            val node = withContext(Dispatchers.Main) { a.findByContentDesc(searchDesc) }
            if (node != null) {
                withContext(Dispatchers.Main) { a.tap(node) }
                return "Clicked desc '$searchDesc' (self-healed)"
            }
        }

        // Tier 4: coordinate rescue
        val dump = withContext(Dispatchers.Main) { a.getCompressedScreenDump() }
        val target = text ?: desc ?: resourceId ?: ""
        val closest = dump.nodes.firstOrNull { n ->
            n.text.contains(target, ignoreCase = true) ||
            n.contentDesc.contains(target, ignoreCase = true) ||
            n.resourceId.contains(target, ignoreCase = true)
        }
        if (closest != null && closest.bounds.width() > 0) {
            val cx = closest.bounds.centerX().toFloat()
            val cy = closest.bounds.centerY().toFloat()
            withContext(Dispatchers.Main) { a.tapCoordinates(cx, cy) }
            return "Tapped at ($cx, $cy) near '$target' (coordinate rescue)"
        }

        return elementNotFound("click", target)
    }

    private fun missingA11y(cmd: String) =
        "Accessibility not available. Cannot $cmd. Use shell commands instead, or ask the user to enable Accessibility in Settings."

    private fun elementNotFound(cmd: String, target: String): String {
        return if (target.contains(":"))
            "Element '$target' not found. The app UI may have changed. Try click_text with the visible label instead."
        else
            "'$target' not found on screen. Try scrolling down, or check if the text is slightly different."
    }
}
