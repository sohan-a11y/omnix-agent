package com.omnix.agent.skills

import android.content.Context
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ai.floatArrayToBytes
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.database.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Skill Registry — Task 37.
 * HTTP skill search and import from the OMNIX skill registry.
 * Also supports importing skills from a local JSON file or URL.
 */
object SkillRegistry {

    private val json = Json { ignoreUnknownKeys = true }

    // Registry endpoint — can be overridden for self-hosted registry
    private var registryUrl = "https://registry.omnix.dev/skills"

    @Serializable
    data class RegistrySkill(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val appId: String,
        val author: String = "community",
        val downloads: Int = 0,
        val version: String = "1.0"
    )

    /**
     * Search the skill registry by keyword.
     * Falls back to local DB search if registry is unreachable.
     */
    suspend fun search(
        context: Context,
        query: String,
        db: OmnixDatabase
    ): List<RegistrySkill> = withContext(Dispatchers.IO) {
        try {
            searchRemote(query)
        } catch (_: Exception) {
            // Offline fallback: search local DB
            searchLocal(query, db)
        }
    }

    private suspend fun searchRemote(query: String): List<RegistrySkill> =
        withContext(Dispatchers.IO) {
            val url = "$registryUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=20"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            if (conn.responseCode != 200) return@withContext emptyList()
            val raw = conn.inputStream.bufferedReader().readText()
            json.decodeFromString<List<RegistrySkill>>(raw)
        }

    private suspend fun searchLocal(query: String, db: OmnixDatabase): List<RegistrySkill> {
        val q = query.lowercase()
        return db.skillDao().getByCategory("").filter {
            it.name.lowercase().contains(q) || it.category.lowercase().contains(q)
        }.map { skill ->
            RegistrySkill(
                id = skill.id,
                name = skill.name,
                description = skill.intentPatternsJson,
                category = skill.category,
                appId = skill.appId
            )
        }
    }

    /**
     * Import a skill from a JSON URL or inline JSON string into the DB.
     */
    suspend fun importSkill(context: Context, jsonOrUrl: String, db: OmnixDatabase): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val rawJson = if (jsonOrUrl.startsWith("http")) {
                    fetchFromUrl(jsonOrUrl)
                } else {
                    jsonOrUrl
                }

                val obj = json.parseToJsonElement(rawJson.trim()) as? JsonObject
                    ?: return@withContext Result.failure(Exception("Invalid JSON"))

                val id = obj["id"]?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(Exception("Missing id"))
                val name = obj["name"]?.jsonPrimitive?.content ?: id
                val emb = GemmaInferenceEngine.generateEmbedding(name)

                val entity = SkillEntity(
                    id = id,
                    appId = obj["app_id"]?.jsonPrimitive?.content ?: "",
                    name = name,
                    type = obj["type"]?.jsonPrimitive?.content ?: "ui_automation",
                    category = obj["category"]?.jsonPrimitive?.content ?: "other",
                    version = obj["version"]?.jsonPrimitive?.content ?: "1.0",
                    intentPatternsJson = obj["intent_patterns"]?.toString() ?: "[]",
                    parametersJson = obj["parameters"]?.toString() ?: "{}",
                    stepsJson = obj["steps"]?.toString() ?: "[]",
                    confirmationRequired = obj["confirmation_required"]?.jsonPrimitive?.content == "true",
                    embedding = floatArrayToBytes(emb),
                    intentHash = sha256(id).take(16),
                    status = "active"
                )

                db.skillDao().upsert(entity)
                Result.success(id)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun fetchFromUrl(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
        }
        if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().readText()
    }

    private fun sha256(input: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        return d.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun setRegistryUrl(url: String) { registryUrl = url }
}
