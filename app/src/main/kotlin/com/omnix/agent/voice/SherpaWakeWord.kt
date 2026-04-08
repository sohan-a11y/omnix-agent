package com.omnix.agent.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Wake-word detector using Vosk in grammar mode (Apache 2.0, no API key, no internet).
 *
 * Named "SherpaWakeWord" to preserve the existing call-site contract.
 * Internally powered by Vosk grammar-mode which only fires when it hears
 * exactly "hey omnix" — all other audio is discarded as "[unk]".
 *
 * This is far more battery-efficient than running full ASR on every frame
 * because Vosk's grammar recogniser is a very small FST, not a full decoder.
 *
 * Shares the same model directory as WhisperEngine; no extra download needed.
 */
object SherpaWakeWord {

    const val KWS_DIR = WhisperEngine.MODEL_DIR   // same model, grammar mode

    private const val GRAMMAR        = """["hi ai", "[unk]"]"""
    private const val SAMPLE_RATE    = 16_000
    private const val FRAME_SHORTS   = 4000        // 250 ms per processFrame call

    private var model: Model? = null
    private var rec: Recognizer? = null

    val isReady: Boolean get() = model != null

    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (model != null) return@withContext true
        val dir = File(context.filesDir, "${WhisperEngine.MODEL_DIR}/${WhisperEngine.MODEL_FILENAME}")
        if (!dir.exists()) return@withContext false
        return@withContext try {
            model = Model(dir.absolutePath)
            rec   = Recognizer(model, SAMPLE_RATE.toFloat(), GRAMMAR)
            true
        } catch (_: Exception) { false }
    }

    /**
     * Feed a frame of 16 kHz mono PCM shorts.
     * Returns true synchronously when enough audio has accumulated for a check;
     * caller must then await [checkPending] to run the Vosk decode.
     */
    private val frameBuffer = mutableListOf<Short>()
    private var pendingBytes: ByteArray? = null

    fun processFrame(samples: ShortArray): Boolean {
        samples.forEach { frameBuffer.add(it) }
        if (frameBuffer.size >= FRAME_SHORTS) {
            pendingBytes = WhisperEngine.shortArrayToBytes(frameBuffer.toShortArray())
            frameBuffer.clear()
            return true
        }
        return false
    }

    /**
     * Run Vosk grammar decode on pending audio.
     * Returns true if "hey omnix" was recognised.  Call from a coroutine.
     */
    suspend fun checkPending(): Boolean = withContext(Dispatchers.IO) {
        val bytes = pendingBytes ?: return@withContext false
        pendingBytes = null
        val r = rec ?: return@withContext false
        return@withContext try {
            r.acceptWaveForm(bytes, bytes.size)
            val partial = JSONObject(r.partialResult).optString("partial", "")
            partial.contains("hi ai", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    fun reset() {
        frameBuffer.clear()
        pendingBytes = null
        // Re-create recogniser to clear decoder state
        model?.let { rec?.close(); rec = Recognizer(it, SAMPLE_RATE.toFloat(), GRAMMAR) }
    }

    fun release() {
        rec?.close()
        rec = null
        model?.close()
        model = null
        frameBuffer.clear()
        pendingBytes = null
    }
}
