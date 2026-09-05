package com.arise.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.arise.assistant.log.LocalLog

/**
 * Hardened wrapper around Android SpeechRecognizer.
 *
 * Guarantees:
 *  - [start] never throws and reports a clean onError when the recognizer is
 *    missing, busy, or fails to start (prevents the app from being killed);
 *  - availability is checked up front ([isAvailable]);
 *  - late callbacks from a cancelled session are ignored via a generation token;
 *  - every callback delivery is wrapped so no exception can escape to the main
 *    thread from the system's binder thread.
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
    @Volatile private var generation = 0
    @Volatile var isActive: Boolean = false
        private set

    /** True when the system exposes a speech recognition service (Google app present). */
    val available: Boolean get() = isAvailable(ctx)

    companion object {
        fun isAvailable(ctx: Context): Boolean = try {
            SpeechRecognizer.isRecognitionAvailable(ctx.applicationContext)
        } catch (_: Throwable) { false }
    }

    /**
     * Start listening. Returns true when the recognizer was handed a start; when
     * the recognizer is unavailable or the start throws it returns false after a
     * best-effort [Callback.onError]. Never throws.
     */
    fun start(endOfSpeechMs: Int, partials: Boolean = true, callback: Callback): Boolean {
        stop(false)
        if (!isAvailable(ctx)) {
            LocalLog.e("STT", "SpeechRecognizer not available (Google speech service missing?)")
            runCatching { callback.onError(SpeechRecognizer.ERROR_CLIENT, "speech service not available on this device") }
            return false
        }
        val gen = generation + 1
        generation = gen

        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(ctx.applicationContext)
        } catch (t: Throwable) {
            LocalLog.e("STT", "createSpeechRecognizer failed: ${t.javaClass.simpleName}: ${t.message}")
            runCatching { callback.onError(SpeechRecognizer.ERROR_CLIENT, "could not start speech service") }
            return false
        }
        recognizer = sr
        isActive = true

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partials)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, endOfSpeechMs)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                (endOfSpeechMs * 0.6).toInt()
            )
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                if (gen != generation || rmsdB < 0f) return
                runCatching { callback.onRmsDb(rmsdB) }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (gen != generation) return
                isActive = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
                    SpeechRecognizer.ERROR_NO_MATCH -> "couldn’t understand"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_NETWORK -> "recognition service unavailable"
                    SpeechRecognizer.ERROR_AUDIO -> "audio error"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
                    SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "speech server error"
                    else -> "error $error"
                }
                LocalLog.i("STT", "error $error: $msg")
                runCatching { callback.onError(error, msg) }
            }
            override fun onResults(results: Bundle?) {
                if (gen != generation) return
                isActive = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: ""
                runCatching {
                    if (text.isNotEmpty()) callback.onResult(text)
                    else callback.onError(SpeechRecognizer.ERROR_NO_MATCH, "no text")
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (gen != generation) return
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: return
                if (text.isNotEmpty()) runCatching { callback.onPartial(text) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            sr.startListening(i)
            LocalLog.i("STT", "started (gen=$gen)")
            return true
        } catch (t: Throwable) {
            LocalLog.e("STT", "startListening threw: ${t.javaClass.simpleName}: ${t.message}")
            isActive = false
            if (recognizer === sr) recognizer = null
            runCatching { sr.destroy() }
            runCatching { callback.onError(SpeechRecognizer.ERROR_CLIENT, "could not start listening") }
            return false
        }
    }

    fun stop(grace: Boolean = true) {
        val sr = recognizer ?: return
        recognizer = null
        generation++            // invalidate any in-flight callbacks
        isActive = false
        runCatching { if (grace) sr.stopListening() }
        runCatching { sr.destroy() }
    }

    fun destroy() {
        stop(false)
        LocalLog.i("STT", "destroyed")
    }
}
