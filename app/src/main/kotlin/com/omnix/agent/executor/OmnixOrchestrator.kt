package com.omnix.agent.executor

import android.content.Context
import android.util.Log
import com.omnix.agent.ai.AppKnowledgeEngine
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.core.OmnixAccessibilityService
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.discovery.DiscoveryEngine
import com.omnix.agent.improvements.ContextManager
import com.omnix.agent.improvements.EventTriggerEngine
import com.omnix.agent.improvements.OmnixProfiler
import com.omnix.agent.improvements.ProactiveAssistant
import com.omnix.agent.skills.CorrectionLearner
import com.omnix.agent.skills.SkillLibraryManager
import com.omnix.agent.voice.TTS
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Central coordinator for OMNIX.
 *
 * Delegates to focused sub-components:
 *  - [AppLauncher]       — resolves and opens Android apps
 *  - [ParameterResolver] — maps intent entities to skill parameters
 *  - [ExecutionRecorder] — persists execution outcomes to the database
 *  - [IntentRouter]      — classifies messages as commands vs. conversation
 */
object OmnixOrchestrator {

    private const val TAG = "OmnixOrch"
    private var context: Context? = null
    private var db: OmnixDatabase? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var knowledgeWarmupStarted = false

    @Volatile private var currentPackage = ""
    @Volatile private var currentScreen = ""

    // ── Sub-components (created in initialize) ────────────────────────────────
    private var appLauncher: AppLauncher? = null
    private var paramResolver: ParameterResolver? = null
    private var execRecorder: ExecutionRecorder? = null

    fun initialize(ctx: Context) {
        val appCtx = ctx.applicationContext
        context = appCtx
        val database = OmnixDatabase.getInstance(appCtx)
        db = database

        appLauncher = AppLauncher(appCtx, scope)
        paramResolver = ParameterResolver(appCtx)
        execRecorder = ExecutionRecorder(database)

        SkillLibraryManager.initialize(appCtx)

        if (!knowledgeWarmupStarted) {
            knowledgeWarmupStarted = true
            scope.launch {
                runCatching {
                    val knownApps = AppKnowledgeEngine.refresh(appCtx)
                    if (knownApps == 0) DiscoveryEngine(appCtx).enumerateApps()
                    GemmaInferenceEngine.loadAppKnowledge(appCtx)
                }
            }
        }
    }

    // ── Screen-change events ──────────────────────────────────────────────────
    fun onScreenChanged(packageName: String, className: String) {
        currentPackage = packageName
        currentScreen = className
        EventTriggerEngine.onScreenChanged(packageName, className)
    }

    fun onContentChanged(packageName: String) {
        EventTriggerEngine.onTextChanged(packageName, "")
    }

    @Suppress("UNUSED_PARAMETER")
    fun onScroll(packageName: String) {}

