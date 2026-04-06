package com.omnix.agent.voice

import org.junit.Test
import org.junit.Assert.*
import java.util.Locale

class TTSTest {

    @Test
    fun `DEFAULT_LOCALE language is en`() {
        assertEquals("en", TTS.DEFAULT_LOCALE.language)
    }

    @Test
    fun `DEFAULT_LOCALE country is IN`() {
        assertEquals("IN", TTS.DEFAULT_LOCALE.country)
    }

    @Test
    fun `DEFAULT_LOCALE is not US English`() {
        assertNotEquals(Locale.US, TTS.DEFAULT_LOCALE)
    }
}

class VoicePipelineTest {

    @Test
    fun `PPN_MODEL_PATH contains models slash omnix_android_arm64 dot ppn`() {
        assertTrue(
            "PPN path must reference filesDir-relative path",
            VoicePipeline.PPN_MODEL_PATH.contains("models/omnix_android_arm64.ppn")
        )
    }
}
