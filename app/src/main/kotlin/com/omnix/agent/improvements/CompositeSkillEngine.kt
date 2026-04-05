package com.omnix.agent.improvements

import android.content.Context
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.database.SkillEntity
import com.omnix.agent.ai.floatArrayToBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Detects when multiple skills are always used together
 * and creates composite skills for one-shot execution.
 *
 * Example: "check balance + transfer money" → composite "smart transfer"
 */
class CompositeSkillEngine(context: Context) {

    private val db = OmnixDatabase.getInstance(context)
    private val json = Json { ignoreUnknownKeys = true }

    // Track execution sequences
    private val executionHistory = mutableListOf<ExecutionSequence>()

    suspend fun trackExecution(taskId: String, skillsExecuted: List<String>) =
        withContext(Dispatchers.IO) {
            executionHistory.add(ExecutionSequence(
                taskId = taskId,
                skillIds = skillsExecuted,
                timestamp = System.currentTimeMillis()
            ))

            // After 10 executions, analyze patterns
            if (executionHistory.size >= 10) {
                analyzePatterns()
            }
        }

    private suspend fun analyzePatterns() {
        // Find sequences that appear 3+ times
        val sequences = executionHistory.groupBy { it.skillIds.take(3) }
            .filter { it.value.size >= 3 }

        sequences.forEach { (skillSequence, occurrences) ->
            if (skillSequence.size >= 2) {
                createCompositeSkill(skillSequence, occurrences.size)
            }
        }
    }

    private suspend fun createCompositeSkill(skillIds: List<String>, frequency: Int) {
        val skills = skillIds.mapNotNull { db.skillDao().getById(it) }
        if (skills.size < 2) return

        val compositeName = skills.joinToString(" → ") { it.name }
        val compositeSteps = skills.flatMap {
            json.decodeFromString<List<com.omnix.agent.executor.SkillStep>>(it.stepsJson)
        }

        val composite = SkillEntity(
            id = "composite_${UUID.randomUUID()}",
            appId = "composite",
            name = compositeName,
            type = "composite",
            category = skills.first().category,
            version = "1.0",
            intentPatternsJson = "[]",
            parametersJson = "{}",
            stepsJson = json.encodeToString(compositeSteps),
            confirmationRequired = skills.any { it.confirmationRequired },
            embedding = floatArrayToBytes(FloatArray(768)),
            intentHash = "",
            status = "active"
        )

        db.skillDao().upsert(composite)
    }
}

@Serializable
data class ExecutionSequence(
    val taskId: String,
    val skillIds: List<String>,
    val timestamp: Long
)
