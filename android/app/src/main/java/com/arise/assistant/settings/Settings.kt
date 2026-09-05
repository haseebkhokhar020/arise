package com.arise.assistant.settings

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * Central user/preference store. All values are read through cached [SharedPreferences]
 * so the UI, service and engine agree on one source of truth.
 */
class Settings(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("arise_settings", Context.MODE_PRIVATE)

    // ---- Wake word -----------------------------------------------------
    var wakeWord: String
        get() = sp.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD)!!.trim()
        set(v) = sp.edit().putString(KEY_WAKE_WORD, v.trim()).apply()

    var wakeWordEnabled: Boolean
        get() = sp.getBoolean(KEY_WAKE_WORD_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_WAKE_WORD_ENABLED, v).apply()

    var handsFreeEnabled: Boolean // keep listening while app in background
        get() = sp.getBoolean(KEY_HANDS_FREE, false)
        set(v) = sp.edit().putBoolean(KEY_HANDS_FREE, v).apply()

    /** Start the wake-word service after device reboot (user opt-in). */
    var autoStartOnBoot: Boolean
        get() = sp.getBoolean(KEY_AUTO_START, false)
        set(v) = sp.edit().putBoolean(KEY_AUTO_START, v).apply()

    /** Visual theme key: violet | ocean | sunset | mono. */
    var theme: String
        get() = sp.getString(KEY_THEME, "violet")!!
        set(v) = sp.edit().putString(KEY_THEME, v).apply()

    // ---- Listening / conversation --------------------------------------
    /** Seconds of silence after the wake word before the command is considered ended. */
    var endOfSpeechMs: Int
        get() = sp.getInt(KEY_END_OF_SPEECH_MS, 900)
        set(v) = sp.edit().putInt(KEY_END_OF_SPEECH_MS, v).apply()

    /** Seconds the assistant keeps accepting follow-ups before returning to sleep. 0 = disabled. */
    var followUpTimeoutSec: Int
        get() = sp.getInt(KEY_FOLLOW_UP_SEC, 12)
        set(v) = sp.edit().putInt(KEY_FOLLOW_UP_SEC, v).apply()

    var briefModeEnabled: Boolean  // "Yes?"-style instant ack vs waiting for full transcription
        get() = sp.getBoolean(KEY_BRIEF_MODE, true)
        set(v) = sp.edit().putBoolean(KEY_BRIEF_MODE, v).apply()

    // ---- Speaker verification (removed feature — kept field for clean rollback) ----
    // Always off: no "specific voice" enrollment is ever required to use Arise.
    var speakerVerifyEnabled: Boolean
        get() = false
        set(v) { sp.edit().putBoolean(KEY_SPEAKER_VERIFY, false).apply() }

    var speakerSensitivity: Float  // 0..1 decision threshold proximity
        get() = sp.getFloat(KEY_SPEAKER_SENSITIVITY, 0.55f)
        set(v) = sp.edit().putFloat(KEY_SPEAKER_SENSITIVITY, v.coerceIn(0f, 1f)).apply()

    /** Monotonic enrollment id; -1 means no valid enrollment. */
    var speakerEnrollmentId: Int
        get() = sp.getInt(KEY_SPEAKER_ENROLL_ID, -1)
        set(v) = sp.edit().putInt(KEY_SPEAKER_ENROLL_ID, v).apply()

    // ---- Voice / speech -------------------------------------------------
    /** Selected TTS voice name (empty = system default). */
    var ttsVoice: String
        get() = sp.getString(KEY_TTS_VOICE, "")!!
        set(v) = sp.edit().putString(KEY_TTS_VOICE, v).apply()

    var ttsRate: Float
        get() = sp.getFloat(KEY_TTS_RATE, 1.0f)
        set(v) = sp.edit().putFloat(KEY_TTS_RATE, v.coerceIn(0.5f, 2f)).apply()

    var ttsEnabled: Boolean
        get() = sp.getBoolean(KEY_TTS_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_TTS_ENABLED, v).apply()

    var ttsSayWakeReply: Boolean
        get() = sp.getBoolean(KEY_TTS_SAY_YES, true)
        set(v) = sp.edit().putBoolean(KEY_TTS_SAY_YES, v).apply()

    // ---- AI provider ----------------------------------------------------
    var aiProvider: String  // "openai" | "custom" | "local"
        get() = sp.getString(KEY_AI_PROVIDER, "openai")!!
        set(v) = sp.edit().putString(KEY_AI_PROVIDER, v).apply()

    var aiEndpoint: String
        get() {
            // A preset-backed provider always uses the preset's (current) endpoint.
            val p = AiPresets.get(aiProvider)
            return p?.endpoint ?: sp.getString(KEY_AI_ENDPOINT, "https://api.openai.com/v1/chat/completions")!!
        }
        set(v) = sp.edit().putString(KEY_AI_ENDPOINT, v.trim()).apply()

    var aiApiKey: String
        get() = sp.getString(KEY_AI_KEY, "")!!
        set(v) = sp.edit().putString(KEY_AI_KEY, v.trim()).apply()

    var aiFastModel: String
        get() {
            val p = AiPresets.get(aiProvider)
            return p?.fast ?: sp.getString(KEY_AI_FAST_MODEL, "gpt-4o-mini")!!
        }
        set(v) = sp.edit().putString(KEY_AI_FAST_MODEL, v.trim()).apply()

    var aiPowerModel: String
        get() {
            val p = AiPresets.get(aiProvider)
            return p?.power ?: sp.getString(KEY_AI_POWER_MODEL, "gpt-4o")!!
        }
        set(v) = sp.edit().putString(KEY_AI_POWER_MODEL, v.trim()).apply()

    var aiTemperature: Float
        get() = sp.getFloat(KEY_AI_TEMP, 0.2f)
        set(v) = sp.edit().putFloat(KEY_AI_TEMP, v.coerceIn(0f, 1f)).apply()

    var aiRoutingEnabled: Boolean  // allow fast model for simple commands
        get() = sp.getBoolean(KEY_AI_ROUTING, true)
        set(v) = sp.edit().putBoolean(KEY_AI_ROUTING, v).apply()

    // ---- AI presets (3 built-in providers; only the API key is user-entered) ----
    /** Applies a preset's endpoint + fast/power models and sets [aiProvider] to its id. */
    fun applyAiPreset(presetId: String) {
        val p = AiPresets.get(presetId) ?: return
        aiProvider = p.id
        aiEndpoint = p.endpoint
        aiFastModel = p.fast
        aiPowerModel = p.power
    }

    /** Human label for the current provider (falls back to the endpoint host). */
    fun aiProviderLabel(): String = AiPresets.get(aiProvider)?.label ?: "Custom endpoint"

    // ---- Logo / animation ------------------------------------------------
    var logoAnimationEnabled: Boolean
        get() = sp.getBoolean(KEY_LOGO_ANIM, true)
        set(v) = sp.edit().putBoolean(KEY_LOGO_ANIM, v).apply()

    var logoQuality: String  // low | medium | high
        get() = sp.getString(KEY_LOGO_QUALITY, "high")!!
        set(v) = sp.edit().putString(KEY_LOGO_QUALITY, v).apply()

    var logoVoiceReactive: Boolean
        get() = sp.getBoolean(KEY_LOGO_VOICE, true)
        set(v) = sp.edit().putBoolean(KEY_LOGO_VOICE, v).apply()

    var logoReducedMotion: Boolean
        get() = sp.getBoolean(KEY_LOGO_REDUCED, false)
        set(v) = sp.edit().putBoolean(KEY_LOGO_REDUCED, v).apply()

    var logoIdleIntensity: Float
        get() = sp.getFloat(KEY_LOGO_IDLE, 0.35f)
        set(v) = sp.edit().putFloat(KEY_LOGO_IDLE, v.coerceIn(0f, 1f)).apply()

    var batterySaverAutoFx: Boolean
        get() = sp.getBoolean(KEY_BATTERY_AUTO_FX, true)
        set(v) = sp.edit().putBoolean(KEY_BATTERY_AUTO_FX, v).apply()

    // ---- Behavior ---------------------------------------------------------
    var confirmationPolicy: String // "always" | "sensitive_only" | "never"
        get() = sp.getString(KEY_CONFIRM_POLICY, "sensitive_only")!!
        set(v) = sp.edit().putString(KEY_CONFIRM_POLICY, v).apply()

    var confirmationTimeoutSec: Int
        get() = sp.getInt(KEY_CONFIRM_TIMEOUT_SEC, 12)
        set(v) = sp.edit().putInt(KEY_CONFIRM_TIMEOUT_SEC, v).apply()

    var errorSpeechEnabled: Boolean
        get() = sp.getBoolean(KEY_ERROR_SPEECH, true)
        set(v) = sp.edit().putBoolean(KEY_ERROR_SPEECH, v).apply()

    var visualAgentEnabled: Boolean  // Accessibility-based universal control
        get() = sp.getBoolean(KEY_VISUAL_AGENT, true)
        set(v) = sp.edit().putBoolean(KEY_VISUAL_AGENT, v).apply()

    var allowExternalActions: Boolean // open apps, launch urls, play media…
        get() = sp.getBoolean(KEY_EXT_ACTIONS, true)
        set(v) = sp.edit().putBoolean(KEY_EXT_ACTIONS, v).apply()

    var allowMessagingActions: Boolean // WhatsApp / SMS / calls
        get() = sp.getBoolean(KEY_MSG_ACTIONS, true)
        set(v) = sp.edit().putBoolean(KEY_MSG_ACTIONS, v).apply()

    var micChimeEnabled: Boolean
        get() = sp.getBoolean(KEY_MIC_CHIME, true)
        set(v) = sp.edit().putBoolean(KEY_MIC_CHIME, v).apply()

    // ---- Debug ------------------------------------------------------------
    var debugEnabled: Boolean
        get() = sp.getBoolean(KEY_DEBUG, false)
        set(v) = sp.edit().putBoolean(KEY_DEBUG, v).apply()

    var latencyLogging: Boolean
        get() = sp.getBoolean(KEY_LATENCY_LOG, true)
        set(v) = sp.edit().putBoolean(KEY_LATENCY_LOG, v).apply()

    /** Anonymous diagnostic consent — never contains personal content. */
    var telemetryOptIn: Boolean
        get() = sp.getBoolean(KEY_TELEMETRY, false)
        set(v) = sp.edit().putBoolean(KEY_TELEMETRY, v).apply()

    var localLogsEnabled: Boolean
        get() = sp.getBoolean(KEY_LOCAL_LOGS, true)
        set(v) = sp.edit().putBoolean(KEY_LOCAL_LOGS, v).apply()

    /** Last applied remote feature-flag version (from Admin backend). */
    var remoteConfigVersion: Int
        get() = sp.getInt(KEY_REMOTE_CFG, 0)
        set(v) = sp.edit().putInt(KEY_REMOTE_CFG, v).apply()

    /** Comma-separated feature flags pulled from the control plane. */
    var featureFlags: String
        get() = sp.getString(KEY_FEATURE_FLAGS, "")!!
        set(v) = sp.edit().putString(KEY_FEATURE_FLAGS, v).apply()

    /** Has the one-time privacy briefing been shown? */
    var privacyBriefingDone: Boolean
        get() = sp.getBoolean(KEY_PRIV_BRIEF, false)
        set(v) = sp.edit().putBoolean(KEY_PRIV_BRIEF, v).apply()

    var lastUptimeLogMs: Long = 0L // in-memory cooldown for periodic state saves

    fun flag(name: String): Boolean {
        val raw = featureFlags.lowercase(Locale.ROOT)
        if (name.lowercase(Locale.ROOT) in raw.split(',').map { it.trim() }) return true
        return when (name) {
            "cloud_ai", "cloud_ai_fast" -> aiRoutingEnabled && aiProvider != "local"
            "billing" -> true
            else -> false
        }
    }

    fun wipe() {
        sp.edit().clear().apply()
    }

    companion object {
        const val DEFAULT_WAKE_WORD = "arise"
        const val KEY_WAKE_WORD = "wake_word"
        const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        const val KEY_HANDS_FREE = "hands_free"
        const val KEY_AUTO_START = "auto_start_boot"
        const val KEY_THEME = "theme"
        const val KEY_END_OF_SPEECH_MS = "eos_ms"
        const val KEY_FOLLOW_UP_SEC = "followup_sec"
        const val KEY_BRIEF_MODE = "brief_mode"
        const val KEY_SPEAKER_VERIFY = "speaker_verify"
        const val KEY_SPEAKER_SENSITIVITY = "speaker_sens"
        const val KEY_SPEAKER_ENROLL_ID = "speaker_enroll_id"
        const val KEY_TTS_VOICE = "tts_voice"
        const val KEY_TTS_RATE = "tts_rate"
        const val KEY_TTS_ENABLED = "tts_enabled"
        const val KEY_TTS_SAY_YES = "tts_say_yes"
        const val KEY_AI_PROVIDER = "ai_provider"
        const val KEY_AI_ENDPOINT = "ai_endpoint"
        const val KEY_AI_KEY = "ai_key"
        const val KEY_AI_FAST_MODEL = "ai_fast_model"
        const val KEY_AI_POWER_MODEL = "ai_power_model"
        const val KEY_AI_TEMP = "ai_temp"
        const val KEY_AI_ROUTING = "ai_routing"
        const val KEY_LOGO_ANIM = "logo_anim"
        const val KEY_LOGO_QUALITY = "logo_quality"
        const val KEY_LOGO_VOICE = "logo_voice"
        const val KEY_LOGO_REDUCED = "logo_reduced"
        const val KEY_LOGO_IDLE = "logo_idle"
        const val KEY_BATTERY_AUTO_FX = "battery_auto_fx"
        const val KEY_CONFIRM_POLICY = "confirm_policy"
        const val KEY_CONFIRM_TIMEOUT_SEC = "confirm_timeout_sec"
        const val KEY_ERROR_SPEECH = "error_speech"
        const val KEY_VISUAL_AGENT = "visual_agent"
        const val KEY_EXT_ACTIONS = "ext_actions"
        const val KEY_MSG_ACTIONS = "msg_actions"
        const val KEY_MIC_CHIME = "mic_chime"
        const val KEY_DEBUG = "debug"
        const val KEY_LATENCY_LOG = "latency_log"
        const val KEY_TELEMETRY = "telemetry"
        const val KEY_LOCAL_LOGS = "local_logs"
        const val KEY_REMOTE_CFG = "remote_cfg"
        const val KEY_FEATURE_FLAGS = "feature_flags"
        const val KEY_PRIV_BRIEF = "priv_brief"
    }
}

/**
 * The three built-in cloud providers. Every preset is OpenAI-compatible, so the
 * same app code works against all of them — the user only ever enters an API key.
 */
object AiPresets {

    data class Preset(
        val id: String,
        val label: String,
        val tagline: String,
        val endpoint: String,
        val fast: String,
        val power: String
    )

    val all: List<Preset> = listOf(
        Preset(
            id = "gemini",
            label = "Google Gemini",
            tagline = "Free tier · no credit card · generous daily limit",
            endpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            fast = "gemini-3.6-flash",
            power = "gemini-3.6-flash"
        ),
        Preset(
            id = "openai",
            label = "OpenAI",
            tagline = "GPT-5 class · pay-as-you-go",
            endpoint = "https://api.openai.com/v1/chat/completions",
            fast = "gpt-5-nano",
            power = "gpt-5-pro"
        ),
        Preset(
            id = "groq",
            label = "Groq",
            tagline = "Ultra-fast open models · free tier",
            endpoint = "https://api.groq.com/openai/v1/chat/completions",
            fast = "llama-3.1-8b-instant",
            power = "openai/gpt-oss-120b"
        )
    )

    fun get(id: String): Preset? = all.firstOrNull { it.id == id }
}
