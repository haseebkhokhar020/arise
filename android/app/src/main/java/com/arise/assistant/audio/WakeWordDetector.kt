package com.arise.assistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.arise.assistant.log.LocalLog

/**
 * Lightweight wake-word listener.
 *
 * Design: we do NOT run a heavyweight on-device model continuously (low-end
 * phones, battery). A cheap VAD (adaptive noise floor + RMS energy) runs on an
 * AudioRecord; when a real speech burst starts, the microphone is handed to the
 * speech recognizer whose live partial results are matched against the wake word
 * (default "arise"). A match wakes Arise; a miss returns to sleep with a cooldown.
 *
 * The recognizer interface point ([WakeMatcher]) means a commercial always-on
 * model (e.g. Porcupine) can be dropped in without touching the engine.
 */
class WakeWordDetector(
    private val ctx: Context,
    private val matcher: WakeMatcher,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {

    interface WakeMatcher {
        /** True when the engine is already awake — detection disabled. */
        fun isAwake(): Boolean
        /** Handle a potential speech utterance by listening for the wake word. */
        fun onSpeechCandidate()
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    /** Mic sample rate used across the app. */
    val sampleRate: Int = 16000

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "arise-wake").apply {
            isDaemon = true
            priority = Process.THREAD_PRIORITY_URGENT_AUDIO.coerceAtLeast(Process.THREAD_PRIORITY_AUDIO)
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        LocalLog.i("Wake", "listening thread up (rate=$sampleRate)")
        var record: AudioRecord? = null
        var cooldownUntil = 0L
        var floorDb = -45f // adaptive noise floor
        val buf = ShortArray(sampleRate / 20) // 50 ms
        var speechFrames = 0
        var inUtterance = false
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buf.size * 2 * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                LocalLog.e("Wake", "AudioRecord init failed — mic busy or permission missing")
                handler.post { matcher.onSpeechCandidate() } // let engine surface the error
                return
            }
            record.startRecording()
            while (running) {
                if (matcher.isAwake()) {
                    Thread.sleep(120)
                    continue
                }
                if (System.currentTimeMillis() < cooldownUntil) {
                    Thread.sleep(40)
                    continue
                }
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                val floats = AudioAnalyzer.toFloats(ShortArray(n) { buf[it] })
                val db = AudioAnalyzer.rmsDb(floats)
                // adaptive floor: slow attack toward speech, fast decay toward silence
                if (db > floorDb + 6) floorDb = (floorDb * 0.995f + (db - 5f) * 0.005f)
                else if (db < floorDb - 2f) floorDb = (floorDb * 0.99f + db * 0.01f)
                floorDb = floorDb.coerceIn(-75f, -20f)

                val speaking = db > (floorDb + 9f).coerceAtMost(-24f) && db > -38f
                if (speaking) {
                    speechFrames++
                    if (!inUtterance && speechFrames >= 3) {
                        inUtterance = true
                        // hand the mic to the speech recognizer to hear the wake word,
                        // then end this thread — the engine spins up a fresh listener
                        record.stop()
                        handler.post { matcher.onSpeechCandidate() }
                        break
                    }
                } else {
                    if (inUtterance && speechFrames > 4) {
                        // long utterance with no candidate; resume gating
                        inUtterance = false
                        cooldownUntil = System.currentTimeMillis() + 900
                        try { record.startRecording() } catch (_: Exception) {}
                    }
                    speechFrames = 0
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            LocalLog.e("Wake", "loop died: ${e.message}")
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            try { record?.release() } catch (_: Exception) {}
            LocalLog.i("Wake", "listener stopped")
        }
    }
}
