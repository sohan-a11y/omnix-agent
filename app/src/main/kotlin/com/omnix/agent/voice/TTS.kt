package com.omnix.agent.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

object TTS {
    val DEFAULT_LOCALE = Locale("en", "IN")
    const val QUEUE_FLUSH = TextToSpeech.QUEUE_FLUSH
    const val QUEUE_ADD   = TextToSpeech.QUEUE_ADD

    private var tts: TextToSpeech? = null
    @Volatile private var initialized = false

    // Maps utterance ID → coroutine continuation so concurrent speakAndWait()
    // calls each get their own callback without overwriting each other.
    private val pendingResumes = ConcurrentHashMap<String, () -> Unit>()

    fun initialize(context: Context, onReady: (() -> Unit)? = null) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = DEFAULT_LOCALE
                tts?.setSpeechRate(1.1f)
                tts?.setPitch(1.0f)
                // Single shared listener — dispatches to the right continuation by ID
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { pendingResumes.remove(it)?.invoke() }
                    }
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { pendingResumes.remove(it)?.invoke() }
                    }
                })
                initialized = true
                onReady?.invoke()
            }
        }
    }

    fun speak(text: String, queueMode: Int = QUEUE_ADD) {
        if (!initialized) return
        tts?.speak(text, queueMode, null, "omnix_${UUID.randomUUID()}")
    }

    suspend fun speakAndWait(text: String, queueMode: Int = QUEUE_ADD): Unit =
        suspendCancellableCoroutine { cont ->
            if (!initialized) { cont.resume(Unit); return@suspendCancellableCoroutine }

            val id = "await_${UUID.randomUUID()}"
            pendingResumes[id] = { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { pendingResumes.remove(id) }
            tts?.speak(text, queueMode, null, id)
        }

    fun stop() { tts?.stop() }

    fun shutdown() {
        pendingResumes.values.forEach { it() }
        pendingResumes.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
    }

    fun isReady() = initialized
}
