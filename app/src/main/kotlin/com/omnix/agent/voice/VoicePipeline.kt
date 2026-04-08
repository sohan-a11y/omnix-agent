package com.omnix.agent.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.omnix.agent.executor.AppPreLauncher
import com.omnix.agent.executor.OmnixOrchestrator
import kotlinx.coroutines.*

/**
 * Always-on voice pipeline.
 *
 * Wake word  →  Sherpa-ONNX KeywordSpotter  (free, on-device, Apache 2.0)
 * ASR        →  Whisper via whisper.cpp JNI  (free, on-device, MIT)
 *
 * Both models are downloaded to filesDir on first run via OnboardingActivity.
 * If models are not yet present the pipeline stays dormant — the app still
 * works via UI touch.
 */
object VoicePipeline {

    private const val SAMPLE_RATE  = 16_000
    private const val FRAME_SHORTS = 512          // 32 ms per frame

    private var recorder: AudioRecord? = null
    @Volatile private var running = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(ctx: Context) {
        if (running) return
        running = true
        scope.launch {
            Log.i("VoicePipeline", "Starting — loading Vosk models…")
            val wakeReady    = SherpaWakeWord.initialize(ctx)
            val whisperReady = WhisperEngine.initialize(ctx)
            Log.i("VoicePipeline", "wakeReady=$wakeReady whisperReady=$whisperReady")
            if (wakeReady && whisperReady) {
                Log.i("VoicePipeline", "Models loaded — listening for 'Hi AI'")
                TTS.speak("I'm listening. Say Hi AI.", TTS.QUEUE_ADD)
                audioLoop(ctx)
            } else {
                Log.w("VoicePipeline", "Models not ready — voice disabled")
                running = false
            }
        }
    }

    fun stop() {
        running = false
        recorder?.stop()
        recorder?.release()
        recorder = null
        SherpaWakeWord.release()
        WhisperEngine.release()
        scope.cancel()
    }

    private suspend fun audioLoop(ctx: Context) = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4
        ).also { it.startRecording() }

        val frame = ShortArray(FRAME_SHORTS)

        while (running && isActive) {
            val read = recorder?.read(frame, 0, frame.size) ?: break
            if (read <= 0) continue

            if (SherpaWakeWord.processFrame(frame.copyOf(read))) {
                // Energy gate fired — run Whisper prefix check asynchronously
                if (SherpaWakeWord.checkPending()) {
                    SherpaWakeWord.reset()
                    onWakeWordDetected(ctx)
                }
            }
        }
    }

    private suspend fun onWakeWordDetected(ctx: Context) {
        Log.i("VoicePipeline", "Wake word detected — capturing command")
        AppPreLauncher.prewarmTopApps(ctx)
        TTS.speak("Yes?", TTS.QUEUE_FLUSH)

        val command = ASREngine.captureCommand(context = ctx, timeoutMs = 7000)
        Log.i("VoicePipeline", "Command captured: '$command'")
        if (command.isNullOrBlank()) return

        TTS.speak("Got it.", TTS.QUEUE_ADD)
        OmnixOrchestrator.handleVoiceIntent(command, ctx)
    }

    fun isRunning() = running
}
