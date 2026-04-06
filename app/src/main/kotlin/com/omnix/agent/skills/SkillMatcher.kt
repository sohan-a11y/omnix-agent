package com.omnix.agent.skills

import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ai.bytesToFloatArray
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.database.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Semantic skill matcher — Task 13.
 * Two-phase match: embedding cosine similarity → Gemma re-rank.
 */
object SkillMatcher {

    /**
     * Find the best skill for the given intent string.
     * Returns null if no skill exceeds the confidence threshold.
     */
    suspend fun findBestSkill(
        intent: String,
        db: OmnixDatabase,
        topK: Int = 5,
        threshold: Float = 0.65f
    ): SkillEntity? = withContext(Dispatchers.IO) {
        val allSkills = db.skillDao().getAllActive()
            .let { flow ->
                // Collect once — we need snapshot, not streaming
                var snapshot: List<SkillEntity> = emptyList()
                try {
                    // Use the DAO's blocking equivalent for a one-shot snapshot
                    snapshot = db.skillDao().getByCategory("") // returns all if empty
                } catch (_: Exception) {}
                snapshot
            }

        if (allSkills.isEmpty()) return@withContext null

        // Phase 1: embedding cosine similarity
        val queryEmb = GemmaInferenceEngine.generateEmbedding(intent)
        val scored = allSkills.mapNotNull { skill ->
            val skillEmb = try { bytesToFloatArray(skill.embedding) } catch (_: Exception) { return@mapNotNull null }
            val sim = cosineSimilarity(queryEmb, skillEmb)
            if (sim >= threshold) skill to sim else null
        }.sortedByDescending { it.second }

        if (scored.isEmpty()) return@withContext null
        if (scored.size == 1) return@withContext scored.first().first

        // Phase 2: Gemma re-rank top-K candidates
        val candidates = scored.take(topK)
        return@withContext rerank(intent, candidates.map { it.first }) ?: candidates.first().first
    }

    /**
     * Return all skills matching the intent above the threshold, ranked.
     */
    suspend fun findMatchingSkills(
        intent: String,
        db: OmnixDatabase,
        topK: Int = 10,
        threshold: Float = 0.55f
    ): List<Pair<SkillEntity, Float>> = withContext(Dispatchers.IO) {
        val queryEmb = GemmaInferenceEngine.generateEmbedding(intent)
        db.skillDao().getByCategory("").mapNotNull { skill ->
            val emb = try { bytesToFloatArray(skill.embedding) } catch (_: Exception) { return@mapNotNull null }
            val sim = cosineSimilarity(queryEmb, emb)
            if (sim >= threshold) skill to sim else null
        }.sortedByDescending { it.second }.take(topK)
    }

    private suspend fun rerank(intent: String, candidates: List<SkillEntity>): SkillEntity? {
        if (!GemmaInferenceEngine.isReady()) return candidates.firstOrNull()
        val prompt = buildString {
            append("User intent: \"$intent\"\n")
            append("Candidate skills (index: name — intent patterns):\n")
            candidates.forEachIndexed { i, s ->
                append("$i: ${s.name} — ${s.intentPatternsJson.take(80)}\n")
            }
            append("\nWhich index best matches? Reply with ONLY the number.")
        }
        val raw = GemmaInferenceEngine.generate(
            system = "You are a skill router. Respond with ONLY a single digit index.",
            user = prompt,
            maxTokens = 5
        ).trim()
        val idx = raw.filter { it.isDigit() }.firstOrNull()?.digitToIntOrNull() ?: return null
        return candidates.getOrNull(idx)
    }

    /** Cosine similarity between two float vectors. */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
        return if (denom < 1e-8) 0f else (dot / denom).toFloat()
    }
}
