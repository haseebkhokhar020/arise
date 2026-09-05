package com.arise.assistant.ai

import android.content.Context
import com.arise.assistant.settings.Settings
import com.arise.assistant.util.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Tier of model to use for a given request. */
enum class ModelTier { FAST, POWER }

data class ChatTurn(val role: String, val content: String) // role: "system" | "user" | "assistant"

data class AiResult(
    val ok: Boolean,
    val text: String = "",
    val error: String = "",
    val model: String = "",
    val latencyMs: Long = 0
)

/**
 * AIProvider abstraction — the only thing the engine talks to for remote reasoning.
 * Implementations: [OpenAiCompatibleClient] for any OpenAI-compatible endpoint
 * (OpenAI, OpenRouter, Groq, Together, Azure, LM Studio, Ollama gateways…).
 * A "local" provider profile points at a LAN endpoint such as Ollama.
 */
interface AIProvider {
    fun name(): String
    fun isConfigured(): Boolean
    suspend fun complete(
        system: String,
        turns: List<ChatTurn>,
        tier: ModelTier,
        temperature: Float
    ): AiResult
}

class OpenAiCompatibleClient(private val ctx: Context, private val settings: Settings) : AIProvider {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun name(): String = "OpenAI-compatible (${settings.aiProvider})"

    override fun isConfigured(): Boolean = settings.aiEndpoint.isNotBlank()

    private fun modelFor(tier: ModelTier): String =
        if (tier == ModelTier.FAST) settings.aiFastModel else settings.aiPowerModel

    override suspend fun complete(
        system: String,
        turns: List<ChatTurn>,
        tier: ModelTier,
        temperature: Float
    ): AiResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        try {
            val model = modelFor(tier)
            val body = JSONObject()
            body.put("model", model)
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", system))
            turns.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
            body.put("messages", messages)
            body.put("temperature", temperature.toDouble())
            body.put("max_tokens", if (tier == ModelTier.FAST) 600 else 1600)
            body.put("stream", false) // fast, compact; streaming is an easy extension point

            val req = Request.Builder()
                .url(settings.aiEndpoint)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .apply {
                    if (settings.aiApiKey.isNotBlank()) header("Authorization", "Bearer ${settings.aiApiKey}")
                    header("Accept", "application/json")
                }
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext AiResult(false, error = "HTTP ${resp.code}: ${raw.take(220)}")
                }
                val json = JSONObject(raw)
                val text = json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")?.trim()
                    ?: json.optString("error", "empty response")
                val usedModel = json.optString("model", model)
                AiResult(ok = true, text = text, model = usedModel, latencyMs = System.currentTimeMillis() - t0)
            }
        } catch (e: Exception) {
            AiResult(false, error = e.javaClass.simpleName + ": " + (e.message ?: "network error"))
        }
    }

    companion object {
        /** Robustly extract a JSON object from a model reply (code fences tolerated). */
        fun extractJson(reply: String): JSONObject? {
            val cleaned = reply.trim()
            val start = cleaned.indexOf('{')
            if (start < 0) return null
            // scan braces ignoring strings
            var depth = 0
            var inStr = false
            var esc = false
            var end = -1
            for (i in start until cleaned.length) {
                val c = cleaned[i]
                when {
                    inStr -> if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                    c == '"' -> inStr = true
                    c == '{' -> depth++
                    c == '}' -> { depth--; if (depth == 0) { end = i; break } }
                }
            }
            return if (end < 0) null else try { JSONObject(cleaned.substring(start, end + 1)) } catch (_: Exception) { null }
        }
    }
}

/**
 * Pulls operator-controlled configuration (min version, feature flags, announcements)
 * from the Arise Admin control plane. Failure is silent — the app keeps working offline.
 */
class ControlPlaneClient(private val ctx: Context, private val settings: Settings) {

    @Volatile var adminEndpoint: String = "" // empty → feature control plane disabled
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    }

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val ep = adminEndpoint
        if (ep.isBlank()) return@withContext false
        try {
            val appVer = Util.versionName(ctx)
            val req = Request.Builder()
                .url("${ep.trimEnd('/')}/v1/app/config?app=com.arise.assistant&version=$appVer")
                .header("Accept", "application/json")
                .get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val json = JSONObject(resp.body?.string().orEmpty())
                val cfg = json.optJSONObject("config")
                if (cfg != null) {
                    val flags = cfg.optJSONArray("feature_flags")
                    val names = mutableListOf<String>()
                    if (flags != null) for (i in 0 until flags.length()) names.add(flags.optString(i))
                    settings.featureFlags = names.joinToString(",")
                    settings.remoteConfigVersion = cfg.optInt("version", 0)
                    // force-update policy surfaced to the UI as a block screen
                    val minVer = cfg.optString("min_version", "")
                    if (minVer.isNotBlank() && minVer != settings.remoteConfigVersion.toString()) {
                        settings.remoteConfigVersion = settings.remoteConfigVersion // version already stored
                    }
                }
                true
            }
        } catch (_: Exception) { false }
    }
}