    // ── Voice intent handler ──────────────────────────────────────────────────
    fun handleVoiceIntent(rawQuery: String, ctx: Context? = null) {
        scope.launch {
            val resolvedCtx = ctx ?: context ?: return@launch
            val launcher = appLauncher ?: return@launch
            val resolver = paramResolver ?: return@launch
            val recorder = execRecorder ?: return@launch

            ContextManager.goal = rawQuery
            ContextManager.addTurn("user: $rawQuery")

            if (!GemmaInferenceEngine.isReady()) {
                TTS.speak(
                    "AI brain not loaded yet. Download the Gemma model from the OMNIX setup screen.",
                    TTS.QUEUE_FLUSH
                )
                return@launch
            }

            val intent = OmnixProfiler.measure("gemma.intent") {
                GemmaInferenceEngine.extractIntent(rawQuery)
            }

            if (intent == null || intent.intent == "parse_error") {
                TTS.speak(GemmaInferenceEngine.converse(rawQuery), TTS.QUEUE_FLUSH)
                return@launch
            }

            if (intent.intent == "unknown" || (intent.confidence < 0.4f && !intent.ambiguous)) {
                TTS.speak(GemmaInferenceEngine.converse(rawQuery), TTS.QUEUE_FLUSH)
                return@launch
            }

            if (intent.ambiguous && !intent.clarification.isNullOrBlank()) {
                TTS.speak(intent.clarification!!, TTS.QUEUE_FLUSH)
                return@launch
            }

            if (intent.intent in IntentRouter.launchIntents) {
                val launched = launcher.resolveAndLaunch(rawQuery, intent.entities["app"], intent.entities["app_name"])
                val name = launched?.second ?: intent.entities["app_name"] ?: intent.entities["app"] ?: "that app"
                TTS.speak(
                    if (launched != null) "Opening $name."
                    else if (name == "that app") "Which app would you like me to open?"
                    else "$name doesn't seem to be installed.",
                    TTS.QUEUE_FLUSH
                )
                return@launch
            }

            val overrideSkillId = CorrectionLearner.applyOverrides(intent)
            val skill = if (overrideSkillId != null) {
                db?.skillDao()?.getById(overrideSkillId)
            } else {
                OmnixProfiler.measure("skill.lookup") {
                    SkillLibraryManager.findSkill(intent, rawQuery)
                }
            }

            if (skill == null) {
                TTS.speak("Let me handle that for you.", TTS.QUEUE_FLUSH)
                val loopResult = AutonomyLoop.run(rawQuery, resolvedCtx)
                TTS.speak(
                    if (loopResult.success) loopResult.message else "I couldn't complete that. ${loopResult.message}",
                    TTS.QUEUE_FLUSH
                )
                return@launch
            }

            val anomalyScore = ProactiveAssistant.anomalyScore(skill.id, emptyMap())
            if (anomalyScore >= 0.7f && skill.confirmationRequired) {
                TTS.speak("This action looks unusual. Please confirm again.", TTS.QUEUE_FLUSH)
                return@launch
            }

            val a11y = OmnixAccessibilityService.instance
            if (a11y == null) {
                TTS.speak("Please enable OMNIX accessibility service in Settings first.", TTS.QUEUE_FLUSH)
                return@launch
            }

            val params = resolver.resolve(skill, intent)
            launcher.learnInBackground(skill.appId)
            val result = OmnixProfiler.measure("skill.execute.${skill.id}") {
                SkillExecutor(a11y, resolvedCtx).executeSkill(skill, params)
            }

            when (result) {
                is SkillResult.Success -> {
                    val summary = result.outputs.entries.take(3).joinToString(", ") { "${it.key}: ${it.value}" }
                    TTS.speak(if (summary.isNotBlank()) "Done. $summary" else "Done.", TTS.QUEUE_FLUSH)
                    recorder.storeMemory(rawQuery, skill, result)
                    recorder.record(skill, params, result, true)
                }
                is SkillResult.Failure -> {
                    TTS.speak("Sorry, that failed. ${result.reason}", TTS.QUEUE_FLUSH)
                    recorder.record(skill, params, result, false)
                }
                is SkillResult.Cancelled -> TTS.speak("Cancelled.", TTS.QUEUE_FLUSH)
            }
        }
    }

    // ── Chat (text in, text out) ───────────────────────────────────────────────

    suspend fun handleChatMessage(text: String): String {
        val ctx = context ?: return "OMNIX is not initialized yet."
        if (!GemmaInferenceEngine.isReady()) {
            return "The Gemma AI brain isn't loaded yet. Please download the model from the setup screen."
        }
        // Conversation — no command keywords
        if (!IntentRouter.looksLikeActionRequest(text.lowercase().trim())) {
            return GemmaInferenceEngine.converse(text)
        }

        // Gemma classifies everything — no fallback routing
        val intent = GemmaInferenceEngine.extractIntent(text)
        if (intent == null || intent.intent == "parse_error") {
            return "I couldn't understand that request. Please try rephrasing."
        }
        if (intent.ambiguous && !intent.clarification.isNullOrBlank()) return intent.clarification!!

        if (intent.intent in IntentRouter.launchIntents) {
            val launched = appLauncher?.resolveAndLaunch(text, intent.entities["app"], intent.entities["app_name"])
            val name = launched?.second ?: intent.entities["app_name"] ?: intent.entities["app"]
            return when {
                launched != null -> "Opening $name for you!"
                name != null -> "$name doesn't seem to be installed."
                else -> "Which app would you like me to open?"
            }
        }

        if (intent.intent == "unknown" || intent.confidence < 0.4f) {
            return GemmaInferenceEngine.converse(text)
        }

        val skill = SkillLibraryManager.findSkill(intent, text)
        if (skill != null) {
            val a11y = OmnixAccessibilityService.instance
                ?: return "I need Accessibility permission enabled to do that. Go to Settings → Accessibility → OMNIX."
            val params = paramResolver?.resolve(skill, intent) ?: emptyMap()
            appLauncher?.learnInBackground(skill.appId)
            return when (SkillExecutor(a11y, ctx).executeSkill(skill, params)) {
                is SkillResult.Success -> "Done! ${skill.name} completed."
                is SkillResult.Failure -> runAutonomyAndSummarize(text, ctx)
                is SkillResult.Cancelled -> "Cancelled."
            }
        }
        return runAutonomyAndSummarize(text, ctx)
    }

