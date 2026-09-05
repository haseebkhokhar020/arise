package com.arise.assistant.speech

import android.content.Context
// (voice selection helper lives here — TextToSpeech references remain clean)

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.arise.assistant.engine.ArisePhase
import com.arise.assistant.settings.Settings
import java.util.Locale
import java.util.UUID

/**
 * Thin, dependable TTS wrapper. Exposes readiness + utterance completion so the
 * engine can transition the logo to SPEAKING and back, and chain "ask a follow-up".
 */
class SpeechManager(private val ctx: Context, private val settings: Settings) {

    interface Callback {
        fun onTtsReady()
        fun onPhaseChange(phase: ArisePhase)
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    var callback: Callback? = null
        set(v) { field = v; if (v != null && ready) v.onTtsReady() }

    @Volatile private var speaking = false

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine != null) {
                    engine.language = Locale.getDefault()
                    engine.setSpeechRate(settings.ttsRate)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            speaking = true
                            callback?.onPhaseChange(ArisePhase.SPEAKING)
                        }
                        override fun onDone(utteranceId: String?) {
                            speaking = false
                            callback?.onPhaseChange(ArisePhase.IDLE)
                        }
                        override fun onError(utteranceId: String?) {
                            speaking = false
                            callback?.onPhaseChange(ArisePhase.IDLE)
                        }
                    })
                    val voiceName = settings.ttsVoice
                    if (voiceName.isNotEmpty()) {
                        for (v in engine.voices) if (v.name == voiceName) { engine.voice = v; break }
                    }
                    ready = true
                    callback?.onTtsReady()
                }
            }
        }
    }

    fun speak(text: String) {
        val t = tts
        if (!ready || t == null || !settings.ttsEnabled) return
        if (text.isBlank()) return
        val id = UUID.randomUUID().toString()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Speak only if nothing else is currently being spoken. */
    fun speakIfFree(text: String) { if (!speaking) speak(text) }

    fun stop() {
        tts?.stop()
        speaking = false
    }

    fun isSpeaking(): Boolean = speaking

    fun shutdown() { stop(); tts?.shutdown(); tts = null; ready = false }
}
