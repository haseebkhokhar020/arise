package com.arise.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.arise.assistant.log.LocalLog

/**
 * Single wrapper around Android SpeechRecognizer.
 *
 * The recognizer owns the microphone while active, so the wake-word AudioRecord
 * pipeline hands the mic over right before this starts and only restarts after
 * [destroy]. Low battery by design: we only run speech recognition when a real
 * utterance is already in progress.
 */
class SpeechCapture(private val ctx: Context) {

    interface Callback {
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onRmsDb(db: Float)
        fun onError(code: Int, message: String)
        fun onTimeout()
    }

    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile var isActive: Boolean = false
        private set

    fun start(endOfSpeechMs: Int, partials: Boolean = true, callback: Callback) {
        stop(false)
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx.applicationContext)
        recognizer = sr
        isActive = true
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partials)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, endOfSpeechMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, (endOfSpeechMs * 0.6).toInt())
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { callback.onRmsDb(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
                    SpeechRecognizer.ERROR_NO_MATCH -> "couldn’t understand"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> "recognition service unavailable"
                    SpeechRecognizer.ERROR_AUDIO -> "audio error"
                    else -> "error $error"
                }
                LocalLog.i("STT", "error $error: $msg")
                callback.onError(error, msg)
            }
            override fun onResults(results: Bundle?) {
                isActive = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: ""
                if (text.isNotEmpty()) callback.onResult(text) else callback.onError(SpeechRecognizer.ERROR_NO_MATCH, "no text")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: return
                if (text.isNotEmpty()) callback.onPartial(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(i)
        LocalLog.i("STT", "started")
    }

    fun stop(grace: Boolean = true) {
        val sr = recognizer ?: return
        recognizer = null
        isActive = false
        try {
            if (grace) sr.stopListening()
        } catch (_: Exception) {}
        try { sr.destroy() } catch (_: Exception) {}
    }

    fun destroy() {
        stop(false)
        LocalLog.i("STT", "destroyed")
    }
}
