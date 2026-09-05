package com.arise.assistant.tools

import com.arise.assistant.engine.ToolResult
import kotlinx.coroutines.delay

/**
 * LEVEL 3 — universal agent actions. Prefer accessibility tree data; screenshots
 * only for commands that genuinely need them. Screen contents are never uploaded.
 */
object AccessExecutors {

    fun all(): List<ToolSpec> = listOf(
        ToolSpec("find_text", "Locate text on the current screen", "query: text (required)") { a -> findText(a) },
        ToolSpec("find_element", "Locate an element by description or view-id", "query: content-desc or view id (required)") { a -> findElement(a) },
        ToolSpec("click", "Click the element with the given text", "text: label (required)") { a -> clickOnText(a, false) },
        ToolSpec("click_exact", "Click an exact text match", "text: label (required)") { a -> clickOnText(a, true) },
        ToolSpec("click_desc", "Click an element by its content description (e.g. Send button)", "desc: description (required)") { a -> clickDesc(a) },
        ToolSpec("long_click", "Long-press a text element", "text: label (required)") { a -> longClick(a) },
        ToolSpec("type_text", "Type text into the focused/visible input", "text: string to type (required)") { a -> typeText(a) },
        ToolSpec("scroll", "Scroll the screen up or down", "direction: up|down (required)") { a -> scrollDir(a) },
        ToolSpec("swipe", "Swipe between two screen points", "x1,y1,x2,y2: ints (required), duration_ms optional") { a -> swipe(a) },
        ToolSpec("press_back", "Press the system Back button", "") { _ -> back() },
        ToolSpec("go_home", "Press the system Home button", "") { _ -> home() },
        ToolSpec("read_screen", "Return a summary of on-screen elements", "") { _ -> readScreen() },
        ToolSpec("take_screenshot", "Save a screenshot privately on this device", "") { _ -> screenshot() },
        ToolSpec("wait", "Pause briefly", "ms: milliseconds (required)") { a -> waitFor(a) }
    )

    private suspend fun AgentContext.requireAccess(tool: String): com.arise.assistant.access.AriseAccessibilityService? {
        val s = access
        if (s == null) progress("The Arise accessibility service is not enabled")
        return s
    }

    private suspend fun AgentContext.findText(args: Map<String, String>): ToolResult {
        args.req("find_text", "query")?.let { return it }
        val s = requireAccess("find_text") ?: return blockedResult("find_text",
            "Enable Arise accessibility in Android Settings → Accessibility to read screens")
        val nodes = s.snapshotNodes()
        val q = args["query"]!!.lowercase()
        val found = nodes.filter { it.text?.lowercase()?.contains(q) == true || it.desc?.lowercase()?.contains(q) == true }
        if (found.isEmpty()) return failResult("find_text", "“$q” not visible on this screen")
        val first = found.first()
        return okResult("find_text", "Found ${found.size} match(es); first: “${first.text ?: first.desc}”", output = "found:${found.size}")
    }

    private suspend fun AgentContext.findElement(args: Map<String, String>): ToolResult {
        args.req("find_element", "query")?.let { return it }
        val s = requireAccess("find_element") ?: return blockedResult("find_element",
            "Enable Arise accessibility to inspect the screen")
        val q = args["query"]!!
        val nodes = s.snapshotNodes()
        val found = nodes.filter { it.viewId?.contains(q, ignoreCase = true) == true || it.desc?.contains(q, true) == true }
        if (found.isEmpty()) return failResult("find_element", "No element matches “$q”")
        return okResult("find_element", "Found ${found.size} element(s) matching “$q”")
    }

    private suspend fun AgentContext.clickOnText(args: Map<String, String>, exact: Boolean): ToolResult {
        args.req("click", "text")?.let { return it }
        val s = requireAccess("click") ?: return blockedResult("click",
            "Enable Arise accessibility to tap on screen elements")
        val r = s.clickOnText(args["text"]!!, exact)
        return if (r.ok) okResult("click", r.detail) else failResult("click", r.detail)
    }

