package com.omnix.agent.executor

import android.content.Context
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ai.IntentResult
import com.omnix.agent.core.OmnixAccessibilityService
import com.omnix.agent.database.*
import com.omnix.agent.skills.SkillLibraryManager
import com.omnix.agent.voice.TTS
import kotlinx.coroutines.*
import java.util.UUID

/**
 * Central coordinator for OMNIX.
 * Routes intents -> skills -> executor -> history.
 */
object OmnixOrchestrator {

    private var context: Context? = null
    private var db: OmnixDatabase? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Current app context
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
        scope.launch {
            // Check if any triggers fire on this screen
            EventTriggerEngine.checkScreenTriggers(packageName, className)
        }
    }

    fun onContentChanged(packageName: String) {
        scope.launch {
            EventTriggerEngine.checkContentTriggers(packageName)
        }
    }

    fun onScroll(packageName: String) {}

    // ── Voice intent handler ──────────────────────────────────────────────────
    fun handleVoiceIntent(intent: IntentResult, rawQuery: String) {
        scope.launch {
            val ctx = context ?: return@launch
            val a11y = OmnixAccessibilityService.instance ?: return@launch

            if (intent.confidence < 0.5f) {
                TTS.speak("I'm not sure I understand. Could you rephrase?", TTS.QUEUE_FLUSH)
                return@launch
            }

            val skill = SkillLibraryManager.findSkill(intent)
            if (skill == null) {
                TTS.speak("I don't know how to ${intent.intent} yet. I'll learn it.", TTS.QUEUE_FLUSH)
                // Trigger discovery for the relevant app
                return@launch
            }

            // Resolve parameters
            val params = resolveParameters(skill, intent)

            // Execute
            val executor = SkillExecutor(a11y, ctx)
            val result = executor.executeSkill(skill, params)

            when (result) {
                is SkillResult.Success -> {
                    val outputs = result.outputs
                    if (outputs.isNotEmpty()) {
                        val summary = outputs.entries.take(3).joinToString(", ") { "${it.key}: ${it.value}" }
                        TTS.speak("Done. $summary", TTS.QUEUE_FLUSH)
                    }
                    // Store to memory
                    storeMemory(rawQuery, skill, result)
                }
                is SkillResult.Failure -> {
                    TTS.speak("Sorry, that failed. ${result.reason}", TTS.QUEUE_FLUSH)
                }
                is SkillResult.Cancelled -> {
                    TTS.speak("Cancelled.", TTS.QUEUE_FLUSH)
                }
            }
        }
    }

    private fun resolveParameters(skill: SkillEntity, intent: IntentResult): Map<String, String> {
        return intent.entities.filterValues { it != null }.mapValues { it.value!! }
    }

    private suspend fun storeMemory(query: String, skill: SkillEntity, result: SkillResult) {
        val db = db ?: return
        val memory = MemoryEntity(
            content = "User asked: $query. Executed: ${skill.name}. Result: ${result.javaClass.simpleName}",
            memoryType = "episodic",
            importanceScore = 0.6f,
            embedding = ByteArray(0)
        )
        db.memoryDao().upsert(memory)
    }
}

// ── Stub for EventTriggerEngine ─────────────────────────────────────────────
object EventTriggerEngine {
    fun start(context: Context) {}
    fun stop() {}
    suspend fun checkScreenTriggers(packageName: String, className: String) {}
    suspend fun checkContentTriggers(packageName: String) {}
}
