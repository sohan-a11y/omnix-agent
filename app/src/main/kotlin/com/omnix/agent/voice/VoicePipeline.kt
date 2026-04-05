package com.omnix.agent.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineActivationException
import com.omnix.agent.BuildConfig
import com.omnix.agent.ai.GemmaInferenceEngine
import com.omnix.agent.executor.OmnixOrchestrator
import kotlinx.coroutines.*

object VoicePipeline {

    private var porcupine: Porcupine? = null
    private var recorder: AudioRecord? = null
    @Volatile private var running = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(ctx: Context) {
        if (running) return
        running = true

        try {
            porcupine = Porcupine.Builder()
                .setAccessKey(BuildConfig.PORCUPINE_KEY)
                .setKeywordPath("omnix_android_arm64.ppn") // Snapdragon-optimized model
                .setSensitivity(0.7f)
                .build(ctx)

            scope.launch { audioLoop(ctx) }
        } catch (e: PorcupineActivationException) {
            // Invalid access key - disable voice until key is set
        } catch (e: Exception) {
            // Fallback: disable voice, app still works via UI
        }
    }

    fun stop() {
        running = false
        recorder?.stop()
        recorder?.release()
        recorder = null
        porcupine?.delete()
        porcupine = null
        scope.cancel()
    }

    private suspend fun audioLoop(ctx: Context) = withContext(Dispatchers.IO) {
        val porcObj = porcupine ?: return@withContext
        val bufSize = AudioRecord.getMinBufferSize(
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize * 2
        ).also { it.startRecording() }

        val frame = ShortArray(porcObj.frameLength)

        while (running && isActive) {
            val read = recorder?.read(frame, 0, frame.size) ?: break
            if (read <= 0) continue

            try {
                val keywordIndex = porcObj.process(frame)
                if (keywordIndex >= 0) {
                    // Wake word detected!
                    onWakeWordDetected(ctx)
                }
            } catch (e: Exception) {
                // Continue loop despite transient errors
            }
        }
    }

    private suspend fun onWakeWordDetected(ctx: Context) {
        TTS.speak("Yes?", TTS.QUEUE_FLUSH)

        // Capture user command via ASR
        val command = ASREngine.captureCommand(timeoutMs = 5000) ?: return
        if (command.isBlank()) return

        TTS.speak("Got it. Processing...", TTS.QUEUE_ADD)

        // Extract intent and execute
        val intent = GemmaInferenceEngine.extractIntent(command)
        if (intent.ambiguous && intent.clarification != null) {
            TTS.speak(intent.clarification, TTS.QUEUE_FLUSH)
            return
        }

        OmnixOrchestrator.handleVoiceIntent(intent, command)
    }

    fun isRunning() = running
}
