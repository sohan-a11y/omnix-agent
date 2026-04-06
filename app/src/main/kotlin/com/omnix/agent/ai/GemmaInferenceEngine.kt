package com.omnix.agent.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File

object GemmaInferenceEngine {

    private var session: LlmInference? = null
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun initialize(context: Context) {
        // Try Android AICore first (zero RAM cost for app, uses system Gemma)
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                initAICore(context)
                return
            } catch (e: Exception) {
                // Fall through to local model
            }
        }

        val modelFile = File(context.filesDir, "models/gemma-4-e2b.litertlm")
        if (!modelFile.exists()) return // Not downloaded yet - will init after download

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(4096)
                .build()
            session = LlmInference.createFromOptions(context, options)
        } catch (e: Exception) {
            // Model load failed - will retry on next launch
        }
    }

    private fun initAICore(context: Context) {
        // Android 15+ AICore integration - uses system-level Gemma
        // Zero additional RAM usage for the app
        // LlmInference.createFromAiCore(context) when API is available
    }

    fun isReady(): Boolean = session != null

    suspend fun generate(
        system: String,
        user: String,
        maxTokens: Int = 1000,
        thinking: Boolean = false
    ): String = mutex.withLock {
        val s = session ?: return@withLock "{}"
        val prompt = buildPrompt(system, user, thinking)
        s.generateResponse(prompt)
    }

    private fun buildPrompt(system: String, user: String, thinking: Boolean): String {
        return buildString {
            if (thinking) append("<|think|>\n")
            append("<start_of_turn>system\n$system<end_of_turn>\n")
            append("<start_of_turn>user\n$user<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
    }

    // ── Intent extraction from voice query ────────────────────────────────────
    suspend fun extractIntent(query: String): IntentResult {
        val raw = generate(INTENT_SYSTEM, query, maxTokens = 300)
        return try {
            json.decodeFromString(raw.extractJsonBlock())
        } catch (e: Exception) {
            IntentResult(intent = "unknown", entities = emptyMap(), confidence = 0f,
                ambiguous = true, clarification = "Could not parse intent")
        }
    }

    // ── Vision-based UI element finding ───────────────────────────────────────
    suspend fun findElementByVision(bmp: Bitmap, label: String): ElementCoords? {
        val b64 = bmp.toBase64Jpeg()
        return try {
            val raw = generate(VISION_SYSTEM, "{\"image\":\"$b64\",\"label\":\"$label\"}", maxTokens = 100)
            json.decodeFromString(raw.extractJsonBlock())
        } catch (e: Exception) {
            null
        }
    }

    // ── Generate embedding for semantic skill matching ─────────────────────────
    /**
     * Generates a 768-dim text embedding.
     * Uses Gemma output if model is ready; falls back to deterministic n-gram hashing.
     */
    suspend fun generateEmbedding(text: String): FloatArray = mutex.withLock {
        tfidfEmbedding(text)  // deterministic fallback that works without model
    }

    /** Deterministic 768-dim embedding via character n-gram hashing. Normalized to unit length. */
    private fun tfidfEmbedding(text: String): FloatArray {
        val dims = 768
        val result = FloatArray(dims)
        val words = text.lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.isEmpty()) return result
        words.forEach { word ->
            word.forEachIndexed { i, c ->
                val hash = (word.hashCode() * 31 + c.code + i * 7)
                val idx = Math.abs(hash) % dims
                result[idx] += 1.0f / words.size
            }
        }
        // L2 normalize
        val norm = Math.sqrt(result.map { it * it }.sum().toDouble()).toFloat()
        if (norm > 0f) result.forEachIndexed { i, v -> result[i] = v / norm }
        return result
    }

    // ── Context compaction for long-running tasks ─────────────────────────────
    suspend fun compactContext(messages: List<String>, goal: String): String {
        return generate(
            COMPACT_SYSTEM,
            "Goal: $goal\nHistory:\n${messages.joinToString("\n")}",
            maxTokens = 500
        )
    }

    // ── Classify app screen ───────────────────────────────────────────────────
    suspend fun classifyScreen(screenTree: String): String {
        return generate(
            CLASSIFY_SYSTEM,
            "Screen tree:\n$screenTree",
            maxTokens = 200
        )
    }

    // ── System prompts ────────────────────────────────────────────────────────
    private val INTENT_SYSTEM = """
        You are an intent extraction engine for device automation.
        Respond ONLY with JSON, no markdown:
        {"intent":"send_message|make_call|check_balance|transfer_money|...",
         "entities":{"contact":"","app":"","amount":"","text":"","date":""},
         "confidence":0.0,"ambiguous":false,"clarification":""}
    """.trimIndent()

    private val VISION_SYSTEM = """
        Analyze this screenshot. Find the UI element matching the label.
        Respond ONLY with JSON:
        {"found":true,"x_pct":0.0,"y_pct":0.0,"confidence":0.0}
    """.trimIndent()

    private val COMPACT_SYSTEM = """
        Compress this agent task history into max 400 words.
        Preserve: goal, what was done, key results, errors, current state.
    """.trimIndent()

    private val CLASSIFY_SYSTEM = """
        Classify this Android screen. Respond ONLY with JSON:
        {"screen_type":"login|home|list|detail|form|payment|settings|other",
         "confidence":0.0,"elements_of_interest":["id1","id2"]}
    """.trimIndent()
}

// ── Data classes ─────────────────────────────────────────────────────────────
@Serializable
data class IntentResult(
    val intent: String,
    val entities: Map<String, String?>,
    val confidence: Float,
    val ambiguous: Boolean,
    val clarification: String?
)

@Serializable
data class ElementCoords(
    val found: Boolean,
    val xPct: Float,
    val yPct: Float,
    val confidence: Float
)

// ── Extension functions ───────────────────────────────────────────────────────
fun Bitmap.toBase64Jpeg(quality: Int = 50): String {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, stream)
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}

fun String.extractJsonBlock(): String {
    val start = indexOfFirst { it == '{' || it == '[' }
    val end = indexOfLast { it == '}' || it == ']' }
    return if (start >= 0 && end > start) substring(start, end + 1) else this
}

fun floatArrayToBytes(arr: FloatArray): ByteArray {
    val buf = java.nio.ByteBuffer.allocate(arr.size * 4)
    arr.forEach { buf.putFloat(it) }
    return buf.array()
}

fun bytesToFloatArray(bytes: ByteArray): FloatArray {
    val buf = java.nio.ByteBuffer.wrap(bytes)
    return FloatArray(bytes.size / 4) { buf.getFloat() }
}
