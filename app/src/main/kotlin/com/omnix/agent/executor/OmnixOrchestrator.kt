package com.omnix.agent.executor

import android.content.Context
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ai.IntentResult
import com.omnix.agent.ai.floatArrayToBytes
import com.omnix.agent.core.OmnixAccessibilityService
import com.omnix.agent.database.*
import com.omnix.agent.improvements.ContextManager
import com.omnix.agent.improvements.EventTriggerEngine
import com.omnix.agent.improvements.OmnixProfiler
import com.omnix.agent.improvements.ProactiveAssistant
import com.omnix.agent.skills.CorrectionLearner
import com.omnix.agent.skills.SkillLibraryManager
import com.omnix.agent.voice.TTS
import kotlinx.coroutines.*

/**
 * Central coordinator for OMNIX — Module 11 wired.
 * Routes intents → skills → executor → history.
 */
object OmnixOrchestrator {

    private var context: Context? = null
    private var db: OmnixDatabase? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var currentPackage = ""
    @Volatile private var currentScreen = ""

    fun initialize(ctx: Context) {
        context = ctx.applicationContext
        db = OmnixDatabase.getInstance(ctx)
    }

    // ── Screen change events ──────────────────────────────────────────────────
    fun onScreenChanged(packageName: String, className: String) {
        currentPackage = packageName
        currentScreen = className
        EventTriggerEngine.onScreenChanged(packageName, className)
    }

    fun onContentChanged(packageName: String) {
        EventTriggerEngine.onTextChanged(packageName, "")
    }

    fun onScroll(packageName: String) {}

    // ── Voice intent handler ──────────────────────────────────────────────────
    fun handleVoiceIntent(rawQuery: String, ctx: Context? = null) {
        scope.launch {
            val context = ctx ?: context ?: return@launch
            val a11y by lazy { OmnixAccessibilityService.instance }

            ContextManager.goal = rawQuery
            ContextManager.addTurn("user: $rawQuery")

            if (!GemmaInferenceEngine.isReady()) {
                TTS.speak("AI brain not loaded yet. Download the Gemma model from the OMNIX setup screen.", TTS.QUEUE_FLUSH)
                return@launch
            }

            val intent = OmnixProfiler.measure("gemma.intent") {
                GemmaInferenceEngine.extractIntent(rawQuery)
            }

            // If intent extraction failed entirely or is a parse error, fall back to conversational reply
            if (intent == null || intent.intent == "parse_error") {
                val reply = GemmaInferenceEngine.converse(rawQuery)
                TTS.speak(reply, TTS.QUEUE_FLUSH)
                return@launch
            }

            // Low confidence or truly unknown → conversational fallback via Gemma
            if (intent.intent == "unknown" || (intent.confidence < 0.4f && !intent.ambiguous)) {
                val reply = GemmaInferenceEngine.converse(rawQuery)
                TTS.speak(reply, TTS.QUEUE_FLUSH)
                return@launch
            }

            if (intent.ambiguous && !intent.clarification.isNullOrBlank()) {
                TTS.speak(intent.clarification!!, TTS.QUEUE_FLUSH)
                return@launch
            }

            // ── Direct launch_app — open the app, no skill needed ────────────
            if (intent.intent == "launch_app") {
                val pkg  = intent.entities["app"]
                val name = intent.entities["app_name"] ?: pkg ?: "that app"
                if (pkg != null) {
                    val li = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (li != null) {
                        li.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(li)
                        TTS.speak("Opening $name.", TTS.QUEUE_FLUSH)
                    } else {
                        TTS.speak("$name doesn't seem to be installed.", TTS.QUEUE_FLUSH)
                    }
                } else {
                    TTS.speak("Which app would you like me to open?", TTS.QUEUE_FLUSH)
                }
                return@launch
            }

            // Apply correction overrides before skill lookup (Task 14)
            val overrideSkillId = CorrectionLearner.applyOverrides(intent)
            val skill = if (overrideSkillId != null) {
                db?.skillDao()?.getById(overrideSkillId)
            } else {
                OmnixProfiler.measure("skill.lookup") {
                    SkillLibraryManager.findSkill(intent)
                }
            }

            if (skill == null) {
                // Fall back to Gemma conversational response instead of dead-end message
                val reply = GemmaInferenceEngine.converse(rawQuery)
                TTS.speak(reply, TTS.QUEUE_FLUSH)
                return@launch
            }

            // Resolve parameters
            val params = resolveParameters(context, skill, intent)

            // Anomaly check before financial actions (Task 35)
            val anomalyScore = ProactiveAssistant.anomalyScore(skill.id, params)
            if (anomalyScore >= 0.7f && skill.confirmationRequired) {
                TTS.speak("This action looks unusual. Please confirm again.", TTS.QUEUE_FLUSH)
                return@launch
            }

            // Execute (UI automation skills need accessibility service)
            val a11yService = a11y
            if (a11yService == null) {
                TTS.speak("Please enable OMNIX accessibility service in Settings first.", TTS.QUEUE_FLUSH)
                return@launch
            }
            val executor = SkillExecutor(a11yService, context)
            val result = OmnixProfiler.measure("skill.execute.${skill.id}") {
                executor.executeSkill(skill, params)
            }

            when (result) {
                is SkillResult.Success -> {
                    val outputs = result.outputs
                    if (outputs.isNotEmpty()) {
                        val summary = outputs.entries.take(3).joinToString(", ") { "${it.key}: ${it.value}" }
                        TTS.speak("Done. $summary", TTS.QUEUE_FLUSH)
                    } else {
                        TTS.speak("Done.", TTS.QUEUE_FLUSH)
                    }
                    storeMemory(rawQuery, skill, result)
                    recordExecution(skill, params, result, true)
                }
                is SkillResult.Failure -> {
                    TTS.speak("Sorry, that failed. ${result.reason}", TTS.QUEUE_FLUSH)
                    recordExecution(skill, params, result, false)
                }
                is SkillResult.Cancelled -> {
                    TTS.speak("Cancelled.", TTS.QUEUE_FLUSH)
                }
            }
        }
    }

