package com.omnix.agent.executor

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.omnix.agent.core.OmnixAccessibilityService
import com.omnix.agent.database.*
import com.omnix.agent.improvements.SelfHealingSystem
import com.omnix.agent.ui.ConfirmationGate
import com.omnix.agent.ui.OverlayUI
import com.omnix.agent.voice.TTS
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
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
                val healed = healer.heal(step, ctx)
                if (!healed) {
                    OverlayUI.dismiss()
                    TTS.speak("Step failed: $narration", TTS.QUEUE_FLUSH)
                    db.skillDao().recordFailure(skill.id)
                    recordHistory(skill, params, "failure", "Step failed: ${step.action}", false)
                    return SkillResult.failure(skill.id, "Step failed at: $narration")
                }
            }

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
        val sel = step.resolvedElement()

        return@withContext when (step.action) {
            // ── tap by element selector ─────────────────────────────────────────
            "tap" -> {
                val node = resolveElement(sel, ctx) ?: return@withContext false
                a11y.tap(node)
            }
            // ── tap by text (flat field) ────────────────────────────────────────
            "tap_text" -> {
                val t = resolveParam(step.text ?: step.value ?: return@withContext false, ctx)
                a11y.findByText(t)?.let { a11y.tap(it) } ?: false
            }
            // ── tap by content description ──────────────────────────────────────
            "tap_content_desc" -> {
                val d = resolveParam(step.desc ?: step.contentDesc ?: return@withContext false, ctx)
                a11y.findByContentDesc(d)?.let { a11y.tap(it) } ?: false
            }
            // ── type into element ───────────────────────────────────────────────
            "type", "type_text" -> {
                val node = resolveElement(sel, ctx)
                    ?: a11y.findByResourceId(resolveParam(step.resourceId ?: return@withContext false, ctx))
                    ?: return@withContext false
                val text = resolveParam(step.value ?: "", ctx)
                a11y.typeText(node, text, step.clearFirst)
            }
            // ── launch app ─────────────────────────────────────────────────────
            "launch", "launch_app" -> {
                val pkg = resolveParam(step.packageName ?: step.value ?: return@withContext false, ctx)
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    delay(1500)
                    true
                } else false
            }
            // ── deep link ──────────────────────────────────────────────────────
            "deep_link" -> {
                val raw = resolveParam(step.uri ?: return@withContext false, ctx)
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(raw))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    delay(1500)
                    true
                } catch (_: Exception) { false }
            }
            // ── wait for element (by resource id) ──────────────────────────────
            "wait_for", "wait_element" -> {
                val id = step.resourceId ?: sel?.resourceId ?: return@withContext false
                a11y.waitForElement(resolveParam(id, ctx), step.timeoutMs) != null
            }
            // ── wait for element (by text) ─────────────────────────────────────
            "wait_for_text" -> {
                val t = resolveParam(step.text ?: step.value ?: return@withContext false, ctx)
                a11y.waitForText(t, step.timeoutMs) != null
            }
            // ── read text from element → output key ────────────────────────────
            "capture", "read_text" -> {
                val id = step.resourceId ?: sel?.resourceId
                val node = if (id != null) a11y.findByResourceId(resolveParam(id, ctx))
                           else resolveElement(sel, ctx)
                val text = node?.text?.toString() ?: ""
                ctx.outputs[step.outputKey ?: "result"] = text
                true
            }
            // ── read all visible text ──────────────────────────────────────────
            "read_screen_text" -> {
                val allText = a11y.getAllText().joinToString(" ") { it.second }
                ctx.outputs[step.outputKey ?: "result"] = allText
                true
            }
            // ── TTS speak with template ────────────────────────────────────────
            "speak" -> {
                val tmpl = step.template ?: step.value ?: return@withContext true
                val text = resolveParam(tmpl, ctx)
                withContext(Dispatchers.Main) { TTS.speak(text, TTS.QUEUE_ADD) }
                true
            }
            // ── dial phone ────────────────────────────────────────────────────
            "dial" -> {
                val phone = resolveParam(step.phone ?: step.value ?: return@withContext false, ctx)
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(intent); true } catch (_: Exception) { false }
            }
            // ── scrolling ─────────────────────────────────────────────────────
            "scroll_down" -> {
                val node = resolveElement(sel, ctx)
                if (node != null) a11y.scrollDown(node) else true
            }
            "scroll_up" -> {
                val node = resolveElement(sel, ctx)
                if (node != null) a11y.scrollUp(node) else true
            }
            // ── navigation ────────────────────────────────────────────────────
            "wait" -> { delay(step.delayAfterMs.coerceAtLeast(500)); true }
            "press_back" -> { a11y.pressBack(); true }
            "press_home" -> { a11y.pressHome(); true }
            "swipe_down" -> {
                val dm = context.resources.displayMetrics
                a11y.swipe(dm.widthPixels / 2f, dm.heightPixels * 0.7f,
                    dm.widthPixels / 2f, dm.heightPixels * 0.3f); true
            }
            "swipe_up" -> {
                val dm = context.resources.displayMetrics
                a11y.swipe(dm.widthPixels / 2f, dm.heightPixels * 0.3f,
                    dm.widthPixels / 2f, dm.heightPixels * 0.7f); true
            }
            // ── fallback: find by any available selector + tap ─────────────────
            else -> {
                val node = resolveElement(sel, ctx)
                if (node != null) a11y.tap(node) else false
            }
        }
    }

    private fun resolveElement(selector: ElementSelector?, ctx: ExecutionContext) =
        selector?.let { sel ->
            a11y.findByResourceId(sel.resourceId).takeIf { sel.resourceId.isNotEmpty() }
                ?: sel.text?.let { a11y.findByText(resolveParam(it, ctx)) }
                ?: sel.contentDesc?.let { a11y.findByContentDesc(it) }
        }

    private fun resolveParam(value: String, ctx: ExecutionContext): String =
        ctx.params.entries.fold(value) { acc, (k, v) -> acc.replace("{$k}", v) }
            .let { v -> ctx.outputs.entries.fold(v) { acc, (k, val2) -> acc.replace("{$k}", val2) } }

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

