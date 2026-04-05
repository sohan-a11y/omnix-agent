package com.omnix.agent.skills

import android.content.Context
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.database.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Skill Marketplace Foundation (Task 37)
 * Allows sharing skills between OMNIX instances.
 * Skills can be exported, imported, and rated.
 */
class SkillMarketplace(private val context: Context) {

    private val db = OmnixDatabase.getInstance(context)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Export a skill as a shareable JSON package.
     */
    suspend fun exportSkill(skillId: String): MarketplaceSkillPackage? =
        withContext(Dispatchers.IO) {
            val skill = db.skillDao().getById(skillId) ?: return@withContext null
            MarketplaceSkillPackage(
                id = skill.id,
                name = skill.name,
                appId = skill.appId,
                category = skill.category,
                version = skill.version,
                intentPatternsJson = skill.intentPatternsJson,
                stepsJson = skill.stepsJson,
                successRate = if (skill.successCount + skill.failureCount > 0)
                    skill.successCount.toFloat() / (skill.successCount + skill.failureCount)
                else 0f,
                avgExecMs = skill.avgExecMs,
                author = "OMNIX/${android.os.Build.MODEL}"
            )
        }

    /**
     * Import a skill from marketplace JSON.
     */
    suspend fun importSkill(pkg: MarketplaceSkillPackage): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val skill = SkillEntity(
                    id = "imported_${pkg.id}",
                    appId = pkg.appId,
                    name = pkg.name,
                    type = "ui_automation",
                    category = pkg.category,
                    version = pkg.version,
                    intentPatternsJson = pkg.intentPatternsJson,
                    parametersJson = "{}",
                    stepsJson = pkg.stepsJson,
                    confirmationRequired = true, // Always confirm imported skills
                    embedding = ByteArray(0),
                    intentHash = "",
                    status = "active"
                )
                db.skillDao().upsert(skill)
                true
            } catch (e: Exception) {
                false
            }
        }
}

@Serializable
data class MarketplaceSkillPackage(
    val id: String,
    val name: String,
    val appId: String,
    val category: String,
    val version: String,
    val intentPatternsJson: String,
    val stepsJson: String,
    val successRate: Float,
    val avgExecMs: Long,
    val author: String
)