    // ── Chat (text in, text out) ───────────────────────────────────────────────

    // Command verbs that suggest an action request rather than a question
    private val commandVerbs = setOf(
        "open", "launch", "start", "call", "phone", "dial",
        "send", "message", "text", "whatsapp",
        "play", "navigate", "go to", "take me to",
        "set alarm", "set reminder", "remind me",
        "search", "find", "look up",
        "transfer", "pay", "send money",
        "take photo", "take picture",
        "turn on", "turn off", "enable", "disable"
    )

    /**
     * Handle a typed/spoken message from ChatActivity.
     *
     * Strategy:
     * 1. If looks like a command (starts with action verb) → extractIntent → execute → text result
     * 2. Everything else → converse() directly (single Gemma inference, fast)
     *
     * Only ONE Gemma inference per message — no double-run.
     */
    suspend fun handleChatMessage(text: String): String {
        val ctx = context ?: return "OMNIX is not initialized yet."

        if (!GemmaInferenceEngine.isReady()) {
            return "The Gemma AI brain isn't loaded yet. Please download the model from the setup screen."
        }

        val lower = text.lowercase().trim()
        val looksLikeCommand = commandVerbs.any { lower.startsWith(it) }

        if (!looksLikeCommand) {
            // Pure conversation — single Gemma call
            return GemmaInferenceEngine.converse(text)
        }

        // Command path — extract intent then execute
        val intent = GemmaInferenceEngine.extractIntent(text)
        if (intent == null || intent.intent == "parse_error" || intent.intent == "unknown" || intent.confidence < 0.45f) {
            return GemmaInferenceEngine.converse(text)
        }
        if (intent.ambiguous && !intent.clarification.isNullOrBlank()) {
            return intent.clarification!!
        }

        return when (intent.intent) {
            "launch_app" -> {
                val pkg  = intent.entities["app"]
                val name = intent.entities["app_name"] ?: pkg ?: "that app"
                if (pkg != null) {
                    val li = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    if (li != null) {
                        li.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(li)
                        "Opening $name for you!"
                    } else {
                        "$name doesn't seem to be installed."
                    }
                } else {
                    "Which app would you like me to open?"
                }
            }
            else -> {
                val skill = SkillLibraryManager.findSkill(intent)
                if (skill == null) {
                    GemmaInferenceEngine.converse(text)
                } else {
                    val a11y = OmnixAccessibilityService.instance
                    if (a11y == null) {
                        "I need Accessibility permission enabled to do that. Go to Settings → Accessibility → OMNIX."
                    } else {
                        val params = resolveParameters(ctx, skill, intent)
                        val executor = SkillExecutor(a11y, ctx)
                        when (val result = executor.executeSkill(skill, params)) {
                            is SkillResult.Success -> "Done! ${skill.name} completed."
                            is SkillResult.Failure -> "That didn't work: ${result.reason}"
                            is SkillResult.Cancelled -> "Cancelled."
                        }
                    }
                }
            }
        }
    }

    /** Execute a skill directly by ID — used by EventTriggerEngine and OmnixMesh. */
    suspend fun executeSkillById(skillId: String, params: Map<String, String>): Boolean {
        val ctx = context ?: return false
        val a11y = OmnixAccessibilityService.instance ?: return false
        val db = db ?: return false
        val skill = db.skillDao().getById(skillId) ?: return false
        return try {
            val executor = SkillExecutor(a11y, ctx)
            val result = executor.executeSkill(skill, params)
            result is SkillResult.Success
        } catch (_: Exception) { false }
    }

    private suspend fun resolveParameters(
        context: Context,
        skill: SkillEntity,
        intent: IntentResult
    ): Map<String, String> {
        val base = intent.entities.filterValues { it != null }.mapValues { it.value!! }.toMutableMap()

        // Resolve contact name → phone number if needed
        val contactName = base["contact"]
        if (!contactName.isNullOrBlank() && !contactName.all { it.isDigit() }) {
            val contact = com.omnix.agent.skills.ContactsReader.resolve(context, contactName)
            contact?.phone?.let { base["phone"] = it }
        }
        return base
    }

    private suspend fun storeMemory(query: String, skill: SkillEntity, result: SkillResult) {
        val db = db ?: return
        val emb = GemmaInferenceEngine.generateEmbedding(query)
        db.memoryDao().upsert(
            MemoryEntity(
                content = "User: $query → ${skill.name} → ${result.javaClass.simpleName}",
                memoryType = "episodic",
                importanceScore = if (skill.category == "banking") 0.9f else 0.6f,
                embedding = floatArrayToBytes(emb)
            )
        )
    }

    private suspend fun recordExecution(
        skill: SkillEntity,
        params: Map<String, String>,
        result: SkillResult,
        success: Boolean
    ) {
        val db = db ?: return
        val durationMs = OmnixProfiler.stats("skill.execute.${skill.id}").p50
        db.executionHistoryDao().insert(
            ExecutionHistoryEntity(
                id = java.util.UUID.randomUUID().toString(),
                skillId = skill.id,
                skillName = skill.name,
                inputParamsJson = params.toString(),
                outputJson = "{}",
                outcome = if (success) "success" else "failure",
                executedAt = System.currentTimeMillis(),
                durationMs = durationMs
            )
        )
        if (success) db.skillDao().recordSuccess(skill.id, durationMs)
        else db.skillDao().recordFailure(skill.id)
    }
}
