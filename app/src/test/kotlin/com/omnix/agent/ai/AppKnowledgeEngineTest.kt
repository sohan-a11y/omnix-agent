package com.omnix.agent.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppKnowledgeEngineTest {

    private val apps = listOf(
        AppKnowledgeEngine.LearnedApp(
            name = "WhatsApp",
            packageName = "com.whatsapp",
            category = "messaging",
            launchActivity = "com.whatsapp.Main",
            capabilityTags = listOf("messaging", "phone_calls")
        ),
        AppKnowledgeEngine.LearnedApp(
            name = "PhonePe",
            packageName = "com.phonepe.app",
            category = "payments",
            launchActivity = "com.phonepe.app.MainActivity",
            capabilityTags = listOf("payments")
        ),
        AppKnowledgeEngine.LearnedApp(
            name = "Google Maps",
            packageName = "com.google.android.apps.maps",
            category = "travel",
            launchActivity = "com.google.android.maps.MapsActivity",
            capabilityTags = listOf("navigation")
        )
    )

    @Test
    fun `extractLaunchTarget handles polite launch requests`() {
        val target = AppKnowledgeEngine.extractLaunchTarget("Could you open the WhatsApp app for me")
        assertEquals("whatsapp", target)
    }

    @Test
    fun `resolveApp matches fuzzy launch query`() {
        val resolved = AppKnowledgeEngine.resolveApp(apps, "open watsapp")
        assertNotNull(resolved)
        assertEquals("com.whatsapp", resolved!!.packageName)
    }

    @Test
    fun `buildPromptSlice stays query-relevant`() {
        val prompt = AppKnowledgeEngine.buildPromptSlice(apps, "navigate to the airport", maxApps = 2)
        assertTrue(prompt.contains("Google Maps"))
        assertTrue(prompt.contains("com.google.android.apps.maps"))
    }

    @Test
    fun `resolveApp accepts exact package hints`() {
        val resolved = AppKnowledgeEngine.resolveApp(
            apps = apps,
            query = "open whatsapp",
            packageHint = "com.whatsapp"
        )
        assertNotNull(resolved)
        assertEquals("WhatsApp", resolved!!.name)
    }
}
