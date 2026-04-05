package com.omnix.agent.executor

import android.content.Context
import android.content.Intent
import com.omnix.agent.core.OmnixAccessibilityService
import com.omnix.agent.database.*
import com.omnix.agent.improvements.SelfHealingSystem
import com.omnix.agent.ui.ConfirmationGate
import com.omnix.agent.ui.OverlayUI
import com.omnix.agent.voice.TTS
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

class SkillExecutor(
    private val a11y: OmnixAccessibilityService,
    private val context: Context
) {
    private val db = OmnixDatabase.getInstance(context)
    private val healer = SelfHealingSystem(a11y)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun executeSkill(
        skill: SkillEntity,
        params: Map<String, String>
    ): SkillResult {
        // Confirm if required
        if (skill.confirmationRequired) {
            val confirmed = ConfirmationGate.confirm(context, skill, params)
            if (!confirmed) return SkillResult.cancelled(skill.id)
        }

        val steps: List<SkillStep> = try {
            json.decodeFromString(skill.stepsJson)
        } catch (e: Exception) {
            return SkillResult.failure(skill.id, "Invalid skill definition: ${e.message}")
        }

        val ctx = ExecutionContext(params = params.toMutableMap())
        val startTime = System.currentTimeMillis()

        OverlayUI.show(context, "Running: ${skill.name}")

        for ((index, step) in steps.withIndex()) {
            val narration = step.toNarration(ctx)
            TTS.speak(narration, TTS.QUEUE_ADD)
            OverlayUI.showProgress(context, narration, steps.size, index + 1)

            val success = executeStep(step, ctx, skill)
            if (!success) {
                // Try self-healing
                val healed = healer.heal(step, ctx)
                if (!healed) {
                    OverlayUI.dismiss()
                    TTS.speak("Step failed: $narration", TTS.QUEUE_FLUSH)
                    val execMs = System.currentTimeMillis() - startTime
                    db.skillDao().recordFailure(skill.id)
                    recordHistory(skill, params, "failure", "Step failed: ${step.action}", false)
                    return SkillResult.failure(skill.id, "Step failed at: $narration")
                }
            }

            // Inter-step delay for human-like behavior
            delay(step.delayAfterMs)
        }

        OverlayUI.dismiss()
        TTS.speak("Done! ${skill.name} completed.", TTS.QUEUE_FLUSH)

        val execMs = System.currentTimeMillis() - startTime
        db.skillDao().recordSuccess(skill.id, execMs)
        recordHistory(skill, params, "success", null, skill.category == "banking")

        return SkillResult.success(skill.id, ctx.outputs)
    }

    private suspend fun executeStep(
        step: SkillStep,
        ctx: ExecutionContext,
        skill: SkillEntity
    ): Boolean = withContext(Dispatchers.Main) {
        return@withContext when (step.action) {
            "tap" -> {
                val node = resolveElement(step.element, ctx) ?: return@withContext false
                a11y.tap(node)
            }
            "type" -> {
                val node = resolveElement(step.element, ctx) ?: return@withContext false
                val text = resolveParam(step.value ?: "", ctx)
                a11y.typeText(node, text, step.clearFirst)
            }
            "scroll_down" -> {
                val node = resolveElement(step.element, ctx)
                if (node != null) a11y.scrollDown(node) else true
            }
            "scroll_up" -> {
                val node = resolveElement(step.element, ctx)
                if (node != null) a11y.scrollUp(node) else true
            }
            "wait" -> {
                delay(step.delayAfterMs.coerceAtLeast(500))
                true
            }
            "wait_element" -> {
                val id = step.element?.resourceId ?: return@withContext false
                a11y.waitForElement(id, step.timeoutMs) != null
            }
            "capture" -> {
                val node = resolveElement(step.element, ctx)
                val text = node?.text?.toString() ?: ""
                ctx.outputs[step.outputKey ?: "result"] = text
                true
            }
            "launch_app" -> {
                val pkg = resolveParam(step.value ?: "", ctx)
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    delay(1500) // Wait for app to open
                    true
                } else false
            }
            "press_back" -> { a11y.pressBack(); true }
            "press_home" -> { a11y.pressHome(); true }
            "swipe_down" -> {
                val dm = context.resources.displayMetrics
                a11y.swipe(dm.widthPixels / 2f, dm.heightPixels * 0.7f,
                    dm.widthPixels / 2f, dm.heightPixels * 0.3f)
                true
            }
            "swipe_up" -> {
                val dm = context.resources.displayMetrics
                a11y.swipe(dm.widthPixels / 2f, dm.heightPixels * 0.3f,
                    dm.widthPixels / 2f, dm.heightPixels * 0.7f)
                true
            }
            else -> {
                // Unknown action - try to find by text/resource id heuristically
                val node = resolveElement(step.element, ctx)
                if (node != null) a11y.tap(node) else false
            }
        }
    }

    private fun resolveElement(
        selector: ElementSelector?,
        ctx: ExecutionContext
    ) = selector?.let { sel ->
        a11y.findByResourceId(sel.resourceId)
            ?: sel.text?.let { a11y.findByText(resolveParam(it, ctx)) }
            ?: sel.contentDesc?.let { a11y.findByContentDesc(it) }
    }

    private fun resolveParam(value: String, ctx: ExecutionContext): String {
        return ctx.params.entries.fold(value) { acc, (k, v) -> acc.replace("{$k}", v) }
    }

    private suspend fun recordHistory(
        skill: SkillEntity,
        params: Map<String, String>,
        outcome: String,
        errorMsg: String?,
        isFinancial: Boolean
    ) {
        db.historyDao().insert(ActionHistoryEntity(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            skillId = skill.id,
            skillName = skill.name,
            paramsJson = params.toString(),
            outcome = outcome,
            errorMsg = errorMsg,
            isFinancial = isFinancial,
            retainDays = if (isFinancial) 90 else 30
        ))
    }
}

// ── Data classes ─────────────────────────────────────────────────────────────
@Serializable
data class SkillStep(
    val action: String,
    val element: ElementSelector? = null,
    val value: String? = null,
    val clearFirst: Boolean = false,
    val outputKey: String? = null,
    val delayAfterMs: Long = 200,
    val timeoutMs: Long = 8000,
    val narration: String = ""
) {
    fun toNarration(ctx: ExecutionContext): String {
        if (narration.isNotEmpty()) return narration
        return when (action) {
            "tap" -> "Tapping ${element?.resourceId ?: element?.text ?: "element"}"
            "type" -> "Typing ${value?.take(20) ?: "text"}"
            "wait" -> "Waiting..."
            "capture" -> "Reading value"
            "launch_app" -> "Opening app"
            else -> action.replace("_", " ").replaceFirstChar { it.uppercase() }
        }
    }
}

@Serializable
data class ElementSelector(
    val resourceId: String = "",
    val text: String? = null,
    val contentDesc: String? = null,
    val fallback1: ElementSelector? = null,
    val fallback2: ElementSelector? = null,
    val fallback3: ElementSelector? = null
)

data class ExecutionContext(
    val params: MutableMap<String, String>,
    val outputs: MutableMap<String, String> = mutableMapOf()
)

sealed class SkillResult {
    data class Success(val skillId: String, val outputs: Map<String, String>) : SkillResult()
    data class Failure(val skillId: String, val reason: String) : SkillResult()
    data class Cancelled(val skillId: String) : SkillResult()

    companion object {
        fun success(skillId: String, outputs: Map<String, String>) = Success(skillId, outputs)
        fun failure(skillId: String, reason: String) = Failure(skillId, reason)
        fun cancelled(skillId: String) = Cancelled(skillId)
    }
}
