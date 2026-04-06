package com.omnix.agent.skills

import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract

/**
 * Reads device contacts and performs fuzzy Levenshtein matching.
 * Spec: distance ≤ 2 for name resolution, exact match for phone number.
 */
object ContactsReader {

    data class Contact(
        val name: String,
        val phone: String,
        val email: String = ""
    )

    /**
     * Resolve a spoken name to the best matching contact.
     * Returns null if no contact within Levenshtein distance 2.
     */
    fun resolve(context: Context, query: String): Contact? {
        val contacts = readAll(context)
        if (contacts.isEmpty()) return null

        val q = query.lowercase().trim()

        // Exact match first
        contacts.firstOrNull { it.name.lowercase() == q }?.let { return it }

        // Fuzzy match — distance ≤ 2
        return contacts
            .map { it to levenshtein(q, it.name.lowercase()) }
            .filter { (_, dist) -> dist <= 2 }
            .minByOrNull { (_, dist) -> dist }
            ?.first
    }

    /** Returns all contacts with at least one phone number. */
    fun readAll(context: Context): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val resolver: ContentResolver = context.contentResolver

        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        ) ?: return emptyList()

        cursor.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIdx)?.trim() ?: continue
                val phone = it.getString(phoneIdx)?.trim() ?: continue
                if (name.isNotBlank() && phone.isNotBlank()) {
                    contacts.add(Contact(name = name, phone = normalizePhone(phone)))
                }
            }
        }
        return contacts
    }

    /** Normalize phone number — strip spaces, dashes, brackets, leading country code. */
    fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == '+' }
        return when {
            digits.startsWith("+91") && digits.length == 13 -> digits.substring(3)
            digits.startsWith("91") && digits.length == 12 -> digits.substring(2)
            else -> digits.filter { it.isDigit() }
        }
    }

    /**
     * Standard Levenshtein edit distance between two strings.
     * O(m*n) time, O(n) space.
     */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev.indices.forEach { prev[it] = curr[it] }
        }
        return prev[b.length]
    }
}
