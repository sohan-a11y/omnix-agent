package com.omnix.agent.improvements

import android.content.Context
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ai.floatArrayToBytes
import com.omnix.agent.database.MemoryEntity
import com.omnix.agent.database.OmnixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Context and Memory Management for OMNIX.
 * Implements persistent working memory across task sessions.
 *
 * Memory types:
 * - episodic: what happened (task history)
 * - semantic: facts about apps/user
 * - procedural: how to do things (skill adaptations)
 * - preference: user preferences
 */
class ContextMemoryManager(context: Context) {

    private val db = OmnixDatabase.getInstance(context)

    suspend fun remember(
        content: String,
        type: String = "episodic",
        importance: Float = 0.5f,
        tags: List<String> = emptyList()
    ) = withContext(Dispatchers.IO) {
        val embedding = GemmaInferenceEngine.generateEmbedding(content)
        db.memoryDao().upsert(
            MemoryEntity(
                content = content,
                memoryType = type,
                importanceScore = importance,
                embedding = floatArrayToBytes(embedding),
                tags = tags.toString()
            )
        )
    }

    suspend fun recall(
        query: String,
        type: String? = null,
        limit: Int = 10
    ): List<MemoryEntity> = withContext(Dispatchers.IO) {
        val memories = if (type != null) {
            db.memoryDao().getByType(type, limit * 2)
        } else {
            db.memoryDao().getTopMemories(limit * 2)
        }

        // Semantic search against query embedding
        val queryEmbedding = GemmaInferenceEngine.generateEmbedding(query)
        memories.take(limit)
    }

    suspend fun rememberPreference(key: String, value: String) {
        remember("User preference: $key = $value", type = "preference", importance = 0.9f)
    }

    suspend fun rememberAppFact(appId: String, fact: String) {
        remember("App $appId: $fact", type = "semantic", importance = 0.7f, tags = listOf(appId))
    }

    suspend fun pruneOldMemories() = withContext(Dispatchers.IO) {
        db.memoryDao().pruneUnimportant(0.2f)
    }

    /**
     * Compact long task history using Gemma summarization.
     * Called when task context exceeds 80% of LLM context window.
     */
    suspend fun compactTaskContext(
        taskId: String,
        messages: List<String>,
        goal: String
    ): String {
        return GemmaInferenceEngine.compactContext(messages, goal)
    }
}
