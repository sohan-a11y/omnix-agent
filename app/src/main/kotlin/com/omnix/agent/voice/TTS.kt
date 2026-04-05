package com.omnix.agent.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

object TTS {
    const val QUEUE_FLUSH = TextToSpeech.QUEUE_FLUSH
    const val QUEUE_ADD = TextToSpeech.QUEUE_ADD

    private var tts: TextToSpeech? = null
    private var initialized = false

    fun initialize(context: Context, onReady: (() -> Unit)? = null) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.1f)
                tts?.setPitch(1.0f)
                initialized = true
                onReady?.invoke()
            }
        }
    }

    fun speak(text: String, queueMode: Int = QUEUE_ADD, utteranceId: String = "omnix_${System.currentTimeMillis()}") {
        if (!initialized) return
        tts?.speak(text, queueMode, null, utteranceId)
    }

    suspend fun speakAndWait(text: String, queueMode: Int = QUEUE_ADD): Unit =
        suspendCancellableCoroutine { cont ->
            if (!initialized) {
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }

            val id = "await_${System.currentTimeMillis()}"
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) cont.resume(Unit)
                }
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id) cont.resume(Unit)
                }
            })
            tts?.speak(text, queueMode, null, id)
        }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
    }

    fun isReady() = initialized
}
