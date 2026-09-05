package com.arise.assistant.tools

import com.arise.assistant.engine.ToolResult
import com.arise.assistant.engine.ToolStatus

/**
 * Registry of every action Arise can perform. Used by BOTH the fast local router
 * and the cloud model agent, so model tool calls and spoken shortcuts share one
 * implementation with a single source of truth.
 */
object ToolRegistry {
    private val specs = linkedMapOf<String, ToolSpec>()

    fun register(spec: ToolSpec) { specs[spec.name] = spec }

    fun list(): List<ToolSpec> = specs.values.toList()

    fun find(name: String): ToolSpec? = specs[name]

    suspend fun execute(ctx: AgentContext, name: String, args: Map<String, String>): ToolResult {
        val spec = find(name)
            ?: return ToolResult("$name", ToolStatus.FAILED, "unknown tool: $name")
        return try {
            ctx.progress("tool: $name")
            spec.exec(ctx, args)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c // preserve real cancellation
        } catch (t: Throwable) {
            ToolResult(name, ToolStatus.FAILED, "exception: ${t.javaClass.simpleName}: ${t.message ?: "?"}")
        }
    }

    /** Cloud-facing doc of the full tool surface. */
    fun systemPromptSection(): String = buildString {
        appendLine("TOOLS (call by name with the JSON params shown):")
        for (s in specs.values) {
            appendLine("- ${s.name}: ${s.summary}. params: ${s.argDoc}")
        }
    }
}
