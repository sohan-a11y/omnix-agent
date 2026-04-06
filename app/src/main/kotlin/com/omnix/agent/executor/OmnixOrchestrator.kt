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
            val a11y = OmnixAccessibilityService.instance ?: return@launch

            // Compact context if needed before Gemma call
            ContextManager.goal = rawQuery
            ContextManager.addTurn("user: $rawQuery")

            val intent = OmnixProfiler.measure("gemma.intent") {
                GemmaInferenceEngine.extractIntent(rawQuery)
            }

            if (intent.confidence < 0.5f && !intent.ambiguous) {
                TTS.speak("I'm not sure I understand. Could you rephrase?", TTS.QUEUE_FLUSH)
                return@launch
            }

            if (intent.ambiguous && !intent.clarification.isNullOrBlank()) {
                TTS.speak(intent.clarification, TTS.QUEUE_FLUSH)
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
                TTS.speak("I don't know how to do that yet — learning it now.", TTS.QUEUE_FLUSH)
                // Trigger discovery for the relevant app (if mentioned)
                val appPackage = intent.entities["app"]
                if (!appPackage.isNullOrBlank()) {
                    val svcIntent = android.content.Intent(context,
                        com.omnix.agent.discovery.OmnixDiscoveryService::class.java).apply {
                        action = "com.omnix.agent.ACTION_DISCOVER_NEW"
                        putExtra("package_name", appPackage)
                    }
                    context.startService(svcIntent)
                }
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

            // Execute
            val executor = SkillExecutor(a11y, context)
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
