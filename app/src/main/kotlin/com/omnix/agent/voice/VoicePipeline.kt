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

    const val PPN_MODEL_PATH = "models/omnix_android_arm64.ppn"
    private const val SAMPLE_RATE  = 16_000
    private const val FRAME_SHORTS = 512          // 32 ms per frame
    private const val STARTUP_WAKE_SUPPRESSION_MS = 4_000L
    private const val POST_COMMAND_SUPPRESSION_MS = 8_000L

    private var recorder: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var suppressWakeUntilMs = 0L
    @Volatile private var captureInProgress = false
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(ctx: Context) {
        if (running) return
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        running = true
        scope.launch {
            Log.i("VoicePipeline", "Starting — loading Vosk models…")
            val wakeReady    = SherpaWakeWord.initialize(ctx)
            val whisperReady = WhisperEngine.initialize(ctx)
            Log.i("VoicePipeline", "wakeReady=$wakeReady whisperReady=$whisperReady")
            if (wakeReady && whisperReady) {
                Log.i("VoicePipeline", "Models loaded — listening for 'Hi AI'")
                suppressWakeUntilMs = System.currentTimeMillis() + STARTUP_WAKE_SUPPRESSION_MS
                TTS.speakAndWait("I'm listening. Say Hi AI.", TTS.QUEUE_FLUSH)
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
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        captureInProgress = false
        suppressWakeUntilMs = 0L
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
            if (captureInProgress || System.currentTimeMillis() < suppressWakeUntilMs) continue

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
        if (captureInProgress) return
        captureInProgress = true
        Log.i("VoicePipeline", "Wake word detected — capturing command")
        AppPreLauncher.prewarmTopApps(ctx)
        TTS.stop()

        try {
            val command = ASREngine.captureCommand(context = ctx, timeoutMs = 7000)
            Log.i("VoicePipeline", "Command captured: '$command'")
            if (command.isNullOrBlank()) return

            TTS.speak("Got it.", TTS.QUEUE_FLUSH)
            suppressWakeUntilMs = System.currentTimeMillis() + POST_COMMAND_SUPPRESSION_MS
            OmnixOrchestrator.handleVoiceIntent(command, ctx)
        } finally {
            captureInProgress = false
            suppressWakeUntilMs = maxOf(suppressWakeUntilMs, System.currentTimeMillis() + 1_500L)
        }
    }

    fun isRunning() = running
}
