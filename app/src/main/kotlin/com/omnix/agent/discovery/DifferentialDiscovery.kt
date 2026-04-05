package com.omnix.agent.discovery

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.omnix.agent.database.OmnixDatabase
import com.omnix.agent.database.ScreenEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Differential Discovery + Intent Deep-Linking (Task 34)
 * Only re-crawls screens that changed since last discovery.
 * Uses deep links to navigate directly to specific screens.
 */
class DifferentialDiscovery(private val context: Context) {

    private val db = OmnixDatabase.getInstance(context)

    /**
     * Compares current APK layout hashes with stored hashes.
     * Returns list of screen IDs that need re-crawling.
     */
    suspend fun findChangedScreens(
        packageId: String,
        newLayouts: List<ScreenLayout>
    ): List<String> = withContext(Dispatchers.IO) {
        val existingScreens = db.screenDao().getForApp(packageId)
            .associateBy { it.id }

        newLayouts.filter { layout ->
            val existing = existingScreens[layout.screenId]
            existing == null || existing.contentHash != layout.contentHash
        }.map { it.screenId }
    }

    /**
     * Navigate to a screen via deep link (faster than UI crawl).
     * Falls back to manual navigation if deep link not available.
     */
    suspend fun navigateViaDeepLink(deepLink: DeepLink): Boolean {
        if (deepLink.scheme.isEmpty()) return false

        return try {
            val uri = Uri.parse("${deepLink.scheme}://${deepLink.host}${deepLink.path}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Update only changed screens in the database.
     */
    suspend fun updateChangedScreens(
        packageId: String,
        changedScreenIds: List<String>,
        newLayouts: List<ScreenLayout>
    ) = withContext(Dispatchers.IO) {
        val changedSet = changedScreenIds.toSet()
        val changed = newLayouts.filter { it.screenId in changedSet }

        changed.forEach { layout ->
            db.screenDao().upsert(ScreenEntity(
                id = layout.screenId,
                appId = packageId,
                name = layout.screenId.substringAfterLast("/").removeSuffix(".xml"),
                visionLabel = "",
                elementCount = layout.elements.size,
                contentHash = layout.contentHash
            ))
        }
    }
}
