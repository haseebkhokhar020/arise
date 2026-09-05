package com.arise.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.arise.assistant.engine.ArisePhase
import com.arise.assistant.log.LocalLog
import com.arise.assistant.settings.Settings
import java.util.Locale
import java.util.UUID

/**
 * Thin, dependable TTS wrapper. Exposes readiness + utterance completion so the
 * engine can transition the logo to SPEAKING and back, and chain "ask a follow-up".
 *
 * Hardened: never throws from speak/stop/shutdown; tolerates engines that expose
 * no voices or fail mid-init; reports readiness only after a successful init.
 */
class SpeechManager(private val ctx: Context, private val settings: Settings) {

    interface Callback {
        fun onTtsReady()
        fun onPhaseChange(phase: ArisePhase)
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    var callback: Callback? = null
        set(v) { field = v; if (v != null && ready) runCatching { v.onTtsReady() } }

    @Volatile private var speaking = false

    val isReady: Boolean get() = ready

    init {
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech
                    try {
                        engine.language = Locale.getDefault()
                        engine.setSpeechRate(settings.ttsRate)
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                speaking = true
                                runCatching { callback?.onPhaseChange(ArisePhase.SPEAKING) }
                            }
                            override fun onDone(utteranceId: String?) {
                                speaking = false
                                runCatching { callback?.onPhaseChange(ArisePhase.IDLE) }
                            }
                            override fun onError(utteranceId: String?) {
                                speaking = false
                                runCatching { callback?.onPhaseChange(ArisePhase.IDLE) }
                            }
                        })
                        val voiceName = settings.ttsVoice
                        if (voiceName.isNotEmpty()) {
                            val voices = engine.voices // may be null on some engines
                            if (voices != null) {
                                for (v in voices) if (v.name == voiceName) { engine.voice = v; break }
                            }
                        }
                        ready = true
                        LocalLog.i("TTS", "ready")
                        runCatching { callback?.onTtsReady() }
                    } catch (t: Throwable) {
                        LocalLog.e("TTS", "init error: ${t.message}")
                    }
                } else {
                    LocalLog.e("TTS", "engine init status $status")
                }
            }
        } catch (t: Throwable) {
            LocalLog.e("TTS", "construct failed: ${t.message}")
        }
    }

    fun speak(text: String) {
        if (!ready || !settings.ttsEnabled || text.isBlank()) return
        val t = tts ?: return
        runCatching {
            val id = UUID.randomUUID().toString()
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }.onFailure { LocalLog.e("TTS", "speak failed: ${it.message}") }
    }

    /** Speak only if nothing else is currently being spoken. */
    fun speakIfFree(text: String) { if (!speaking) speak(text) }

    fun stop() {
        runCatching { tts?.stop() }
        speaking = false
    }

    fun isSpeaking(): Boolean = speaking

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
