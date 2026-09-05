package com.arise.assistant.engine

import org.json.JSONArray
import org.json.JSONObject

/** Runtime phase the assistant (and logo) is in. */
enum class ArisePhase { IDLE, WAKING, VERIFYING, ACK, LISTENING, PROCESSING, SPEAKING, CONFIRMING, SUCCESS, ERROR }

enum class AgentState { SLEEPING, AWAITING_COMMAND, BUSY, CONFIRMATION, CANCELLED, FAILED }

/** Structured command parsed from text — the contract between STT and execution. */
data class ParsedIntent(
    val intent: String,
    val confidence: Float = 1f,
    val params: Map<String, String> = emptyMap(),
    val rawText: String = "",
    val appHint: String? = null,
    val sensitive: Boolean = false,
    /** Free-form action plan produced by a remote model (power tools). */
    val modelPlan: String? = null,
    val needsModel: Boolean = false
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("intent", intent)
        o.put("confidence", confidence)
        o.put("raw_text", rawText)
        if (appHint != null) o.put("app_hint", appHint)
        o.put("sensitive", sensitive)
        val p = JSONObject()
        params.forEach { (k, v) -> p.put(k, v) }
        o.put("params", p)
        if (modelPlan != null) o.put("model_plan", modelPlan)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): ParsedIntent {
            val p = mutableMapOf<String, String>()
            o.optJSONObject("params")?.keys()?.forEach { k -> p[k] = o.optJSONObject("params").optString(k) }
            return ParsedIntent(
                intent = o.optString("intent", ""),
                confidence = o.optDouble("confidence", 1.0).toFloat(),
                params = p,
                rawText = o.optString("raw_text", ""),
                appHint = o.optString("app_hint", null),
                sensitive = o.optBoolean("sensitive", false),
                modelPlan = o.optString("model_plan", null),
                needsModel = o.optBoolean("needs_model", false)
            )
        }
    }
}

/** Result envelope — every executed step returns one of these, and never lies. */
enum class ToolStatus { SUCCESS, FAILED, BLOCKED, NO_PERMISSION, PARTIAL, VERIFICATION_PENDING, ABORTED, CANCELLED }

data class ToolResult(
    val tool: String,
    val status: ToolStatus,
    val detail: String = "",
    val verified: Boolean = false,      // action-level confirmation happened
    val output: String? = null,
    val data: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("tool", tool)
        o.put("status", status.name)
        o.put("detail", detail)
        o.put("verified", verified)
        if (output != null) o.put("output", output)
        val d = JSONObject(); data.forEach { (k, v) -> d.put(k, v) }
        o.put("data", d)
        return o
    }
}

/** Per-run timing breakdown, shown in the debug panel and logged locally. */
class LatencyReport {
    val events = linkedMapOf<String, Long>()
    fun mark(key: String) { if (!events.containsKey(key)) events.put(key, System.currentTimeMillis()) }
    fun elapsedMs(key: String): Long = (events[key] ?: 0L) - (events["start"] ?: events[key] ?: 0L)
    fun totalMs(): Long = if (events.isEmpty()) 0 else events.values.max() - events.values.min()
    fun add(from: LatencyReport) { from.events.forEach { (k, v) -> if (!events.containsKey(k)) events[k] = v } }
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("total_ms", totalMs())
        val ev = JSONObject()
        var prev: Long? = null
        fromStart()  // ensure ordering
        events.forEach { (k, v) ->
            val rel = prev?.let { v - it } ?: 0L
            ev.put(k, JSONObject().put("at_ms", v - (events["start"] ?: v)).put("seg_ms", rel))
            prev = v
        }
        o.put("events", ev)
        return o
    }
    private fun fromStart() = Unit
}

/** One visible conversation line. */
data class ChatItem(
    val kind: String,          // "user" | "assistant" | "system" | "debug"
    val text: String,
    val meta: String = "",
    val atMillis: Long = System.currentTimeMillis(),
    val toolResult: ToolResult? = null
)

/** Opaque log line written to the local ring buffer (never contains secrets). */
data class LogLine(val ts: Long, val tag: String, val msg: String)

/** What the Wake word layer heard + matched. */
data class WakeMatch(val word: String, val score: Float, val rawEnergyDb: Float)

/** Audio feature capture used by optional speaker verification. */
data class AudioFrame(
    val samples: FloatArray,
    val rmsDb: Float,
    val energy: Double,
    val spectralFlux: Double
) {
    fun toBase64Array(): String = buildString {
        append('[')
        samples.forEachIndexed { i, s -> if (i > 0) append(','); append(String.format(java.util.Locale.ROOT, "%.5f", s)) }
        append(']')
    }
}
