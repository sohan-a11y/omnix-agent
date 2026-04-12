package com.omnix.agent.ai

import android.content.Context
import com.omnix.agent.database.AppEntity
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.skills.ContactsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Compact app-knowledge index used for prompt building and deterministic app resolution.
 *
 * Keeps the existing discovery architecture intact while avoiding huge prompts and
 * giving OMNIX a reliable fallback when Gemma misses an app package name.
 */
object AppKnowledgeEngine {

    data class LearnedApp(
        val name: String,
        val packageName: String,
        val category: String,
        val launchActivity: String,
        val capabilityTags: List<String>
    ) {
        val normalizedName: String = normalizeForMatch(name)
        val compactName: String = normalizedName.replace(" ", "")
        val normalizedPackage: String = normalizeForMatch(packageName)
        val compactPackage: String = normalizedPackage.replace(" ", "")
        val packageTail: String = normalizeForMatch(packageName.substringAfterLast('.'))
    }

    @Volatile
    private var learnedApps: List<LearnedApp> = emptyList()

    suspend fun refresh(context: Context): Int = withContext(Dispatchers.IO) {
        val dao = OmnixDatabase.getInstance(context).appDao()
        val discovered = dao.getDiscovered()
        val source = if (discovered.isNotEmpty()) {
            discovered
        } else {
            // firstOrNull with timeout prevents indefinite blocking on empty DB
            withTimeoutOrNull(5_000L) { dao.getAll().firstOrNull() }
                ?.filter { it.launchActivity.isNotBlank() }
                ?: emptyList()
        }
        val apps = source
            .map { it.toLearnedApp() }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        learnedApps = apps
        apps.size
    }

    fun totalApps(): Int = learnedApps.size

    fun resolveLaunchableApp(
        query: String,
        packageHint: String? = null,
        appHint: String? = null
    ): LearnedApp? = resolveApp(learnedApps, query, packageHint, appHint)
        ?.takeIf { it.launchActivity.isNotBlank() }

    fun buildIntentContext(query: String, maxApps: Int = 14): String =
        buildPromptSlice(learnedApps, query, maxApps)

    fun buildConversationSummary(maxCategories: Int = 6, maxExamples: Int = 8): String {
        if (learnedApps.isEmpty()) return ""

        val categoryCounts = learnedApps
            .groupingBy { it.category }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(maxCategories)
            .joinToString(", ") { "${it.key}:${it.value}" }

        val examples = learnedApps
            .filter { it.category in setOf("messaging", "banking", "payments", "travel", "entertainment") }
            .take(maxExamples)
            .joinToString(", ") { "${it.name}=${it.packageName}" }

        return buildString {
            appendLine("DEVICE APP OVERVIEW:")
            appendLine("- discovered_apps=${learnedApps.size}")
            appendLine("- categories=$categoryCounts")
            if (examples.isNotBlank()) appendLine("- examples=$examples")
        }.trim()
    }

    internal fun resolveApp(
        apps: List<LearnedApp>,
        query: String,
        packageHint: String? = null,
        appHint: String? = null
    ): LearnedApp? {
        if (apps.isEmpty()) return null

        packageHint
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { pkg ->
                apps.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }?.let { return it }
            }

        val hints = linkedSetOf<String>()
        packageHint?.takeIf { it.isNotBlank() }?.let { hints += it }
        appHint?.takeIf { it.isNotBlank() }?.let { hints += it }
        extractLaunchTarget(query)?.let { hints += it }
        if (hints.isEmpty()) return null

