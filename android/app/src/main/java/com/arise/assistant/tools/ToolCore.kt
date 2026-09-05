package com.arise.assistant.tools

import android.content.Context
import com.arise.assistant.access.AriseAccessibilityService
import com.arise.assistant.engine.ToolResult
import com.arise.assistant.engine.ToolStatus
import com.arise.assistant.settings.Settings
import com.arise.assistant.speaker.SpeakerVerifier

/**
 * Live execution context handed to every tool. Tools never talk to the engine
 * directly — they use this narrow surface (progress, confirmation, capabilities).
 */
class AgentContext(
    val ctx: Context,
    val settings: Settings,
    val verifier: SpeakerVerifier,
    val progress: (String) -> Unit,
    /** Suspends until the user answers yes/no (voice, buttons or timeout). */
    val confirm: suspend (question: String, timeoutSec: Int) -> Boolean,
    val announce: (String) -> Unit
) {
    val access: AriseAccessibilityService? get() = AriseAccessibilityService.instance
    fun accessOrNull(msg: String): AriseAccessibilityService? {
        val s = AriseAccessibilityService.instance
        if (s == null) progress("Accessibility service not enabled")
        return s
    }
}

/** Tool callable name→function. All args are validated before execution. */
class ToolSpec(
    val name: String,
    val summary: String,
    /** Minimal JSON schema description surfaced to the cloud model. */
    val argDoc: String,
    val sensitiveByDefault: Boolean = false,
    val exec: suspend AgentContext.(Map<String, String>) -> ToolResult
)

/** Result builders so no tool can accidentally lie about state. */
fun okResult(tool: String, detail: String, output: String? = null): ToolResult =
    ToolResult(tool, ToolStatus.SUCCESS, detail, verified = true, output = output)

fun partialResult(tool: String, detail: String): ToolResult =
    ToolResult(tool, ToolStatus.PARTIAL, detail, verified = false)

fun failResult(tool: String, detail: String): ToolResult =
    ToolResult(tool, ToolStatus.FAILED, detail, verified = false)

fun blockedResult(tool: String, detail: String): ToolResult =
    ToolResult(tool, ToolStatus.BLOCKED, detail, verified = false)

fun noPermResult(tool: String, detail: String): ToolResult =
    ToolResult(tool, ToolStatus.NO_PERMISSION, detail, verified = false)

fun abortResult(tool: String, detail: String): ToolResult =
    ToolResult(tool, ToolStatus.ABORTED, detail, verified = false)

/** Convenience param accessors. */
fun Map<String, String>.req(tool: String, vararg keys: String): ToolResult? {
    for (k in keys) if (this[k].isNullOrBlank()) {
        return failResult(tool, "missing required parameter: $k")
    }
    return null
}

fun Map<String, String>.optNum(key: String): Double? = this[key]?.trim()?.toDoubleOrNull()
