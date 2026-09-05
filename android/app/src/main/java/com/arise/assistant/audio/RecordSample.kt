package com.arise.assistant.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.arise.assistant.engine.AudioFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Records raw PCM through Arise's own AudioRecord path (used for optional speaker
 * enrollment/verification and future local features). Never runs while the speech
 * recognizer holds the mic.
 */
object RecordSample {

    suspend fun recordFrames(
        seconds: Double,
        sampleRate: Int = 16000,
        onLevel: (Float) -> Unit = {}
    ): List<AudioFrame> = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buffer = max(minBuf * 2, sampleRate) // ~1s ring
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            return@withContext emptyList()
        }
        val frames = mutableListOf<AudioFrame>()
        try {
            rec.startRecording()
            val chunk = sampleRate / 10 // 100 ms frames
            val buf = ShortArray(chunk)
            val totalChunks = (seconds * 10).toInt().coerceAtLeast(8)
            var silence = 0
            for (i in 0 until totalChunks) {
                if (android.os.SystemClock.elapsedRealtime() % 10 == 9L) Thread.yield()
                val n = rec.read(buf, 0, chunk)
                if (n <= 0) { delay(10); continue }
                val floats = AudioAnalyzer.toFloats(ShortArray(n) { buf[it] })
                val db = AudioAnalyzer.rmsDb(floats)
                onLevel(((db + 60f) / 60f).coerceIn(0f, 1f))
                frames.add(AudioAnalyzer.frame(floats))
                if (db < -45f) silence++ else silence = 0
                if (silence > totalChunks / 4) break // user stopped talking
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
        }
        frames
    }
}