        val scored = apps.map { app ->
            app to scoreAppMatch(app, hints, packageHint)
        }.filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<LearnedApp, Int>> { it.second }
                    .thenBy { it.first.name.lowercase(Locale.ROOT) }
            )

        val top = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)
        if (top.second < 170) return null
        if (second != null && top.second - second.second < 15 && second.first.packageName != top.first.packageName) {
            return null
        }
        return top.first
    }

    internal fun buildPromptSlice(
        apps: List<LearnedApp>,
        query: String,
        maxApps: Int = 14
    ): String {
        if (apps.isEmpty()) return ""

        val relevant = rankRelevantApps(apps, query)
            .take(maxApps)
            .toList()
        if (relevant.isEmpty()) return "DEVICE APP INDEX: ${apps.size} discovered apps are available locally."

        return buildString {
            appendLine("DEVICE APP INDEX: ${apps.size} discovered apps are available locally.")
            appendLine("MOST RELEVANT APPS FOR THIS REQUEST:")
            relevant.forEach { app ->
                val caps = app.capabilityTags.take(4)
                append("• ${app.name} pkg=${app.packageName} category=${app.category}")
                if (caps.isNotEmpty()) append(" caps=[${caps.joinToString(", ")}]")
                appendLine()
            }
        }.trim()
    }

    internal fun normalizeForMatch(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    internal fun extractLaunchTarget(query: String): String? {
        val normalized = normalizeForMatch(query)
        if (normalized.isBlank()) return null

        val stripped = sequenceOf(
            "please ",
            "can you ",
            "could you ",
            "would you ",
            "will you ",
            "help me ",
            "i want you to "
        ).fold(normalized) { acc, prefix ->
            if (acc.startsWith(prefix)) acc.removePrefix(prefix) else acc
        }

        val actionPrefix = sequenceOf(
            "open up ",
            "open ",
            "launch ",
            "start ",
            "go to ",
            "take me to "
        ).firstOrNull { stripped.startsWith(it) } ?: return null

        return stripped
            .removePrefix(actionPrefix)
            .removePrefix("the ")
            .removeSuffix(" app")
            .removeSuffix(" for me")
            .trim()
            .ifBlank { null }
    }

    private fun AppEntity.toLearnedApp(): LearnedApp = LearnedApp(
        name = name,
        packageName = packageName.ifBlank { id },
        category = category.ifBlank { "other" },
        launchActivity = launchActivity,
        capabilityTags = extractCapabilityTags(capabilities)
    )

    private fun extractCapabilityTags(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val match = Regex(""""capabilities"\s*:\s*\[([^\]]*)]""").find(raw) ?: return emptyList()
        return match.groupValues[1]
            .split(',')
            .map { it.replace("\"", "").trim() }
            .filter { it.isNotBlank() }
    }

    private fun hintedCategoriesForQuery(query: String): Set<String> {
        val q = normalizeForMatch(query)
        val categories = mutableSetOf<String>()
        fun hasAny(vararg terms: String) = terms.any { q.contains(it) }

        if (hasAny("bank", "balance", "upi", "pay", "payment", "money", "transfer", "account")) {
            categories += "banking"
            categories += "payments"
        }
        if (hasAny("message", "whatsapp", "text", "sms", "chat", "call", "phone")) {
            categories += "messaging"
            categories += "communication"
        }
        if (hasAny("map", "navigate", "route", "direction", "travel", "cab")) categories += "travel"
        if (hasAny("youtube", "music", "movie", "watch", "play")) categories += "entertainment"
        if (hasAny("food", "swiggy", "zomato", "order")) categories += "food"
        if (hasAny("mail", "email", "gmail")) categories += "productivity"
        return categories
    }

    private fun rankRelevantApps(apps: List<LearnedApp>, query: String): Sequence<LearnedApp> {
        val normalizedQuery = normalizeForMatch(query)
        val queryTokens = normalizedQuery.split(' ').filter { it.isNotBlank() }.toSet()
        val categories = hintedCategoriesForQuery(query)
        val exactApp = resolveApp(apps, query)

        return apps.asSequence()
            .map { app ->
                var score = 0
                if (exactApp?.packageName == app.packageName) score += 500
                if (app.category in categories) score += 120
                score += queryTokens.count { token ->
                    token.length >= 3 && (
                        app.normalizedName.contains(token) ||
                            app.packageTail.contains(token) ||
                            app.capabilityTags.any { normalizeForMatch(it).contains(token) }
                        )
                } * 35
                if (queryTokens.any { token -> token in setOf("open", "launch", "start") }) score += 10
                app to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun scoreAppMatch(
        app: LearnedApp,
        rawHints: Set<String>,
        packageHint: String?
    ): Int {
        val exactPackageHint = packageHint?.trim()?.lowercase(Locale.ROOT)
        if (!exactPackageHint.isNullOrBlank() && app.packageName.equals(exactPackageHint, ignoreCase = true)) {
            return 1000
        }

        var best = 0
        rawHints.forEach { rawHint ->
            val hint = normalizeForMatch(rawHint)
            val compactHint = hint.replace(" ", "")
            if (hint.isBlank()) return@forEach

            var score = 0
            val hintTokens = hint.split(' ').filter { it.isNotBlank() }
            val appTokens = app.normalizedName.split(' ').filter { it.isNotBlank() }.toSet()

            when {
                compactHint == app.compactName -> score += 450
                compactHint == app.compactPackage -> score += 430
                hint == app.packageTail -> score += 410
                app.compactName.contains(compactHint) -> score += 310
                app.compactPackage.contains(compactHint) -> score += 280
                hintTokens.isNotEmpty() && hintTokens.all { it in appTokens } -> score += 240
            }

            val fuzzyDistance = when {
                compactHint.length >= 5 -> ContactsReader.levenshtein(compactHint, app.compactName)
                else -> Int.MAX_VALUE
            }
            if (fuzzyDistance <= 2) score += 210 - (fuzzyDistance * 40)

            score += hintTokens.count { token ->
                token.length >= 3 && (
                    token in appTokens ||
                        app.packageTail.contains(token) ||
                        app.compactPackage.contains(token)
                    )
            } * 35

            best = maxOf(best, score)
        }
        return best
    }
}
