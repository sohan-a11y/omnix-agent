package com.omnix.agent.executor

import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ai.floatArrayToBytes
import com.omnix.agent.database.*
import com.omnix.agent.improvements.OmnixProfiler

/**
 * Records skill execution outcomes to the database.
 *
 * Separates persistence concerns from the orchestration logic so that
 * ChatHandler and VoiceHandler share a single recording path.
 */
class ExecutionRecorder(private val db: OmnixDatabase) {

    suspend fun record(
        skill: SkillEntity,
        params: Map<String, String>,
        result: SkillResult,
        success: Boolean
    ) {
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

    suspend fun storeMemory(query: String, skill: SkillEntity, result: SkillResult) {
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
}