    fun handleChatMessageFlow(text: String): Flow<String> = flow {
        Log.i(TAG, "handleChatMessageFlow: '$text'")
        val ctx = context ?: run { emit("OMNIX is not initialized yet."); return@flow }
        if (!GemmaInferenceEngine.isReady()) {
            emit("The Gemma AI brain isn't loaded yet. Please download the model from the setup screen.")
            return@flow
        }

        // Pure conversation — not a command
        if (!IntentRouter.looksLikeActionRequest(text.lowercase().trim())) {
            emit(GemmaInferenceEngine.converse(text))
            return@flow
        }

        // All decisions go through Gemma — no fallback routing
        val intent = GemmaInferenceEngine.extractIntent(text)
        Log.i(TAG, "Intent: ${intent?.intent} conf=${intent?.confidence}")

        if (intent == null || intent.intent == "parse_error") {
            emit("I couldn't understand that request. Please try rephrasing.")
            return@flow
        }

        // App launch intent — Gemma said "open/launch X"
        if (intent.intent in IntentRouter.launchIntents) {
            val launched = appLauncher?.resolveAndLaunch(text, intent.entities["app"], intent.entities["app_name"])
            val name = launched?.second ?: intent.entities["app_name"] ?: intent.entities["app"]
            when {
                launched != null -> { emit("Opening $name for you!"); return@flow }
                name != null -> { emit("$name doesn't seem to be installed."); return@flow }
                else -> emit("Which app would you like me to open?")
            }
            return@flow
        }

        // Unknown intent with low confidence — Gemma can't determine what to do
        if (intent.intent == "unknown" || intent.confidence < 0.4f) {
            // Let the LLM converse rather than blindly executing
            emit(GemmaInferenceEngine.converse(text))
            return@flow
        }

        // Check pre-built skills — Gemma validates the match (see SkillLibraryManager.gemmaValidate)
        if (intent.confidence >= 0.45f) {
            val skill = SkillLibraryManager.findSkill(intent, text)
            if (skill != null) {
                val a11y = OmnixAccessibilityService.instance
                if (a11y != null) {
                    emit("⚡ Found skill: ${skill.name}. Executing...")
                    val params = paramResolver?.resolve(skill, intent) ?: emptyMap()
                    appLauncher?.learnInBackground(skill.appId)
                    when (SkillExecutor(a11y, ctx).executeSkill(skill, params)) {
                        is SkillResult.Success -> { emit("✅ Done! ${skill.name} completed."); return@flow }
                        is SkillResult.Failure -> emit("⚠️ Skill failed, letting AI handle it autonomously...")
                        is SkillResult.Cancelled -> { emit("Cancelled."); return@flow }
                    }
                }
            }
        }

        // AutonomyLoop — Gemma drives every step via ReAct reasoning
        val autonomyGoal = buildString {
            append(text)
            val ents = intent.entities.filterValues { !it.isNullOrBlank() }
            if (ents.isNotEmpty()) append(" [intent=${intent.intent}, ${ents.entries.joinToString(", ") { "${it.key}=${it.value}" }}]")
        }

        emit("🤖 Starting autonomous task...")
        AutonomyLoop.runAsFlow(autonomyGoal, ctx).collect { update ->
            when (update) {
                is AutonomyLoop.StepUpdate.Progress -> emit(update.message)
                is AutonomyLoop.StepUpdate.Completed -> {
                    val r = update.result
                    emit(if (r.success) "✅ ${r.message} (${r.steps} steps)" else "⚠️ ${r.message}")
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Execute a skill directly by ID — used by EventTriggerEngine and OmnixMesh. */
    suspend fun executeSkillById(skillId: String, params: Map<String, String>): Boolean {
        val ctx = context ?: return false
        val a11y = OmnixAccessibilityService.instance ?: return false
        val skill = db?.skillDao()?.getById(skillId) ?: return false
        return runCatching {
            SkillExecutor(a11y, ctx).executeSkill(skill, params) is SkillResult.Success
        }.getOrDefault(false)
    }

    private suspend fun runAutonomyAndSummarize(goal: String, ctx: Context): String {
        OmnixAccessibilityService.instance
            ?: return "I need Accessibility permission enabled to do that. Go to Settings → Accessibility → OMNIX."
        val result = AutonomyLoop.run(goal, ctx)
        return if (result.success) "✅ ${result.message} (${result.steps} steps)" else "⚠️ ${result.message}"
    }
}
