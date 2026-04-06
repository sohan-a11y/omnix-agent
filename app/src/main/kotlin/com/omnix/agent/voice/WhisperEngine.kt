package com.omnix.agent.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * On-device speech-to-text using Vosk (Apache 2.0, no API key, no internet).
 *
 * Named "WhisperEngine" to preserve the existing call-site contract.
 * Internally powered by Vosk which has a proper Maven artifact and works
 * on Android 6+ without any external service.
 *
 * Model: vosk-model-small-en-us-0.15  (~40 MB, good accuracy for Indian English)
 * Download URL: https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
 * Extracted to: filesDir/models/vosk/
 */
object WhisperEngine {

    const val MODEL_DIR      = "models/vosk"
    const val MODEL_FILENAME = "vosk-model-small-en-us-0.15"   // extracted directory name

    private var model: Model? = null

    val isReady: Boolean get() = model != null

    /** Load the Vosk model from filesDir. Returns true on success. */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (model != null) return@withContext true
        val dir = File(context.filesDir, "$MODEL_DIR/$MODEL_FILENAME")
        if (!dir.exists() || !dir.isDirectory) return@withContext false
        return@withContext try {
            model = Model(dir.absolutePath)
            true
        } catch (_: Exception) { false }
    }

    /**
     * Transcribe 16 kHz mono PCM (ShortArray little-endian).
     * Returns empty string on error or if model not loaded.
     */
    suspend fun transcribe(audio: ShortArray): String = withContext(Dispatchers.IO) {
        val m = model ?: return@withContext ""
        return@withContext try {
            val rec = Recognizer(m, 16000f)
            val bytes = shortArrayToBytes(audio)
            rec.acceptWaveForm(bytes, bytes.size)
            val json = rec.finalResult
            rec.close()
            JSONObject(json).optString("text", "").trim()
        } catch (_: Exception) { "" }
    }

    fun release() {
        model?.close()
        model = null
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Convert little-endian ShortArray → ByteArray for Vosk. */
    fun shortArrayToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2]     = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }
}
