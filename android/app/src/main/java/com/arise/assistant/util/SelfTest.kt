package com.arise.assistant.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.tts.TextToSpeech
import com.arise.assistant.settings.Settings
import com.arise.assistant.speech.SpeechCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** One line of the in-app health report. */
data class CheckResult(val name: String, val ok: Boolean, val detail: String)

/**
 * Runs quick on-device checks that explain — in plain language — why a feature
 * (voice, hands-free, cloud AI, universal control) may not work on a given phone.
 * Shown in Settings → Diagnostics and mirrored to the local log.
 */
object SelfTest {

    suspend fun run(ctx: Context, settings: Settings): List<CheckResult> = withContext(Dispatchers.IO) {
        val out = mutableListOf<CheckResult>()

        val mic = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        out += CheckResult(
            "Microphone permission", mic,
            if (mic) "granted" else "NOT granted — open Arise Settings → Permissions → Microphone and allow it"
        )

        val stt = SpeechCapture.isAvailable(ctx)
        out += CheckResult(
            "Google speech service", stt,
            if (stt) "available"
            else "NOT available — install/update the Google app (Google speech services), or enable it in Android settings, then fully close & reopen Arise"
        )

        val tts = ttsEnginePresent(ctx)
        out += CheckResult(
            "Text-to-speech engine", tts,
            if (tts) "present" else "no TTS engine found — install Google Text-to-Speech from Play, or set a TTS voice in Android settings"
        )

        val net = ping()
        out += CheckResult(
            "Internet connection", net,
            if (net) "reachable" else "unreachable — voice recognition and cloud AI need internet"
        )

        val aiCfg = settings.aiEndpoint.isNotBlank() && settings.aiApiKey.isNotBlank()
        out += CheckResult(
            "Cloud AI set up", aiCfg,
            if (aiCfg) settings.aiProviderLabel() else "pick a provider and paste its API key in Settings → Cloud AI"
        )

        val notif = Build.VERSION.SDK_INT < 33 ||
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        out += CheckResult(
            "Notification permission", notif,
            if (notif) "granted" else "not granted — the hands-free mic notification needs it on Android 13+"
        )

        val a11y = accessibilityEnabled(ctx)
        out += CheckResult(
            "Accessibility service", a11y,
            if (a11y) "enabled"
            else "off — optional; only needed for universal control (tapping/typing inside other apps)"
        )

        out
    }

    private fun ttsEnginePresent(ctx: Context): Boolean = try {
        val pm = ctx.packageManager
        val i = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
        pm.queryIntentActivities(i, 0).isNotEmpty() ||
                pm.queryIntentServices(Intent().setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA), 0).isNotEmpty()
    } catch (_: Throwable) { false }

    private fun ping(): Boolean = try {
        val c = URL("https://example.com").openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.requestMethod = "HEAD"
        try { val code = c.responseCode; code in 200..399 } finally { c.disconnect() }
    } catch (_: Throwable) { false }

    private fun accessibilityEnabled(ctx: Context): Boolean = try {
        val expected = "${ctx.packageName}/${ctx.packageName}.access.AriseAccessibilityService"
        val enabled = android.provider.Settings.Secure.getString(
            ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    } catch (_: Throwable) { false }
}
