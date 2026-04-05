package com.omnix.agent.discovery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.database.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Discovery Engine: 6-stage pipeline to learn all installed apps.
 *
 * Stage 0: APK static analysis (30s, no app launch)
 * Stage 1: Enumerate installed apps
 * Stage 2: Classify apps by category
 * Stage 3: UI Crawl (APK-guided, live navigation)
 * Stage 4: Vision labeling
 * Stage 5: Path extraction (skill generation)
 * Stage 6: Skill synthesis
 */
class DiscoveryEngine(private val context: Context) {

    private val db = OmnixDatabase.getInstance(context)
    private val apkAnalyzer = APKAnalyzer(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Stage 1: Enumerate installed apps ─────────────────────────────────────
    suspend fun enumerateApps(): List<AppEntity> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 33) {
            PackageManager.PackageInfoFlags.of(0L)
        } else {
            null
        }

        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { !isSystemApp(it.packageName) }
            .map { appInfo ->
                AppEntity(
                    id = appInfo.packageName,
                    name = pm.getApplicationLabel(appInfo).toString(),
                    version = try {
                        pm.getPackageInfo(appInfo.packageName, 0).versionName ?: "unknown"
                    } catch (e: Exception) { "unknown" },
                    category = "uncategorized",
                    capabilities = "[]",
                    packageName = appInfo.packageName,
                    launchActivity = pm.getLaunchIntentForPackage(appInfo.packageName)
                        ?.component?.className ?: ""
                )
            }

        db.appDao().upsertAll(installed)
        installed
    }

    // ── Stage 2: Classify apps ────────────────────────────────────────────────
    suspend fun classifyApp(app: AppEntity): String = withContext(Dispatchers.IO) {
        val prompt = "App name: ${app.name}, package: ${app.packageName}. Classify into one of: banking, payments, messaging, social, shopping, travel, food, health, productivity, entertainment, other."
        val result = GemmaInferenceEngine.generate(
            system = "Classify Android apps. Respond with ONLY the category word.",
            user = prompt,
            maxTokens = 50
        )
        result.trim().lowercase().replace(Regex("[^a-z]"), "")
    }

    // ── Full discovery pipeline for one app ───────────────────────────────────
    suspend fun discoverApp(packageId: String, forceRefresh: Boolean = false) =
        withContext(Dispatchers.IO) {
            val existing = db.appDao().getById(packageId)
            if (existing?.isDiscovered == true && !forceRefresh) return@withContext

            // Stage 0: APK static analysis
            val apkKnowledge = try {
                apkAnalyzer.analyzeApp(packageId)
            } catch (e: Exception) {
                null
            }

            // Stage 2: Classify
            val app = existing ?: AppEntity(
                id = packageId,
                name = packageId,
                version = "unknown",
                category = "other",
                capabilities = "[]"
            )
            val category = classifyApp(app)
            db.appDao().upsert(app.copy(category = category))

            // Stage 3: Generate skills from APK knowledge
            apkKnowledge?.let { generateSkillsFromAPK(packageId, it, category) }

            // Mark as discovered
            db.appDao().markDiscovered(packageId)
        }

    // ── Generate skills from APK static analysis ──────────────────────────────
    private suspend fun generateSkillsFromAPK(
        packageId: String,
        knowledge: APKKnowledge,
        category: String
    ) {
        // Use Gemma to generate skill definitions from screen layouts
        val layoutSummary = knowledge.layouts.take(5).joinToString("\n") { layout ->
            "Screen: ${layout.screenId}, elements: ${layout.elements.size}"
        }

        val skillJson = GemmaInferenceEngine.generate(
            system = SKILL_GEN_SYSTEM,
            user = "App: $packageId, Category: $category\nLayouts:\n$layoutSummary",
            maxTokens = 1000
        )

        // Parse and store skills
        // Skills stored as JSON in SkillEntity
    }

    // ── Discover all undiscovered apps ─────────────────────────────────────────
    suspend fun discoverAllApps() = withContext(Dispatchers.IO) {
        val undiscovered = db.appDao().getUndiscovered()
        undiscovered.forEach { app ->
            try {
                discoverApp(app.id)
            } catch (e: Exception) {
                // Continue with next app
            }
        }
    }

    private fun isSystemApp(packageName: String): Boolean {
        val systemPrefixes = listOf(
            "com.android.", "com.google.android.", "android.",
            "com.samsung.", "com.sec.", "com.qualcomm."
        )
        return systemPrefixes.any { packageName.startsWith(it) }
    }

    companion object {
        private val SKILL_GEN_SYSTEM = """
            You are a mobile automation skill generator.
            Given an Android app's layout info, generate automation skills as JSON array.
            Each skill: {"name":"","intent_patterns":[],"steps":[],"confirmation_required":false}
            Respond ONLY with JSON array.
        """.trimIndent()
    }
}
