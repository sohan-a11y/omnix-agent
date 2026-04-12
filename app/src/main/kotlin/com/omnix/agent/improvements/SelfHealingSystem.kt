package com.omnix.agent.improvements

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.omnix.agent.core.OmnixAccessibilityService
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.executor.ElementSelector
import com.omnix.agent.executor.ExecutionContext
import com.omnix.agent.executor.SkillStep
import com.omnix.agent.database.OmnixDatabase
import org.json.JSONObject

class SelfHealingSystem(private val a11y: OmnixAccessibilityService) {

    private val TAG = "SelfHealingSystem"

    /**
     * Attempts to recover from a failed step using multiple strategies:
     * 1. Try fallback selectors
     * 2. Vision-based element finding
     * 3. Fuzzy text matching
     * 4. Gemma-guided adaptation
     */
    suspend fun heal(step: SkillStep, ctx: ExecutionContext): Boolean {
        val sel = step.element ?: return false

        // Strategy 1: Try fallback selectors
        val fallbacks = listOfNotNull(sel.fallback1, sel.fallback2, sel.fallback3)
        for (fallback in fallbacks) {
            val node = findBySelector(fallback) ?: continue
            return performAction(step.action, node, step, ctx)
        }

        // Strategy 2: Fuzzy text matching
        val allText = a11y.getAllText()
        if (sel.text != null) {
            val fuzzyMatch = allText.firstOrNull { (_, text) ->
                text.contains(sel.text, ignoreCase = true) ||
                    sel.text.contains(text, ignoreCase = true)
            }
            if (fuzzyMatch != null) {
                val node = a11y.findByText(fuzzyMatch.second)
                if (node != null) return performAction(step.action, node, step, ctx)
            }
        }

        // Strategy 3: Vision-based finding
        if (step.narration.isNotEmpty()) {
            val node = a11y.findByVisionLabel(step.narration)
            if (node != null) return performAction(step.action, node, step, ctx)
        }

        // Strategy 4: Gemma-guided — ask model which element to use, then perform the action
        val screenDump = a11y.getAllText().take(20)
            .joinToString("\n") { "${it.first}: ${it.second}" }

        val suggestion = GemmaInferenceEngine.generate(
            system = """You are an Android automation healer.
Given a failed step and current screen state, suggest which element to use.
Respond with ONLY valid JSON (no markdown): {"resourceId":"","text":"","contentDesc":""}""",
            user = "Failed step: ${step.action} on ${sel.resourceId}\nScreen:\n$screenDump"
        )

        // Parse Gemma's suggestion and attempt the action
        try {
            val raw = suggestion.substringAfter("{").let { "{$it" }
                .substringBefore("}").let { "$it}" }
            val json = JSONObject(raw)
            val resourceId = json.optString("resourceId").takeIf { it.isNotBlank() }
            val text       = json.optString("text").takeIf { it.isNotBlank() }
            val contentDesc = json.optString("contentDesc").takeIf { it.isNotBlank() }

            val node = resourceId?.let { a11y.findByResourceId(it) }
                ?: text?.let { a11y.findByText(it) }
                ?: contentDesc?.let { a11y.findByContentDesc(it) }

            if (node != null) {
                Log.i(TAG, "Gemma heal succeeded: resourceId=$resourceId text=$text")
                return performAction(step.action, node, step, ctx)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemma heal parse failed: ${e.message}")
        }

        Log.w(TAG, "All heal strategies exhausted for step: ${step.action}")
        return false
    }

    private fun findBySelector(sel: ElementSelector): AccessibilityNodeInfo? {
        return a11y.findByResourceId(sel.resourceId)
            ?: sel.text?.let { a11y.findByText(it) }
            ?: sel.contentDesc?.let { a11y.findByContentDesc(it) }
    }

    private fun performAction(
        action: String,
        node: AccessibilityNodeInfo,
        step: SkillStep,
        ctx: ExecutionContext
    ): Boolean {
        return when (action) {
            "tap" -> a11y.tap(node)
            "type" -> {
                val text = ctx.params[step.value] ?: step.value ?: ""
                a11y.typeText(node, text, step.clearFirst)
            }
            "scroll_down" -> a11y.scrollDown(node)
            "scroll_up" -> a11y.scrollUp(node)
            else -> a11y.tap(node)
        }
    }
}
