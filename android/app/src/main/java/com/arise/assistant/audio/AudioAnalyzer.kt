package com.arise.assistant.audio

import com.arise.assistant.engine.AudioFrame
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Lightweight DSP used by wake-word gating and optional speaker verification.
 * Pure functions — no Android dependencies so it is unit-testable.
 */
object AudioAnalyzer {

    fun toFloats(shortArray: ShortArray): FloatArray =
        FloatArray(shortArray.size) { shortArray[it] / 32768f }

    /** RMS energy in decibels relative to full scale. */
    fun rmsDb(samples: FloatArray): Float {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        val rms = sqrt(sum / samples.size.coerceAtLeast(1))
        return if (rms <= 1e-9) -96f else (20 * ln(rms) / ln(10.0)).toFloat().coerceIn(-96f, 0f)
    }

    fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size.coerceAtLeast(1)).toFloat()
    }

    /** Zero-crossing rate per sample (0..1). Differentiates voiced/unvoiced. */
    fun zeroCrossingRate(samples: FloatArray): Float {
        if (samples.size < 2) return 0f
        var crosses = 0
        for (i in 1 until samples.size) {
            if ((samples[i - 1] < 0f && samples[i] >= 0f) || (samples[i - 1] >= 0f && samples[i] < 0f)) crosses++
        }
        return crosses.toFloat() / (samples.size - 1)
    }

    /**
     * Crude spectral centroid estimate via zero-crossing + energy proxy
     * (kept intentionally light — see note in [SpeakerVerifier]).
     */
    fun spectralProxy(samples: FloatArray): Float =
        zeroCrossingRate(samples) * 4000f + rms(samples) * 100f

    /** Build frame features from a raw chunk of float samples. */
    fun frame(samples: FloatArray): AudioFrame {
        val e = rms(samples).toDouble()
        return AudioFrame(
            samples = samples,
            rmsDb = rmsDb(samples),
            energy = e * e,
            spectralFlux = abs(spectralProxy(samples)).toDouble()
        )
    }
}
