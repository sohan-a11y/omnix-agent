package com.omnix.agent.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ASREngine {

    private const val SAMPLE_RATE            = 16_000
    private const val FRAME_SHORTS           = 512
    private const val SILENCE_RMS_THRESHOLD  = 600f   // raised from 200 — filters room noise
    private const val SILENCE_FRAMES_TO_STOP = 30     // ~960 ms silence = end of speech
    private const val MIN_SPEECH_FRAMES      = 10     // must have spoken for at least ~320 ms

    // Words that are clearly Vosk noise-transcription artifacts
    private val NOISE_PHRASES = setOf(
        "huh", "uh", "um", "ah", "oh", "er", "hmm", "mm",
        "the", "a", "and", "or", "it", "is", "in", "on", "at"
    )

    suspend fun captureCommand(context: Context, timeoutMs: Long = 7000): String? =
        withContext(Dispatchers.IO) {
            if (!WhisperEngine.isReady) return@withContext null

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,   // optimised for speech
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
            recorder.startRecording()

            val allSamples   = mutableListOf<Short>()
            val frame        = ShortArray(FRAME_SHORTS)
            var silenceCount = 0
            var speechFrames = 0
            val deadline     = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                val read = recorder.read(frame, 0, frame.size)
                if (read <= 0) continue
                for (i in 0 until read) allSamples.add(frame[i])

                val sumSq = (0 until read).sumOf { frame[it].toLong() * frame[it] }
                val rms   = Math.sqrt(sumSq.toDouble() / read).toFloat()

                if (rms >= SILENCE_RMS_THRESHOLD) {
                    speechFrames++
                    silenceCount = 0
                } else {
                    silenceCount++
                    // Only stop on silence if we already heard real speech
                    if (silenceCount >= SILENCE_FRAMES_TO_STOP && speechFrames >= MIN_SPEECH_FRAMES) break
                }
            }

            recorder.stop()
            recorder.release()

            // Not enough speech energy — treat as noise, ignore
            if (speechFrames < MIN_SPEECH_FRAMES) {
                Log.d("ASREngine", "Rejected: too little speech (frames=$speechFrames)")
                return@withContext null
            }

            val text = WhisperEngine.transcribe(allSamples.toShortArray()).trim()
            Log.d("ASREngine", "Transcribed: '$text'")

            // Validate: must have at least 2 words and not be a noise artifact
            val words = text.split("\\s+".toRegex()).filter { it.length > 1 }
            if (words.size < 2) {
                Log.d("ASREngine", "Rejected: too short ($words)")
                return@withContext null
            }
            if (words.all { it.lowercase() in NOISE_PHRASES }) {
                Log.d("ASREngine", "Rejected: all noise words ($words)")
                return@withContext null
            }

            text.ifBlank { null }
        }
}
