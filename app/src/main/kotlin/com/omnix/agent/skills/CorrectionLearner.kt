package com.omnix.agent.skills

import android.content.Context
import android.content.SharedPreferences
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ai.IntentResult
import com.omnix.agent.ai.floatArrayToBytes
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.database.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Correction Learner — Task 14.
 * When a user corrects OMNIX ("no, I meant X"), this persists the mapping
 * and applies overrides before future skill lookups.
 *
 * Overrides stored in SharedPreferences as JSON: Map<utterance, skillId>
 */
object CorrectionLearner {

    private const val PREFS_NAME = "omnix_corrections"
    private const val KEY_OVERRIDES = "overrides"
    private val json = Json { ignoreUnknownKeys = true }

    private var prefs: SharedPreferences? = null
    private val overrides = mutableMapOf<String, String>() // utterance → skillId

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadOverrides()
    }

    /**
     * Apply overrides: if the user's utterance has been corrected before,
     * return the override skill instead.
     */
    fun applyOverrides(intentResult: IntentResult): String? {
        val key = intentResult.intent.lowercase().trim()
        return overrides[key]
    }

    /**
     * Record a correction: "I said X but OMNIX executed Y; the correct skill was Z."
     * Persists the mapping and updates the skill's intent patterns in DB.
     */
    suspend fun learnCorrection(
        context: Context,
        utterance: String,
        wrongSkillId: String,
        correctSkillId: String,
        db: OmnixDatabase
    ) = withContext(Dispatchers.IO) {
        val key = utterance.lowercase().trim()
        overrides[key] = correctSkillId
        persistOverrides()

        // Also add the utterance as a new intent pattern on the correct skill
        val skill = db.skillDao().getById(correctSkillId) ?: return@withContext
        val patterns = try {
            json.decodeFromString<List<String>>(skill.intentPatternsJson).toMutableList()
        } catch (_: Exception) { mutableListOf() }

        if (!patterns.contains(utterance)) {
            patterns.add(utterance)
            val newEmb = GemmaInferenceEngine.generateEmbedding(patterns.joinToString(" "))
            db.skillDao().upsert(
                skill.copy(
                    intentPatternsJson = json.encodeToString(patterns),
                    embedding = floatArrayToBytes(newEmb),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** Forget all corrections for a skill (e.g., skill was deleted). */
    fun forgetSkill(skillId: String) {
        overrides.entries.removeIf { it.value == skillId }
        persistOverrides()
    }

    private fun loadOverrides() {
        val raw = prefs?.getString(KEY_OVERRIDES, null) ?: return
        try {
            val map = json.decodeFromString<Map<String, String>>(raw)
            overrides.clear()
            overrides.putAll(map)
        } catch (_: Exception) {}
    }

    private fun persistOverrides() {
        prefs?.edit()?.putString(KEY_OVERRIDES, json.encodeToString(overrides.toMap()))?.apply()
    }
}
