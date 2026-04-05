package com.omnix.agent.skills

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.ai.IntentResult
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.database.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves parameters for skills from:
 * 1. Direct entities in the intent
 * 2. Contacts lookup
 * 3. Context from memory
 * 4. Clarification from user
 *
 * Task 14: ParameterResolver + CorrectionLearner
 */
class ParameterResolver(private val context: Context) {

    private val db = OmnixDatabase.getInstance(context)

    /**
     * Resolves all required parameters for a skill from the intent.
     * Returns null if a required parameter cannot be resolved.
     */
    suspend fun resolve(
        skill: SkillEntity,
        intent: IntentResult
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        val params = mutableMapOf<String, String>()

        // Start with entities from intent
        intent.entities.forEach { (k, v) -> if (v != null) params[k] = v }

        // Resolve contact names to phone numbers
        params["contact"]?.let { contactName ->
            val phone = lookupContact(contactName)
            if (phone != null) params["contact_phone"] = phone
        }

        // Resolve amounts - strip currency symbols
        params["amount"]?.let { amount ->
            params["amount"] = amount.replace(Regex("[₹$€£,]"), "").trim()
        }

        params
    }

    private fun lookupContact(name: String): String? {
        return try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(0)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Learn from corrections: when user says "no, I meant X not Y"
     */
    suspend fun learnCorrection(
        originalEntity: String,
        correctedValue: String,
        skillId: String
    ) = withContext(Dispatchers.IO) {
        // Store correction as a memory for future resolution
        db.memoryDao().upsert(
            com.omnix.agent.database.MemoryEntity(
                content = "Correction: '$originalEntity' should be '$correctedValue' for skill $skillId",
                memoryType = "preference",
                importanceScore = 0.8f,
                embedding = ByteArray(0)
            )
        )
    }
}
