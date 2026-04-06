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

    /**
     * Crawls an app by launching it and recording each screen's elements.
     * Uses APKKnowledge as a guide for what screens to expect.
     */
    suspend fun crawlAppWithAPKGuide(
        packageId: String,
        a11y: com.omnix.agent.core.OmnixAccessibilityService,
        maxScreens: Int = 20
    ): List<ScreenCrawlEntity> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScreenCrawlEntity>()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageId)
            ?: return@withContext emptyList()
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        delay(2000)

        val visited = mutableSetOf<String>()
        var count = 0
        while (count < maxScreens) {
            val elements = a11y.getAllText()
            val screenKey = "$packageId:${elements.take(3).joinToString("|") { it.second }}"
            if (screenKey in visited) break
            visited.add(screenKey)

            val screenName = "screen_$count"
            val crawlId = sha256("$packageId:$screenKey:${System.currentTimeMillis()}")
            val entity = ScreenCrawlEntity(
                id = crawlId,
                packageId = packageId,
                screenName = screenName,
                elementsJson = elements.take(50).map { it.first to it.second }.toString(),
                navPathJson = """["$screenName"]""",
                crawledAt = System.currentTimeMillis(),
                contentHash = sha256(elements.joinToString { it.second })
            )
            db.screenCrawlDao().insert(entity)
            results.add(entity)
            count++

            // Try to go deeper — tap first clickable node from screen tree
            val tapped = a11y.dumpScreenTree()
                .firstOrNull { it.isClickable }
                ?.let { nodeInfo ->
                    a11y.findByResourceId(nodeInfo.resourceId)?.let { node ->
                        a11y.tap(node)
                        delay(1500)
                        true
                    }
                } ?: false
            if (!tapped) break
        }
        a11y.pressHome()
        results
    }

    /**
     * Labels UI elements with no text/contentDesc using Gemma vision.
     * Operates on elements stored in the database for the given package.
     */
    suspend fun labelUnknownElements(
        packageId: String,
        a11y: com.omnix.agent.core.OmnixAccessibilityService
    ): Int = withContext(Dispatchers.IO) {
        if (!GemmaInferenceEngine.isReady()) return@withContext 0
        val screens = try { db.screenDao().getForApp(packageId) } catch (e: Exception) { return@withContext 0 }
        var labeled = 0
        screens.chunked(5).forEach { batch ->
            batch.forEach { screen ->
                val unlabeled = try {
                    db.elementDao().getForScreen(screen.id).filter {
                        it.text.isBlank() && it.contentDesc.isBlank() && it.visionLabel.isBlank()
                    }
                } catch (e: Exception) { emptyList() }
                unlabeled.forEach { element ->
                    val visionResult = try {
                        GemmaInferenceEngine.classifyScreen(element.className)
                    } catch (e: Exception) { null }
                    if (!visionResult.isNullOrBlank()) {
                        try {
                            db.elementDao().upsert(element.copy(visionLabel = visionResult.trim().take(80)))
                        } catch (_: Exception) {}
                        labeled++
                    }
                }
            }
            delay(100)
        }
        labeled
    }

    /**
     * Synthesizes skills from discovered screen navigation paths.
     */
    suspend fun generateSkillsFromNavPaths(packageId: String): Int = withContext(Dispatchers.IO) {
        val crawls = db.screenCrawlDao().getForApp(packageId)
        if (crawls.isEmpty() || !GemmaInferenceEngine.isReady()) return@withContext 0
        val navDesc = crawls.take(10).joinToString("\n") {
            "Screen: ${it.screenName}, Elements: ${it.elementsJson.take(100)}"
        }
        val prompt = "App: $packageId\nScreens:\n$navDesc\n\nList 3 useful skill names for this app, one per line."
        return@withContext try {
            val response = GemmaInferenceEngine.generate(
                "You are an Android automation expert.", prompt, maxTokens = 100
            )
            val skillNames = response.lines().filter { it.isNotBlank() }.take(3)
            skillNames.forEach { name ->
                val skillId = "auto_${packageId}_${name.replace(" ", "_").lowercase().take(30)}"
                val emb = GemmaInferenceEngine.generateEmbedding(name)
                db.skillDao().upsert(
                    SkillEntity(
                        id = skillId,
                        appId = packageId,
                        name = name.trim(),
                        type = "ui_automation",
                        category = "auto_generated",
                        version = "1.0",
                        intentPatternsJson = """["${name.trim()}"]""",
                        parametersJson = "{}",
                        stepsJson = "[]",
                        confirmationRequired = false,
                        embedding = com.omnix.agent.ai.floatArrayToBytes(emb),
                        intentHash = sha256(name).take(16),
                        status = "active"
                    )
                )
            }
            skillNames.size
        } catch (e: Exception) { 0 }
    }

    private fun sha256(input: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        return d.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
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
