package com.arise.assistant.speaker

import android.content.Context
import com.arise.assistant.audio.AudioAnalyzer
import com.arise.assistant.engine.AudioFrame
import com.arise.assistant.log.LocalLog
import com.arise.assistant.settings.Settings
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * OPTIONAL local speaker "verification" — an enrollment + matching convenience layer.
 *
 * IMPORTANT honest boundary: it uses lightweight acoustic features (energy,
 * zero-crossing, spectral proxy) to tell "is this plausibly the enrolled voice?"
 * It is a privacy-preserving convenience gate, NOT a secure biometric — that is
 * stated in the UI and is by design (a wake-word microphone cannot guarantee
 * identity). Recordings never leave the device and can be wiped with one control.
 */
class SpeakerVerifier(private val ctx: Context, private val settings: Settings) {

    data class Result(val score: Float, val accepted: Boolean, val reason: String)

    private data class Profile(val id: Int, val energy: Float, val zcr: Float, val proxy: Float, val samples: Int)

    private fun file() = ctx.getFileStreamPath("speaker_profile.json")

    fun isEnrolled(): Boolean = settings.speakerEnrollmentId >= 0 && file().exists()

    fun deleteProfile() {
        file().delete()
        settings.speakerEnrollmentId = -1
        LocalLog.i("Speaker", "profile deleted")
    }

    /** Enroll from ~N speech frames. Returns new enrollment id or -1 on failure. */
    fun enroll(frames: List<AudioFrame>): Int {
        if (frames.size < 3) { LocalLog.w("Speaker", "enroll needs ≥3 seconds of voice"); return -1 }
        val profile = Profile(
            id = (System.currentTimeMillis() % 1_000_000).toInt(),
            energy = mean(frames) { it.energy.toFloat() },
            zcr = mean(frames) { AudioAnalyzer.zeroCrossingRate(it.samples) },
            proxy = mean(frames) { it.spectralFlux.toFloat() },
            samples = frames.size
        )
        try {
            ctx.openFileOutput("speaker_profile.json", Context.MODE_PRIVATE).bufferedWriter().use { w ->
                w.write(JSONObject().apply {
                    put("id", profile.id)
                    put("energy", profile.energy.toDouble())
                    put("zcr", profile.zcr.toDouble())
                    put("proxy", profile.proxy.toDouble())
                    put("samples", profile.samples)
                }.toString())
            }
            settings.speakerEnrollmentId = profile.id
            LocalLog.i("Speaker", "enrolled id=${profile.id} frames=${profile.samples}")
            return profile.id
        } catch (e: Exception) {
            LocalLog.e("Speaker", "enroll failed ${e.message}")
            return -1
        }
    }

    /** Score + decide on live audio. Threshold scales with sensitivity setting. */
    fun verify(frames: List<AudioFrame>): Result {
        if (frames.size < 2) return Result(0f, false, "too little audio")
        if (!isEnrolled()) return Result(0f, false, "no_enrollment")
        val stored = readProfile() ?: return Result(0f, false, "no_enrollment")
        val energy = mean(frames) { it.energy.toFloat() }
        val zcr = mean(frames) { AudioAnalyzer.zeroCrossingRate(it.samples) }
        val proxy = mean(frames) { it.spectralFlux.toFloat() }

        // normalized closeness of the three features
        val de = closeness(energy, stored.energy)
        val dz = closeness(zcr, stored.zcr)
        val dp = closeness(proxy, stored.proxy)
        val score = (de * 0.5f + dz * 0.2f + dp * 0.3f).coerceIn(0f, 1f)

        val threshold = 1f - settings.speakerSensitivity.coerceIn(0.2f, 0.95f) // sens high → stricter
        val accepted = score >= threshold
        LocalLog.i("Speaker", "verify score=%.2f threshold=%.2f accepted=%s".format(score, threshold, accepted))
        return Result(score, accepted, if (accepted) "match" else "mismatch")
    }

    private fun readProfile(): Profile? {
        if (!file().exists()) return null
        return try {
            val o = JSONObject(ctx.openFileInput("speaker_profile.json").bufferedReader().use { it.readText() })
            Profile(o.getInt("id"), o.getDouble("energy").toFloat(), o.getDouble("zcr").toFloat(),
                o.getDouble("proxy").toFloat(), o.optInt("samples", 0))
        } catch (e: Exception) { null }
    }

    private inline fun mean(frames: List<AudioFrame>, sel: (AudioFrame) -> Float): Float {
        if (frames.isEmpty()) return 0f
        var s = 0.0; for (fr in frames) s += sel(fr); return (s / frames.size).toFloat()
    }

    private fun closeness(a: Float, b: Float): Float = if (abs(b) < 1e-6) 1f else (1f - abs(a - b) / b).coerceIn(0f, 1f)
}
