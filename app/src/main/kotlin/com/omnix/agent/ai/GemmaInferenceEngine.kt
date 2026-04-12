package com.omnix.agent.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.omnix.agent.database.OmnixDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

/**
 * Gemma 4 E2B — the AI brain of OMNIX.
 *
 * Uses LiteRT-LM (com.google.ai.edge.litertlm) to load the .litertlm model
 * with GPU acceleration on Snapdragon (arm64 via OpenCL).
 *
 * ALL intent parsing goes through Gemma when loaded.
 * Knowledge base: every discovered app is injected into the system prompt.
 */
object GemmaInferenceEngine {

    private const val TAG = "GemmaEngine"

    private var engine: Engine? = null
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var appKnowledge: String = ""
    @Volatile private var currentApp: String = ""

    // Multi-turn chat session
    private val chatHistory = mutableListOf<String>()

    // ── Init ───────────────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        if (engine != null) {
            Log.i(TAG, "Gemma already initialized")
            return
        }
        val appCtx = context.applicationContext
        scope.launch { initBlocking(appCtx) }
    }

    private suspend fun initBlocking(context: Context) = withContext(Dispatchers.IO) {
        val modelFile = ModelDownloadManager.getModelFile(context)
        if (!modelFile.exists()) {
            Log.w(TAG, "Model not found at ${modelFile.absolutePath}")
            return@withContext
        }
        Log.i(TAG, "Loading Gemma 4 E2B (${modelFile.length() / 1_048_576} MB) with GPU…")
        try {
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            val config = EngineConfig(
                modelPath  = modelFile.absolutePath,
                backend    = Backend.GPU(),
                cacheDir   = context.cacheDir.absolutePath,
                maxNumTokens = 4096
            )
            val e = Engine(config)
            e.initialize()
            engine = e
            Log.i(TAG, "✅ Gemma 4 E2B ready — GPU backend active")
            loadAppKnowledge(context)
        } catch (gpuEx: Exception) {
            Log.w(TAG, "GPU init failed (${gpuEx.message}), retrying with CPU…")
            try {
                val config = EngineConfig(
                    modelPath    = modelFile.absolutePath,
                    backend      = Backend.CPU(),
                    cacheDir     = context.cacheDir.absolutePath,
                    maxNumTokens = 2048
                )
                val e = Engine(config)
                e.initialize()
                engine = e
                Log.i(TAG, "✅ Gemma 4 E2B ready — CPU backend")
                loadAppKnowledge(context)
            } catch (cpuEx: Exception) {
                Log.e(TAG, "❌ Gemma load failed: ${cpuEx.message}")
                engine = null
            }
        }
    }

    suspend fun loadAppKnowledge(context: Context) {
        try {
            val count = AppKnowledgeEngine.refresh(context)
            appKnowledge = AppKnowledgeEngine.buildConversationSummary()
            Log.i(TAG, "App knowledge: $count apps loaded into Gemma")
        } catch (e: Exception) {
            Log.w(TAG, "App knowledge load failed: ${e.message}")
        }
    }

    fun setCurrentApp(pkg: String) { currentApp = pkg }
    fun isReady(): Boolean = engine != null

    // ── Core generation ────────────────────────────────────────────────────────

    /**
     * Single-turn generation with a system prompt.
     * Uses a fresh conversation per call — correct for structured JSON tasks.
     */
    suspend fun generate(system: String, user: String): String = mutex.withLock {
        val e = engine ?: return@withLock "{}"
        return@withLock withContext(Dispatchers.IO) {
            try {
                val conv = e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(system),
                        samplerConfig     = SamplerConfig(topK = 1, topP = 0.95, temperature = 0.1)
                    )
                )
                try {
                    val chunks = mutableListOf<String>()
                    conv.sendMessageAsync(user)
                        .catch { ex -> Log.e(TAG, "Generation error: ${ex.message}") }
                        .collect { msg -> chunks.add(msg.toString()) }
                    chunks.joinToString("")
                } finally {
                    conv.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "generate() failed: ${e.message}")
                "{}"
            }
        }
    }

    // ── Intent extraction — Gemma is the ONLY path ────────────────────────────

    suspend fun extractIntent(query: String): IntentResult? {
        if (engine == null) {
            Log.w(TAG, "Gemma not ready — model not loaded")
            return null
        }
        return runCatching { extractWithGemma(query) }
            .onFailure { Log.e(TAG, "extractIntent error: ${it.message}") }
            .getOrNull()
    }

    private suspend fun extractWithGemma(query: String): IntentResult {
        val raw = generate(buildIntentSystem(query), query)
        Log.d(TAG, "Gemma raw: ${raw.take(300)}")
        return try {
            json.decodeFromString<IntentResult>(raw.extractJsonBlock())
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed: ${raw.take(100)}")
            IntentResult(
                intent        = "parse_error",
                entities      = emptyMap(),
                confidence    = 0f,
                ambiguous     = true,
                clarification = "I didn't catch that. Could you say it again?"
            )
        }
    }

    private fun buildIntentSystem(query: String): String = buildString {
        appendLine("""
You are OMNIX, an Android AI assistant. Convert voice commands to JSON intents.
Output ONLY valid JSON — no markdown, no explanation.

Schema:
{"intent":"<name>","entities":{"app":"<package>","app_name":"","contact":"","amount":"","text":"","query":"","destination":"","time":"","task":""},"confidence":0.95,"ambiguous":false,"clarification":""}

Intents: launch_app | make_call | send_message | transfer_money | check_balance | navigate | set_alarm | set_reminder | play_music | search_web | take_photo | send_email | youtube_play | open_settings | unknown

Rules:
- launch_app → put exact package name in entities.app
- Prefer packages from DEVICE APP INDEX and map nicknames or misspellings to the closest installed app
- make_call → entities.contact = person name
- send_message → entities.contact, text, app (package)
- transfer_money → entities.contact, amount (digits only)
- navigate → entities.destination
- set_alarm → entities.time
- unknown → confidence < 0.3, ambiguous=true, clarification=ask user
        """.trimIndent())

        val relevantAppKnowledge = AppKnowledgeEngine.buildIntentContext(query)
        if (relevantAppKnowledge.isNotBlank()) {
            appendLine()
            appendLine(relevantAppKnowledge)
        }
        if (currentApp.isNotBlank()) appendLine("CURRENT APP: $currentApp")
    }

    // ── Chat (multi-turn conversation) ────────────────────────────────────────

    /**
     * Multi-turn conversational response.
     * Reuses a persistent Conversation session (up to 20 turns, then resets).
     * Used by ChatActivity for natural back-and-forth dialogue.
     */
    suspend fun converse(userMessage: String): String = mutex.withLock {
        val e = engine
            ?: return@withLock "The AI model isn't loaded yet. Download Gemma from the setup screen."
        return@withLock withContext(Dispatchers.IO) {
            try {
                val prompt = buildString {
                    if (chatHistory.isNotEmpty()) {
                        appendLine("Recent context:")
                        chatHistory.forEach { appendLine(it) }
                        appendLine()
                    }
                    appendLine("User: $userMessage")
                    appendLine("AI:")
                }

                val conv = e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(buildChatSystem()),
                        samplerConfig     = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
                    )
                )
                
                val chunks = mutableListOf<String>()
                try {
                    conv.sendMessageAsync(prompt)
                        .catch { ex -> Log.e(TAG, "Converse error: ${ex.message}") }
                        .collect { msg -> chunks.add(msg.toString()) }
                } finally {
                    conv.close()
                }
                
                val reply = chunks.joinToString("").trim()
                val finalReply = reply.ifEmpty { "I'm not sure how to respond to that." }
                
                chatHistory.add("User: $userMessage")
                chatHistory.add("AI: $finalReply")
                if (chatHistory.size > 10) chatHistory.subList(0, 2).clear() // keep last 5 turns
                
                finalReply
            } catch (ex: Exception) {
                Log.e(TAG, "converse() failed: ${ex.message}")
                "Sorry, something went wrong. Please try again."
            }
        }
    }

    fun clearChatHistory() {
        chatHistory.clear()
    }

    private fun buildChatSystem(): String = buildString {
        appendLine("You are OMNIX, an intelligent on-device AI assistant for Android.")
        appendLine("You help with Android tasks (open apps, send messages, calls, navigation, etc.) and hold natural conversations.")
        appendLine("Be concise, friendly, and helpful. When you execute a task say what you did.")
        appendLine("You run entirely on-device — AI processing needs no internet.")
        appendLine("Never say you are just a language model or that you cannot access the device.")
        appendLine("If the user asks for an action and you are only chatting, ask a short clarification instead of refusing the action.")
        if (appKnowledge.isNotBlank()) {
            appendLine()
            appendLine(appKnowledge)
        }
        if (currentApp.isNotBlank()) appendLine("CURRENT APP: $currentApp")
    }

    // ── Vision ─────────────────────────────────────────────────────────────────

    suspend fun findElementByVision(bmp: Bitmap, label: String): ElementCoords? {
        return try {
            val raw = generate(
                "Find UI element. JSON only: {\"found\":true,\"x_pct\":0.5,\"y_pct\":0.5,\"confidence\":0.9}",
                "label: $label"
            )
            json.decodeFromString(raw.extractJsonBlock())
        } catch (_: Exception) { null }
    }

    // ── Skill reranking ────────────────────────────────────────────────────────

    suspend fun rerankSkills(intent: IntentResult, candidates: List<String>): Int {
        val list = candidates.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n")
        val raw = generate(
            "Pick best skill for intent. Reply with ONLY a single digit (1, 2, or 3).",
            "Intent: ${intent.intent}\nEntities: ${intent.entities}\nOptions:\n$list"
        )
        return raw.trim().firstOrNull()?.digitToIntOrNull()?.minus(1) ?: 0
    }

    // ── Embedding (deterministic, always works) ────────────────────────────────

    suspend fun generateEmbedding(text: String): FloatArray = mutex.withLock {
        tfidfEmbedding(text)
    }

    private fun tfidfEmbedding(text: String): FloatArray {
        val dims = 768
        val v = FloatArray(dims)
        val words = text.lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.isEmpty()) return v
        words.forEach { w ->
            w.forEachIndexed { i, c ->
                v[Math.abs(w.hashCode() * 31 + c.code + i * 7) % dims] += 1f / words.size
            }
        }
        val norm = Math.sqrt(v.map { it * it }.sum().toDouble()).toFloat()
        if (norm > 0f) v.forEachIndexed { i, x -> v[i] = x / norm }
        return v
    }

    // ── Context / screen helpers ───────────────────────────────────────────────

    suspend fun compactContext(messages: List<String>, goal: String): String =
        generate(
            "Compress task history into max 300 words. Preserve: goal, actions, results.",
            "Goal: $goal\nHistory:\n${messages.joinToString("\n")}"
        )

    suspend fun classifyScreen(screenTree: String): String =
        generate(
            "Classify Android screen. JSON only: {\"screen_type\":\"login|home|list|form|payment|settings|other\",\"confidence\":0.9}",
            screenTree
        )
}

// ── Data classes ───────────────────────────────────────────────────────────────

@Serializable
data class IntentResult(
    val intent: String,
    val entities: Map<String, String?> = emptyMap(),
    val confidence: Float = 0f,
    val ambiguous: Boolean = false,
    val clarification: String? = null
)

@Serializable
data class ElementCoords(
    val found: Boolean,
    val xPct: Float = 0f,
    val yPct: Float = 0f,
    val confidence: Float = 0f
)

// ── Extensions ────────────────────────────────────────────────────────────────

fun Bitmap.toBase64Jpeg(quality: Int = 40): String {
    val s = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, s)
    return Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
}

fun String.extractJsonBlock(): String {
    val start = indexOfFirst { it == '{' || it == '[' }
    val end   = indexOfLast  { it == '}' || it == ']' }
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
