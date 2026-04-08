package com.omnix.agent.discovery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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

    // ── Stage 2: Classify apps (keyword-based, no Gemma required) ────────────
    fun classifyApp(app: AppEntity): String {
        val pkg  = app.packageName.lowercase()
        val name = app.name.lowercase()
        return when {
            pkg.containsAny("bank","hdfc","icici","sbi","axis","kotak","paytm","phonepe","gpay","upi","bhim","razorpay","zerodha","groww","navi") ||
            name.containsAny("bank","pay","upi","money","wallet","transfer","finance","invest","mutual","stock") -> "banking"

            pkg.containsAny("whatsapp","telegram","signal","messenger","instagram","snapchat","discord","slack","teams","meet","zoom","skype") ||
            name.containsAny("chat","message","call","video","meet") -> "messaging"

            pkg.containsAny("amazon","flipkart","meesho","myntra","snapdeal","nykaa","shop","store","commerce") ||
            name.containsAny("shop","store","buy","deal","cart") -> "shopping"

            pkg.containsAny("swiggy","zomato","blinkit","dunzo","bigbasket","grocer","food","restaurant","dine") ||
            name.containsAny("food","delivery","order","restaurant","eat") -> "food"

            pkg.containsAny("uber","ola","rapido","maps","navigation","yatra","makemytrip","irctc","redbus","metro","travel","flight") ||
            name.containsAny("travel","cab","ride","bus","train","flight","map","route") -> "travel"

            pkg.containsAny("netflix","hotstar","prime","youtube","spotify","gaana","jiocinema","mx","zee","sony","entertainment","music","game") ||
            name.containsAny("video","music","watch","stream","movie","song","game","play") -> "entertainment"

            pkg.containsAny("health","medic","doctor","apollo","pharmeasy","netmeds","practo","fitbit","calorie","yoga","fitness") ||
            name.containsAny("health","doctor","medic","fitness","exercise","calorie","workout") -> "health"

            pkg.containsAny("facebook","twitter","instagram","linkedin","reddit","quora","share","social","community") ||
            name.containsAny("social","community","share","post","follow") -> "social"

            else -> "productivity"
        }
    }

    // ── Deep discovery: reads ALL metadata from PackageManager (no APK zip needed) ─
    // Reads: app name, version, launch activity, ALL activities, permissions,
    // services, receivers — builds a rich capabilities string for Gemma.
    suspend fun discoverApp(packageId: String, forceRefresh: Boolean = false) =
        withContext(Dispatchers.IO) {
            val existing = db.appDao().getById(packageId)
            if (existing?.isDiscovered == true && !forceRefresh) return@withContext

            val pm = context.packageManager
            val appInfo = try {
                pm.getApplicationInfo(packageId, PackageManager.GET_META_DATA)
            } catch (e: Exception) { return@withContext }

            val appName = pm.getApplicationLabel(appInfo).toString()

            // Full package info with activities, permissions, services, receivers
            val pkgInfo = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    packageId,
                    PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_PERMISSIONS or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS
                )
            } catch (e: Exception) { null }

            val version = pkgInfo?.versionName ?: "unknown"
            val launchActivity = pm.getLaunchIntentForPackage(packageId)?.component?.className ?: ""

            // Collect activity names (strip package prefix for readability)
            val activities = pkgInfo?.activities?.map { it.name.removePrefix("$packageId.") }
                ?.filter { it.isNotBlank() && !it.contains("$") }
                ?.take(30) ?: emptyList()

            // Dangerous permissions the app has declared (tells us what it CAN do)
            val permissions = pkgInfo?.requestedPermissions
                ?.filter { it.startsWith("android.permission.") }
                ?.map { it.removePrefix("android.permission.") }
                ?.take(20) ?: emptyList()

            // Services declared
            val services = pkgInfo?.services?.map { it.name.removePrefix("$packageId.") }
                ?.filter { it.isNotBlank() && !it.contains("$") }
                ?.take(10) ?: emptyList()

            // Infer human-readable capabilities from permissions
            val capabilityTags = buildList {
                if (permissions.any { it.contains("CAMERA") }) add("camera")
                if (permissions.any { it.contains("RECORD_AUDIO") || it.contains("MICROPHONE") }) add("microphone")
                if (permissions.any { it.contains("READ_CONTACTS") || it.contains("WRITE_CONTACTS") }) add("contacts")
                if (permissions.any { it.contains("CALL_PHONE") || it.contains("CALL_PRIVILEGED") }) add("phone_calls")
                if (permissions.any { it.contains("SEND_SMS") || it.contains("READ_SMS") || it.contains("RECEIVE_SMS") }) add("sms")
                if (permissions.any { it.contains("READ_CALENDAR") || it.contains("WRITE_CALENDAR") }) add("calendar")
                if (permissions.any { it.contains("ACCESS_FINE_LOCATION") || it.contains("ACCESS_COARSE_LOCATION") }) add("location")
                if (permissions.any { it.contains("READ_EXTERNAL_STORAGE") || it.contains("WRITE_EXTERNAL_STORAGE") || it.contains("MANAGE_EXTERNAL") }) add("storage")
                if (permissions.any { it.contains("INTERNET") }) add("internet")
                if (permissions.any { it.contains("NFC") }) add("nfc")
                if (permissions.any { it.contains("BLUETOOTH") }) add("bluetooth")
                if (permissions.any { it.contains("BODY_SENSORS") || it.contains("ACTIVITY_RECOGNITION") }) add("sensors")
                if (permissions.any { it.contains("PAYMENT") || it.contains("TRANSACTION") }) add("payments")
                if (activities.any { it.lowercase().containsAny("payment","pay","transaction","transfer","upi","bank") }) add("payments")
                if (activities.any { it.lowercase().containsAny("chat","message","conversation","inbox") }) add("messaging")
                if (activities.any { it.lowercase().containsAny("video","camera","photo","capture") }) add("media_capture")
                if (activities.any { it.lowercase().containsAny("map","location","route","navigate") }) add("navigation")
                if (activities.any { it.lowercase().containsAny("cart","checkout","order","product") }) add("shopping")
                if (activities.any { it.lowercase().containsAny("player","stream","watch","listen") }) add("media_playback")
            }.distinct()

            val capabilitiesJson = buildString {
                append("{")
                append("\"capabilities\":${capabilityTags.map { "\"$it\"" }},")
                append("\"activities_count\":${activities.size},")
                append("\"permissions\":${permissions.take(10).map { "\"$it\"" }},")
                append("\"services_count\":${services.size}")
                append("}")
            }

            val app = AppEntity(
                id = packageId,
                name = appName,
                version = version,
                category = "productivity",
                capabilities = capabilitiesJson,
                packageName = packageId,
                launchActivity = launchActivity
            )
            val category = classifyApp(app)
            db.appDao().upsert(app.copy(category = category, isDiscovered = true))
            Log.d("OmnixDisc", "Discovered $packageId → $category | caps=${capabilityTags.size} | activities=${activities.size} | perms=${permissions.size}")
        }

    // ── Deep analysis for a specific app (on-demand only) ────────────────────
    // Only called when OMNIX needs to build automation skills for a specific app.
    suspend fun deepAnalyzeApp(packageId: String) = withContext(Dispatchers.IO) {
        try {
            apkAnalyzer.analyzeApp(packageId)
            Log.d("OmnixDisc", "Deep analysis done: $packageId")
        } catch (e: Exception) {
            Log.w("OmnixDisc", "Deep analysis failed for $packageId: ${e.message}")
        }
    }

    // ── Extension ─────────────────────────────────────────────────────────────
    private fun String.containsAny(vararg keywords: String) =
        keywords.any { this.contains(it, ignoreCase = true) }

    // ── Discover all undiscovered apps in batches ──────────────────────────────
    suspend fun discoverAllApps(onProgress: ((done: Int, total: Int) -> Unit)? = null) =
        withContext(Dispatchers.IO) {
            val undiscovered = db.appDao().getUndiscovered()
            val total = undiscovered.size
            Log.i("OmnixDisc", "discoverAllApps: $total apps to process")
            undiscovered.forEachIndexed { index, app ->
                try {
                    discoverApp(app.id)
                } catch (e: Exception) {
                    Log.w("OmnixDisc", "Failed to discover ${app.id}: ${e.message}")
                }
                onProgress?.invoke(index + 1, total)
                delay(5) // yield the coroutine dispatcher — keeps Samsung happy
            }
            Log.i("OmnixDisc", "discoverAllApps: complete")
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
                "You are an Android automation expert.", prompt
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
