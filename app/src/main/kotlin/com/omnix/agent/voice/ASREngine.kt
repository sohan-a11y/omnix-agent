package com.omnix.agent.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Records a voice command after wake-word detection and transcribes it with
 * Vosk (free, on-device, Apache 2.0 — no Google services, no internet, no API key).
 *
 * Recording stops when silence is detected or the timeout elapses.
 */
object ASREngine {

    private const val SAMPLE_RATE            = 16_000
    private const val FRAME_SHORTS           = 512
    private const val SILENCE_RMS_THRESHOLD  = 200f
    private const val SILENCE_FRAMES_TO_STOP = 25     // ~800 ms at 512 samples/frame

    suspend fun captureCommand(
        context: Context,
        timeoutMs: Long = 7000
    ): String? = withContext(Dispatchers.IO) {
        if (!WhisperEngine.isReady) return@withContext null

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4
        )
        recorder.startRecording()

        val allSamples   = mutableListOf<Short>()
        val frame        = ShortArray(FRAME_SHORTS)
        var silenceCount = 0
        val deadline     = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val read = recorder.read(frame, 0, frame.size)
            if (read <= 0) continue
            for (i in 0 until read) allSamples.add(frame[i])

            val sumSq = (0 until read).sumOf { frame[it].toLong() * frame[it] }
            val rms   = Math.sqrt(sumSq.toDouble() / read).toFloat()

            if (rms < SILENCE_RMS_THRESHOLD) {
                silenceCount++
                if (silenceCount >= SILENCE_FRAMES_TO_STOP && allSamples.size > SAMPLE_RATE / 2) break
            } else {
                silenceCount = 0
            }
        }

        recorder.stop()
        recorder.release()

        if (allSamples.size < SAMPLE_RATE / 4) return@withContext null

        WhisperEngine.transcribe(allSamples.toShortArray()).ifBlank { null }
    }
}
