package com.omnix.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object ASREngine {

    /**
     * Captures a voice command and returns the transcribed text.
     * Uses Android's on-device speech recognition.
     * Returns null on timeout or error.
     */
    suspend fun captureCommand(
        context: Context? = null,
        timeoutMs: Long = 5000
    ): String? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val ctx = context ?: return@suspendCancellableCoroutine

            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    recognizer.destroy()
                    cont.resume(matches?.firstOrNull())
                }

                override fun onError(error: Int) {
                    recognizer.destroy()
                    cont.resume(null)
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            Handler(Looper.getMainLooper()).post {
                recognizer.startListening(intent)
            }

            cont.invokeOnCancellation {
                recognizer.destroy()
            }
        }
    }
}
