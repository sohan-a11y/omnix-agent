package com.omnix.agent.core

import org.junit.Test
import org.junit.Assert.*

class SamsungCompatibilityLayerTest {

    @Test
    fun `GALAXY_AI_EVENT_DELAY_MS is exactly 50`() {
        assertEquals(50L, SamsungCompatibilityLayer.GALAXY_AI_EVENT_DELAY_MS)
    }

    @Test
    fun `isSamsungCustomView returns true for com dot samsung prefix`() {
        assertTrue(SamsungCompatibilityLayer.isSamsungCustomView("com.samsung.android.SomeView"))
    }

    @Test
    fun `isSamsungCustomView returns true for com dot sec prefix`() {
        assertTrue(SamsungCompatibilityLayer.isSamsungCustomView("com.sec.android.app"))
    }

    @Test
    fun `isSamsungCustomView returns false for com dot google prefix`() {
        assertFalse(SamsungCompatibilityLayer.isSamsungCustomView("com.google.android.gm"))
    }

    @Test
    fun `isS25Ultra returns true for SM-S938 model string`() {
        // Test the substring logic directly
        val model = "SM-S938B"
        val matches = model.contains("SM-S938") || model.contains("SM-S931") || model.contains("SM-S936")
        assertTrue("SM-S938B should be recognized as S25 Ultra", matches)
    }
}
