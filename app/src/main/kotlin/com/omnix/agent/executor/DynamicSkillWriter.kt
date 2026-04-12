package com.omnix.agent.executor

import android.content.Context
import android.util.Log
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.database.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * DynamicSkillWriter — L3 Self-Modification Layer.
 *
 * The AI can:
 *   1. Write shell/python scripts to solve a task
 *   2. Save successful scripts as reusable "skills"
 *   3. Modify existing skills based on feedback
 *   4. Generate automation scripts on-the-fly
 *
 * This makes OMNIX self-improving: every solved task becomes a
 * cached skill that runs instantly next time.
 *
 * Safety: all generated code passes through SafeGuard before execution.
 * Skills are versioned — old versions are kept so rollback is possible.
 */
object DynamicSkillWriter {

    private const val TAG = "DynSkillWriter"
    private const val SKILLS_DIR = "dynamic_skills"
    private const val MAX_SKILL_VERSIONS = 5

    data class DynamicSkill(
        val id: String,
        val name: String,
        val description: String,
        val triggerPattern: String,  // regex that matches user queries
        val scriptType: String,     // "bash", "python", "node", "accessibility"
        val scriptContent: String,
        val version: Int,
        val successCount: Int = 0,
        val failureCount: Int = 0
    )

    // In-memory cache of dynamic skills
    @Volatile
    private var skills = mutableListOf<DynamicSkill>()

    // ── Initialization ──────────────────────────────────────────────────────

    fun initialize(context: Context) {
        loadSkillsFromDisk(context)
        Log.i(TAG, "Loaded ${skills.size} dynamic skills")
    }

    // ── Skill Creation ──────────────────────────────────────────────────────

    /**
     * Ask Gemma to write a script that solves the given task.
     * Returns the generated script content.
     */
    suspend fun generateScript(
        task: String,
        scriptType: String = "bash",
        context: Context
    ): String? {
        if (!GemmaInferenceEngine.isReady()) return null

        val prompt = buildString {
            appendLine("Write a $scriptType script that accomplishes this task on Android:")
            appendLine("TASK: $task")
            appendLine()
            appendLine("Requirements:")
            appendLine("- Output ONLY the script code, no explanation")
            appendLine("- The script runs in Termux on Android")
            appendLine("- Use 'am start' to launch apps, 'input tap' for UI interaction")
            appendLine("- Print results to stdout")
            appendLine("- Handle errors gracefully")
            if (scriptType == "python") {
                appendLine("- Use only standard library or commonly available packages")
            }
        }

        val system = "You are a code generator. Output ONLY executable $scriptType code. No markdown, no explanation, no comments beyond necessary ones."

        val code = GemmaInferenceEngine.generate(system, prompt)

        // Clean up any markdown wrapping
        return cleanGeneratedCode(code, scriptType)
    }

    /**
     * Save a successfully executed script as a reusable dynamic skill.
     */
    suspend fun saveAsSkill(
        name: String,
        description: String,
        triggerPattern: String,
        scriptType: String,
        scriptContent: String,
        context: Context
    ): DynamicSkill {
        val skill = DynamicSkill(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            triggerPattern = triggerPattern,
            scriptType = scriptType,
            scriptContent = scriptContent,
            version = 1
        )

        skills.add(skill)
        saveSkillToDisk(skill, context)
        Log.i(TAG, "Saved dynamic skill: ${skill.name} (${skill.scriptType})")

        // Also save to Room database for persistence
        saveToDatabase(skill, context)

        return skill
    }

    /**
     * Update a skill's script content (self-improvement).
     */
    suspend fun updateSkill(
        skillId: String,
        newScript: String,
        context: Context
    ): DynamicSkill? {
        val index = skills.indexOfFirst { it.id == skillId }
        if (index < 0) return null

        val old = skills[index]
        val updated = old.copy(
            scriptContent = newScript,
            version = old.version + 1
        )

        // Keep old version as backup
        saveSkillVersion(old, context)

        skills[index] = updated
        saveSkillToDisk(updated, context)
        Log.i(TAG, "Updated skill: ${updated.name} v${updated.version}")

        return updated
    }

    /**
     * Record success/failure of a skill execution for learning.
     */
    fun recordExecution(skillId: String, success: Boolean) {
        val index = skills.indexOfFirst { it.id == skillId }
        if (index < 0) return

        val skill = skills[index]
        skills[index] = if (success) {
            skill.copy(successCount = skill.successCount + 1)
        } else {
            skill.copy(failureCount = skill.failureCount + 1)
        }
    }

    // ── Skill Matching ──────────────────────────────────────────────────────

    /**
     * Find a dynamic skill that matches the user's query.
     * Returns the best matching skill, or null if none match.
     */
    fun findMatchingSkill(query: String): DynamicSkill? {
        val lower = query.lowercase()

        return skills
            .filter { skill ->
                try {
                    Regex(skill.triggerPattern, RegexOption.IGNORE_CASE).containsMatchIn(lower)
                } catch (e: Exception) {
                    lower.contains(skill.triggerPattern.lowercase())
                }
            }
            .maxByOrNull { it.successCount - it.failureCount }
    }