// ── SkillStep — understands BOTH the nested format and the flat skill-JSON format ──
@Serializable
data class SkillStep(
    val action: String,
    // Nested element selector (from Kotlin-generated skills)
    val element: ElementSelector? = null,
    // Flat fields used in pre-built skill JSON strings
    @SerialName("resource_id") val resourceId: String? = null,
    val text: String? = null,
    @SerialName("content_desc") val contentDesc: String? = null,
    val desc: String? = null,         // alias for content_desc in tap_content_desc steps
    // Action value / extra params
    val value: String? = null,
    @SerialName("package")  val packageName: String? = null,
    val uri: String? = null,
    val template: String? = null,
    val phone: String? = null,
    @SerialName("output_key") val outputKey: String? = null,
    @SerialName("timeout_ms") val timeoutMs: Long = 8000,
    @SerialName("clear_first") val clearFirst: Boolean = false,
    @SerialName("delay_after_ms") val delayAfterMs: Long = 200,
    val narration: String = ""
) {
    /** Synthesise an ElementSelector from flat or nested fields. */
    fun resolvedElement(): ElementSelector? {
        if (element != null) return element
        val rid = resourceId ?: ""
        val t   = text
        val cd  = contentDesc ?: desc
        if (rid.isEmpty() && t == null && cd == null) return null
        return ElementSelector(resourceId = rid, text = t, contentDesc = cd)
    }

    fun toNarration(ctx: ExecutionContext): String {
        if (narration.isNotEmpty()) return narration
        return when (action) {
            "tap"             -> "Tapping ${resourceId ?: text ?: element?.resourceId ?: "element"}"
            "tap_text"        -> "Tapping \"${text ?: value}\""
            "tap_content_desc"-> "Tapping ${desc ?: contentDesc}"
            "type", "type_text" -> "Typing ${(value ?: "").take(20)}"
            "launch", "launch_app" -> "Opening ${packageName ?: value}"
            "deep_link"       -> "Opening link"
            "wait", "wait_for", "wait_for_text", "wait_element" -> "Waiting…"
            "capture", "read_text", "read_screen_text" -> "Reading value"
            "speak"           -> "Speaking"
            "dial"            -> "Calling"
            else              -> action.replace("_", " ").replaceFirstChar { it.uppercase() }
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