    private suspend fun AgentContext.clickDesc(args: Map<String, String>): ToolResult {
        args.req("click_desc", "desc")?.let { return it }
        val s = requireAccess("click_desc") ?: return blockedResult("click_desc",
            "Enable Arise accessibility to tap buttons")
        val r = s.clickByDescExact(args["desc"]!!)
        return if (r.ok) okResult("click_desc", r.detail) else failResult("click_desc", r.detail)
    }

    private suspend fun AgentContext.longClick(args: Map<String, String>): ToolResult {
        args.req("long_click", "text")?.let { return it }
        val s = requireAccess("long_click") ?: return blockedResult("long_click",
            "Enable Arise accessibility to long-press elements")
        val r = s.longClickOnText(args["text"]!!)
        return if (r.ok) okResult("long_click", r.detail) else failResult("long_click", r.detail)
    }

    private suspend fun AgentContext.typeText(args: Map<String, String>): ToolResult {
        args.req("type_text", "text")?.let { return it }
        val s = requireAccess("type_text") ?: return blockedResult("type_text",
            "Enable Arise accessibility to type for you")
        val r = s.typeText(args["text"]!!)
        return if (r.ok) okResult("type_text", r.detail) else failResult("type_text", r.detail)
    }

    private suspend fun AgentContext.scrollDir(args: Map<String, String>): ToolResult {
        args.req("scroll", "direction")?.let { return it }
        val dir = args["direction"]!!
        if (dir !in setOf("up", "down")) return failResult("scroll", "direction must be up or down")
        val s = requireAccess("scroll") ?: return blockedResult("scroll", "Enable Arise accessibility to scroll")
        val r = s.scroll(dir)
        return if (r.ok) okResult("scroll", r.detail) else failResult("scroll", r.detail)
    }

    private suspend fun AgentContext.swipe(args: Map<String, String>): ToolResult {
        val xs = listOf("x1", "y1", "x2", "y2")
        for (k in xs) if (args[k]?.toIntOrNull() == null) return failResult("swipe", "invalid/missing $k")
        val s = requireAccess("swipe") ?: return blockedResult("swipe", "Enable Arise accessibility to swipe")
        val r = s.swipe(args["x1"]!!.toInt(), args["y1"]!!.toInt(), args["x2"]!!.toInt(), args["y2"]!!.toInt())
        return if (r.ok) okResult("swipe", r.detail) else failResult("swipe", r.detail)
    }

    private suspend fun AgentContext.back(): ToolResult {
        val s = requireAccess("press_back") ?: return blockedResult("press_back", "Enable Arise accessibility to press Back")
        return if (s.globalActionBack()) okResult("press_back", "Pressed Back") else failResult("press_back", "Back failed")
    }

    private suspend fun AgentContext.home(): ToolResult {
        val s = requireAccess("go_home") ?: return blockedResult("go_home", "Enable Arise accessibility to go home")
        return if (s.globalActionHome()) okResult("go_home", "Went home") else failResult("go_home", "Home failed")
    }

    private suspend fun AgentContext.readScreen(): ToolResult {
        val s = requireAccess("read_screen") ?: return blockedResult("read_screen",
            "Enable Arise accessibility to read the screen")
        val dump = s.dumpHierarchy(2400)
        return okResult("read_screen", "Screen read (stays on your device)", output = dump)
    }

    private suspend fun AgentContext.screenshot(): ToolResult {
        if (settings.visualAgentEnabled) {
            val s = requireAccess("take_screenshot")
            if (s != null) {
                val r = s.captureScreenshot()
                if (r.ok) return okResult("take_screenshot", "Saved to ${r.detail}", output = r.detail)
                return failResult("take_screenshot", r.detail)
            }
        }
        return blockedResult("take_screenshot", "Screen capture needs the accessibility service and its capture permission")
    }

    private suspend fun AgentContext.waitFor(args: Map<String, String>): ToolResult {
        val ms = args["ms"]?.toLongOrNull() ?: return failResult("wait", "ms must be a number")
        delay(ms.coerceIn(0, 30_000))
        return okResult("wait", "waited ${ms}ms")
    }
}
