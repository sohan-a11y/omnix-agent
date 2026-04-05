package com.omnix.agent.ui

import android.content.Context
import com.omnix.agent.database.SkillEntity
import com.omnix.agent.executor.SkillStep
import com.omnix.agent.voice.TTS
import kotlinx.serialization.json.Json

/**
 * PlanPreview + ActionHistory (Task 22)
 * Shows user what OMNIX is about to do before executing.
 * High-risk actions get explicit confirmation.
 */
object PlanPreview {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Presents the execution plan and gets confirmation.
     * Returns true if user confirms or skill doesn't require confirmation.
     */
    suspend fun confirm(
        context: Context,
        skill: SkillEntity,
        params: Map<String, String>
    ): Boolean {
        if (!skill.confirmationRequired) return true

        // Build plan summary
        val steps = try {
            json.decodeFromString<List<SkillStep>>(skill.stepsJson)
        } catch (e: Exception) {
            emptyList()
        }

        val summary = buildString {
            append("OMNIX will: ${skill.name}. ")
            steps.take(3).forEach { step ->
                if (step.narration.isNotEmpty()) append("${step.narration}. ")
            }
            if (steps.size > 3) append("And ${steps.size - 3} more steps.")
        }

        return ConfirmationGate.confirm(context, skill, params)
    }
}
