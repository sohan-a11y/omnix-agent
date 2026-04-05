package com.omnix.agent.skills

import android.content.Context
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.database.SkillEntity
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ai.IntentResult
import com.omnix.agent.ai.bytesToFloatArray
import com.omnix.agent.ai.floatArrayToBytes
import kotlinx.coroutines.*
import java.security.MessageDigest

object SkillLibraryManager {

    private lateinit var db: OmnixDatabase
    private val embeddingCache = mutableMapOf<String, FloatArray>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize(context: Context) {
        db = OmnixDatabase.getInstance(context)
        scope.launch { loadEmbeddings() }
    }

    private suspend fun loadEmbeddings() {
        // Load all skill embeddings into memory for fast semantic search
        db.skillDao().getAllActive().collect { skills ->
            skills.forEach { skill ->
                if (skill.embedding.isNotEmpty()) {
                    embeddingCache[skill.id] = bytesToFloatArray(skill.embedding)
                }
            }
        }
    }

    /**
     * 4-stage skill matching pipeline:
     * 1. Intent hash (O(1))
     * 2. Category filter
     * 3. Semantic embedding similarity
     * 4. Gemma re-ranking
     */
    suspend fun findSkill(intent: IntentResult): SkillEntity? {
        // Stage 1: Intent hash exact match (fastest)
        val hash = hashIntent(intent.intent, intent.entities)
        db.skillDao().findByIntentHash(hash)?.let { return it }

        // Stage 2: Category-filtered search
        val appCategory = inferCategory(intent)
        val candidates = db.skillDao().getByCategory(appCategory)
        if (candidates.isEmpty()) return null

        // Stage 3: Semantic similarity
        val queryEmbedding = GemmaInferenceEngine.generateEmbedding(intent.intent)
        val ranked = candidates
            .filter { embeddingCache.containsKey(it.id) }
            .map { skill ->
                val emb = embeddingCache[skill.id] ?: return@map Pair(0f, skill)
                Pair(cosineSimilarity(queryEmbedding, emb), skill)
            }
            .filter { it.first > 0.7f }
            .sortedByDescending { it.first }
            .map { it.second }

        if (ranked.isEmpty()) return candidates.firstOrNull()

        // Stage 4: Gemma re-rank top 3
        if (ranked.size > 1) {
            return gemmaRerank(intent, ranked.take(3))
        }

        return ranked.first()
    }

    private suspend fun gemmaRerank(intent: IntentResult, candidates: List<SkillEntity>): SkillEntity? {
        val candidateList = candidates.mapIndexed { i, s -> "${i + 1}. ${s.name}: ${s.intentPatternsJson}" }
            .joinToString("\n")

        val result = GemmaInferenceEngine.generate(
            system = "Select the best skill for the user intent. Respond with ONLY the number (1, 2, or 3).",
            user = "Intent: ${intent.intent}\nEntities: ${intent.entities}\n\nCandidates:\n$candidateList",
            maxTokens = 10
        )

        val idx = result.trim().firstOrNull()?.digitToIntOrNull()?.minus(1) ?: 0
        return candidates.getOrNull(idx) ?: candidates.first()
    }

    private fun inferCategory(intent: IntentResult): String {
        return when {
            intent.intent.contains("bank") || intent.intent.contains("balance") || intent.intent.contains("transfer") -> "banking"
            intent.intent.contains("pay") || intent.intent.contains("upi") -> "payments"
            intent.intent.contains("message") || intent.intent.contains("whatsapp") -> "messaging"
            intent.intent.contains("call") -> "messaging"
            intent.intent.contains("order") || intent.intent.contains("food") -> "food"
            else -> "other"
        }
    }

    private fun hashIntent(intent: String, entities: Map<String, String?>): String {
        val key = "$intent:${entities.keys.sorted().joinToString(",")}"
        return MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0f || normB == 0f) 0f
        else dot / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())).toFloat()
    }
}
