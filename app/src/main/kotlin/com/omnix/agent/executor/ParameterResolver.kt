package com.omnix.agent.executor

import android.content.Context
import com.omnix.agent.ai.IntentResult
import com.omnix.agent.database.SkillEntity
import com.omnix.agent.skills.ContactsReader

/**
 * Resolves raw intent entities into the concrete parameter map expected by [SkillExecutor].
 *
 * Currently handles:
 *  - Contact name → phone number lookup (so skills get a diallable number).
 *
 * Additional resolutions (location, account selection, etc.) should be added here
 * rather than scattered through voice/chat handlers.
 */
class ParameterResolver(private val context: Context) {

    suspend fun resolve(skill: SkillEntity, intent: IntentResult): Map<String, String> {
        val params = intent.entities
            .filterValues { it != null }
            .mapValues { it.value!! }
            .toMutableMap()

        // Contact name → phone number
        val contactName = params["contact"]
        if (!contactName.isNullOrBlank() && !contactName.all { it.isDigit() }) {
            val contact = ContactsReader.resolve(context, contactName)
            contact?.phone?.let { params["phone"] = it }
        }

        return params
    }
}
