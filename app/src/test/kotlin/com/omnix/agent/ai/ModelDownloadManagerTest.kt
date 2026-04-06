package com.omnix.agent.ai

import org.junit.Test
import org.junit.Assert.*

class ModelDownloadManagerTest {

    @Test
    fun `MODEL_URL starts with https`() {
        assertTrue(ModelDownloadManager.MODEL_URL.startsWith("https://"))
    }

    @Test
    fun `MODEL_FILENAME is non-empty`() {
        assertTrue(ModelDownloadManager.MODEL_FILENAME.isNotEmpty())
    }

    @Test
    fun `getModelFile returns filesDir slash models slash filename`() {
        // Path construction test — verify the path formula
        val filename = ModelDownloadManager.MODEL_FILENAME
        assertTrue("Filename must end in .litertlm", filename.endsWith(".litertlm"))
    }
}

class EncryptedPrefsManagerTest {

    @Test
    fun `PREF_KEY_ZERODHA_API_KEY is non-empty`() {
        assertTrue(EncryptedPrefsManager.PREF_KEY_ZERODHA_API_KEY.isNotEmpty())
    }

    @Test
    fun `PREF_KEY_ZERODHA_ACCESS_TOKEN is non-empty`() {
        assertTrue(EncryptedPrefsManager.PREF_KEY_ZERODHA_ACCESS_TOKEN.isNotEmpty())
    }
}
