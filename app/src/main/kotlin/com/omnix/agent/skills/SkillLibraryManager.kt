package com.omnix.agent.skills

import android.content.Context
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.database.SkillEntity
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ai.IntentResult
import com.omnix.agent.ai.bytesToFloatArray
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.security.MessageDigest

object SkillLibraryManager {

    private lateinit var db: OmnixDatabase
    private val embeddingCache = mutableMapOf<String, FloatArray>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        db = OmnixDatabase.getInstance(context)
        initialized = true
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
    suspend fun findSkill(intent: IntentResult, rawQuery: String? = null): SkillEntity? {
        // Stage 1: Intent hash exact match (fastest)
        val hash = hashIntent(intent.intent, intent.entities)
        db.skillDao().findByIntentHash(hash)?.let { return it }

        val allSkills = db.skillDao().getAllActive().first()
        if (allSkills.isEmpty()) return null

        // Stage 2: Category- and app-filtered search
        val categories = inferCategories(intent, rawQuery)
        var candidates = allSkills.filter { it.category in categories }
        if (candidates.isEmpty()) candidates = allSkills

        val appHints = listOfNotNull(intent.entities["app"], intent.entities["app_name"])
            .map(::normalize)
            .filter { it.isNotBlank() }
        if (appHints.isNotEmpty()) {
            val appMatched = candidates.filter { skill ->
                appHints.any { hint ->
                    normalize(skill.appId).contains(hint) || normalize(skill.name).contains(hint)
                }
            }
            if (appMatched.isNotEmpty()) candidates = appMatched
        }

        if (candidates.isEmpty()) return null

        scorePatternMatches(candidates, rawQuery, intent)?.let { directMatch ->
            return directMatch
        }

        // Stage 3: Semantic similarity
        val queryEmbedding = GemmaInferenceEngine.generateEmbedding(buildEmbeddingQuery(intent, rawQuery))
        val ranked = candidates
            .filter { embeddingCache.containsKey(it.id) }
            .map { skill ->
                val emb = embeddingCache[skill.id] ?: return@map Pair(0f, skill)
                Pair(cosineSimilarity(queryEmbedding, emb), skill)
            }
            .filter { it.first > 0.55f }
            .sortedByDescending { it.first }
            .map { it.second }

        if (ranked.isEmpty()) return candidates.firstOrNull()

        // Stage 4: Gemma re-rank top 3 (only if Gemma is ready)
        if (ranked.size > 1 && GemmaInferenceEngine.isReady()) {
            return gemmaRerank(intent, ranked.take(3))
        }

        return ranked.first()
    }

    private suspend fun gemmaRerank(intent: IntentResult, candidates: List<SkillEntity>): SkillEntity? {
        val candidateList = candidates.mapIndexed { i, s -> "${i + 1}. ${s.name}: ${s.intentPatternsJson}" }
            .joinToString("\n")

        val result = GemmaInferenceEngine.generate(
            system = "Select the best skill for the user intent. Respond with ONLY the number (1, 2, or 3).",
            user = "Intent: ${intent.intent}\nEntities: ${intent.entities}\n\nCandidates:\n$candidateList"
        )

        // Robust parsing: find any digit 1-9 in the response, don't rely on firstOrNull()
        val idx = Regex("\\b([1-9])\\b").find(result)
            ?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
            ?.coerceIn(0, candidates.lastIndex)
            ?: 0
        return candidates.getOrNull(idx) ?: candidates.first()
    }

    /**
     * Notify the cache that a new skill has been registered.
     * Called by DynamicSkillWriter / DiscoveryEngine after persisting a skill.
     */
    suspend fun onSkillRegistered(skill: SkillEntity) {
        if (skill.embedding.isNotEmpty()) {
            embeddingCache[skill.id] = bytesToFloatArray(skill.embedding)
        } else {
            // Generate and cache embedding for the new skill on the spot
            val query = "${skill.name} ${skill.intentPatternsJson.take(100)}"
            val emb = GemmaInferenceEngine.generateEmbedding(query)
            if (emb.isNotEmpty()) embeddingCache[skill.id] = emb
        }
    }

    private fun inferCategories(intent: IntentResult, rawQuery: String?): Set<String> {
        val q = normalize(listOfNotNull(rawQuery, intent.intent).joinToString(" "))
        val categories = mutableSetOf<String>()
        fun hasAny(vararg tokens: String) = tokens.any { q.contains(it) || intent.intent.contains(it) }

        if (hasAny("bank", "balance", "transfer")) categories += "banking"
        if (hasAny("pay", "upi", "wallet")) categories += "payments"
        if (hasAny("message", "text", "whatsapp", "chat")) categories += "messaging"
        if (hasAny("call", "dial", "phone")) {
            categories += "communication"
            categories += "messaging"
        }
        if (hasAny("order", "food", "restaurant")) categories += "food"
        if (hasAny("navigate", "direction", "route", "map")) categories += "travel"
        if (hasAny("youtube", "play", "music", "video")) categories += "entertainment"
        if (hasAny("email", "mail", "compose")) categories += "productivity"
        if (categories.isEmpty()) categories += "other"
        return categories
    }

    private fun scorePatternMatches(
        candidates: List<SkillEntity>,
        rawQuery: String?,
        intent: IntentResult
    ): SkillEntity? {
        if (rawQuery.isNullOrBlank()) return null
        val query = normalize(rawQuery)
        var best: Pair<SkillEntity, Int>? = null

        candidates.forEach { skill ->
            var score = 0
            runCatching {
                json.decodeFromString<List<String?>>(skill.intentPatternsJson)
            }.getOrDefault(emptyList()).forEach { p ->
                val pattern = p ?: return@forEach
                val normalizedPattern = normalize(pattern.replace(Regex("""\{[^}]+\}"""), " "))
                if (normalizedPattern.isBlank()) return@forEach
                val patternTokens = normalizedPattern.split(' ').filter { it.isNotBlank() }
                val queryTokens = query.split(' ').filter { it.isNotBlank() }.toSet()
                val overlap = patternTokens.count { it in queryTokens }

                if (query == normalizedPattern) score = maxOf(score, 260)
                if (query.contains(normalizedPattern)) score = maxOf(score, 230)
                if (overlap == patternTokens.size && patternTokens.isNotEmpty()) score = maxOf(score, 220)
                if (overlap > 0) score = maxOf(score, overlap * 45)
            }

            if (intent.intent.contains("call") && normalize(skill.name).contains("call")) score += 40
            if (intent.intent.contains("message") && normalize(skill.name).contains("message")) score += 40
            if (intent.intent.contains("transfer") && normalize(skill.name).contains("transfer")) score += 40

            intent.entities["app"]?.let { appPkg ->
                if (skill.appId.equals(appPkg, ignoreCase = true)) score += 140
            }
            intent.entities["app_name"]?.let { appName ->
                if (normalize(skill.name).contains(normalize(appName)) || normalize(skill.appId).contains(normalize(appName))) {
                    score += 100
                }
            }

            if (best == null || score > best!!.second) best = skill to score
        }

        return best?.takeIf { it.second >= 180 }?.first
    }

    private fun buildEmbeddingQuery(intent: IntentResult, rawQuery: String?): String {
        val entityTerms = intent.entities.values.filterNotNull().joinToString(" ")
        return listOfNotNull(rawQuery, intent.intent, entityTerms)
            .joinToString(" ")
            .trim()
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
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