    /**
     * Execute a dynamic skill.
     */
    suspend fun executeSkill(
        skill: DynamicSkill,
        context: Context,
        extraArgs: Map<String, String> = emptyMap()
    ): TermuxBridge.ExecResult {
        Log.i(TAG, "Executing dynamic skill: ${skill.name} (${skill.scriptType})")

        var script = skill.scriptContent

        // Substitute variables
        extraArgs.forEach { (key, value) ->
            script = script.replace("\${$key}", value)
            script = script.replace("{{$key}}", value)
        }

        val result = when (skill.scriptType) {
            "python" -> TermuxBridge.executePython(script, context)
            "node" -> TermuxBridge.executeNode(script, context)
            else -> TermuxBridge.execute(script, context)
        }

        recordExecution(skill.id, result.success)
        return result
    }

    /**
     * Ask Gemma to create a skill name and trigger pattern from a task description.
     */
    suspend fun askAIForSkillMeta(task: String): Triple<String, String, String>? {
        if (!GemmaInferenceEngine.isReady()) return null

        val raw = GemmaInferenceEngine.generate(
            "You create skill metadata. Output ONLY JSON: {\"name\":\"...\",\"trigger\":\"...\",\"description\":\"...\"}",
            "Create a reusable skill name, regex trigger pattern, and description for: $task"
        )

        return try {
            val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: "custom_skill"
            val trigger = Regex("\"trigger\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: task.take(30)
            val desc = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: task
            Triple(name, trigger, desc)
        } catch (e: Exception) {
            Triple("custom_skill_${System.currentTimeMillis()}", task.take(30), task)
        }
    }

    // ── Skill listing (for system prompt injection) ─────────────────────────

    /**
     * Get a compact summary of available dynamic skills for the AI context.
     */
    fun getSkillSummary(): String {
        if (skills.isEmpty()) return ""
        return buildString {
            appendLine("DYNAMIC SKILLS (learned from past tasks):")
            skills.sortedByDescending { it.successCount }.take(10).forEach { s ->
                appendLine("  • ${s.name} [${s.scriptType}] trigger=/${s.triggerPattern}/ success=${s.successCount}")
            }
        }
    }

    fun getAllSkills(): List<DynamicSkill> = skills.toList()

    // ── Disk persistence ────────────────────────────────────────────────────

    private fun getSkillsDir(context: Context): File {
        val dir = File(context.filesDir, SKILLS_DIR)
        dir.mkdirs()
        return dir
    }

    private fun loadSkillsFromDisk(context: Context) {
        val dir = getSkillsDir(context)
        val loaded = mutableListOf<DynamicSkill>()

        dir.listFiles()?.filter { it.extension == "skill" }?.forEach { file ->
            try {
                val lines = file.readLines()
                if (lines.size >= 6) {
                    loaded.add(DynamicSkill(
                        id = lines[0],
                        name = lines[1],
                        description = lines[2],
                        triggerPattern = lines[3],
                        scriptType = lines[4],
                        scriptContent = lines.drop(6).joinToString("\n"),
                        version = lines.getOrNull(5)?.toIntOrNull() ?: 1
                    ))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load skill: ${file.name}: ${e.message}")
            }
        }

        skills = loaded
    }

    private fun saveSkillToDisk(skill: DynamicSkill, context: Context) {
        val file = File(getSkillsDir(context), "${skill.id}.skill")
        file.writeText(buildString {
            appendLine(skill.id)
            appendLine(skill.name)
            appendLine(skill.description)
            appendLine(skill.triggerPattern)
            appendLine(skill.scriptType)
            appendLine(skill.version)
            append(skill.scriptContent)
        })
    }

    private fun saveSkillVersion(skill: DynamicSkill, context: Context) {
        val versionsDir = File(getSkillsDir(context), "versions")
        versionsDir.mkdirs()
        val file = File(versionsDir, "${skill.id}_v${skill.version}.skill")
        file.writeText(skill.scriptContent)

        // Prune old versions
        val versions = versionsDir.listFiles()
            ?.filter { it.name.startsWith(skill.id) }
            ?.sortedByDescending { it.lastModified() }
        versions?.drop(MAX_SKILL_VERSIONS)?.forEach { it.delete() }
    }

    private suspend fun saveToDatabase(skill: DynamicSkill, context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val db = OmnixDatabase.getInstance(context)
                db.skillDao().upsert(SkillEntity(
                    id = "dynamic_${skill.id}",
                    appId = "com.omnix.agent",
                    name = skill.name,
                    type = "dynamic",
                    category = "dynamic",
                    version = skill.version.toString(),
                    intentPatternsJson = """["${skill.triggerPattern}"]""",
                    parametersJson = "{}",
                    stepsJson = skill.scriptContent,
                    confirmationRequired = false,
                    embedding = ByteArray(0),
                    intentHash = skill.id.hashCode().toString()
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save skill to DB: ${e.message}")
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun cleanGeneratedCode(raw: String, type: String): String {
        var code = raw.trim()

        // Remove markdown code fences
        val langPatterns = listOf("```$type", "```bash", "```python", "```javascript", "```sh", "```")
        for (pattern in langPatterns) {
            if (code.startsWith(pattern)) {
                code = code.removePrefix(pattern).trim()
            }
        }
        if (code.endsWith("```")) {
            code = code.removeSuffix("```").trim()
        }

        return code
    }
}
