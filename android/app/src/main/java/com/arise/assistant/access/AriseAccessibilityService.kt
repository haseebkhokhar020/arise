package com.arise.assistant.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.arise.assistant.log.LocalLog
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Arise's Level-3 universal agent surface.
 *
 * It only performs actions (typed by the user) while the user is driving Arise —
 * it is NOT used to quietly watch or record anything, and it never uploads UI
 * content anywhere. Screen pixels are used only when a specific command truly
 * requires it and the user has enabled that feature.
 */
class AriseAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AriseAccessibilityService? = null
        fun isRunning(): Boolean = instance != null
        private val idSeq = AtomicLong(0)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Package + activity of the foreground window as last seen by events. */
    @Volatile var currentPackage: String? = null
        private set
    @Volatile var currentClass: String? = null
        private set
    @Volatile var lastEventAt: Long = 0
        private set

    /** Subscribers for window changes (verification can await a screen change). */
    private val windowListeners = mutableListOf<() -> Unit>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LocalLog.i("Access", "connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        lastEventAt = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val pkg = event.packageName?.toString()
                val cls = event.className?.toString()
                if (pkg != null) currentPackage = pkg
                if (cls != null) currentClass = cls
                synchronized(windowListeners) { windowListeners.toList() }.forEach { it() }
            }
            else -> Unit
        }
    }

    override fun onInterrupt() {}

    // ---------- helpers usable from the engine (suspend) ----------

    suspend fun <T> onMain(block: () -> T): T = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            try { cont.resume(block()) } catch (t: Throwable) { if (cont.isActive) cont.resumeWith(Result.failure(t)) }
        }
    }

    private fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    /** Snapshot the current screen as lightweight node copies (main-thread only). */
    private fun snapshot(): List<UiNode> {
        val root = root() ?: return emptyList()
        val out = mutableListOf<UiNode>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40) return
            if (n.text != null || n.contentDescription != null || n.isClickable || n.viewIdResourceName != null || n.isScrollable) {
                val b = Rect()
                n.getBoundsInScreen(b)
                out.add(
                    UiNode(
                        id = idSeq.incrementAndGet(),
                        text = n.text?.toString(),
                        desc = n.contentDescription?.toString(),
                        className = n.className?.toString(),
                        viewId = n.viewIdResourceName,
                        clickable = n.isClickable,
                        longClickable = n.isLongClickable,
                        scrollable = n.isScrollable,
                        focusable = n.isFocusable,
                        focused = n.isFocused,
                        editable = n.isEditable,
                        checkable = n.isCheckable,
                        checked = n.isChecked,
                        pkg = n.packageName?.toString(),
                        bounds = b
                    )
                )
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    private fun findRaw(max: Int = 12, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val root = root() ?: return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            if (out.size >= max) return
            if (pred(n)) { out.add(n); if (out.size >= max) return }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c)
            }
        }
        walk(root)
        return out
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur = n
        var guard = 0
        while (guard++ < 12) {
            if (cur.isClickable) return cur
            val p = cur.parent ?: return null
            cur = p
        }
        return null
    }

    private fun clickOnRaw(n: AccessibilityNodeInfo): Boolean =
        (clickableAncestor(n) ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)

    private fun performTextAction(n: AccessibilityNodeInfo, text: String): Boolean {
        val bundle = android.os.Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val did = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        if (!did && n.isEditable) {
            // fall back to pasting from our own clipboard
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("arise", text))
            return n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        return did
    }

    private fun scrollDir(node: AccessibilityNodeInfo, dir: String): Boolean {
        val action = if (dir == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        var cur: AccessibilityNodeInfo? = node
        var guard = 0
        while (cur != null && guard++ < 14) {
            if (cur.isScrollable && cur.performAction(action)) return true
            cur = cur.parent
        }
        return false
    }

    private fun tap(x: Int, y: Int): Boolean {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    // ================= public suspend API used by tools =================

    suspend fun currentAppLabel(): String = onMain {
        var pkg = currentPackage
        if (pkg == null) {
            val r = root()
            pkg = r?.packageName?.toString()
        }
        pkg ?: "unknown"
    }

    suspend fun snapshotNodes(): List<UiNode> = onMain { snapshot() }

    suspend fun dumpHierarchy(maxChars: Int = 6000): String = onMain {
        val sb = StringBuilder()
        for (n in snapshot()) {
            sb.append("[pkg=${n.pkg ?: "-"}] ")
            if (n.text != null) sb.append("text=\"${n.text}\" ")
            if (n.desc != null) sb.append("desc=\"${n.desc}\" ")
            sb.append("id=${n.id} viewId=${n.viewId ?: "-"} cls=${short(n.className)} click=${n.clickable} scroll=${n.scrollable} bounds=${n.bounds}\n")
            if (sb.length > maxChars) break
        }
        sb.toString()
    }

    suspend fun clickOnText(query: String, exact: Boolean = false): UiActionResult = onMain {
        val q = query.trim()
        val targets = findRaw { n ->
            val t = n.text?.toString() ?: return@findRaw false
            if (exact) t.equals(q, ignoreCase = true) else t.contains(q, ignoreCase = true)
        }
        if (targets.isEmpty()) return@onMain UiActionResult(false, "no node with text \"$q\"")
        for (t in targets) { if (clickOnRaw(t)) return@onMain UiActionResult(true, "clicked \"${t.text}\"") }
        UiActionResult(false, "text found but not clickable: \"$q\"")
    }

    suspend fun clickOnDesc(query: String): UiActionResult = onMain {
        val q = query.trim()
        val targets = findRaw { n -> n.contentDescription?.toString()?.contains(q, ignoreCase = true) == true }
        if (targets.isEmpty()) return@onMain UiActionResult(false, "no element described \"$q\"")
        for (t in targets) { if (clickOnRaw(t)) return@onMain UiActionResult(true, "clicked ${t.contentDescription}") }
        UiActionResult(false, "element found but not clickable")
    }

    suspend fun clickById(viewId: String): UiActionResult = onMain {
        val targets = findRaw { n -> n.viewIdResourceName?.endsWith(viewId, ignoreCase = true) == true }
        if (targets.isEmpty()) return@onMain UiActionResult(false, "no element with id \"$viewId\"")
        for (t in targets) { if (clickOnRaw(t)) return@onMain UiActionResult(true, "clicked id $viewId") }
        UiActionResult(false, "element found but not clickable")
    }

    suspend fun longClickOnText(query: String): UiActionResult = onMain {
        val q = query.trim()
        val targets = findRaw { n -> n.text?.toString()?.contains(q, ignoreCase = true) == true }
        if (targets.isEmpty()) return@onMain UiActionResult(false, "no node with text \"$q\"")
        for (t in targets) {
            val target = (clickableAncestor(t) ?: t)
            if (target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return@onMain UiActionResult(true, "long-clicked \"${t.text}\"")
        }
        UiActionResult(false, "long click not supported on \"$q\"")
    }

    /** Type into the first visible editable field. */
    suspend fun typeText(text: String): UiActionResult = onMain {
        val fields = findRaw { it.isEditable }
        if (fields.isEmpty()) return@onMain UiActionResult(false, "no text field visible")
        val field = fields.first()
        val ok = performTextAction(field, text)
        UiActionResult(ok, if (ok) "typed ${text.length} chars" else "failed to type")
    }

    /** Tap a node located by content-description (e.g. WhatsApp "Send"). */
    suspend fun clickByDescExact(desc: String): UiActionResult = onMain {
        val targets = findRaw { n ->
            n.contentDescription?.toString()?.equals(desc, ignoreCase = true) == true ||
                n.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true
        }
        if (targets.isEmpty()) return@onMain UiActionResult(false, "no \"$desc\" button")
        for (t in targets) { if (clickOnRaw(t)) return@onMain UiActionResult(true, "pressed $desc") }
        UiActionResult(false, "\"$desc\" found but not clickable")
    }

    suspend fun scroll(direction: String): UiActionResult = onMain {
        val root = root()
        if (root == null) return@onMain UiActionResult(false, "no screen")
        val ok = scrollDir(root, direction)
        UiActionResult(ok, if (ok) "scrolled $direction" else "nothing to scroll")
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): UiActionResult = onMain {
        val path = Path()
        path.moveTo(x1.toFloat(), y1.toFloat())
        path.lineTo(x2.toFloat(), y2.toFloat())
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong())).build()
        val ok = dispatchGesture(g, null, null)
        UiActionResult(ok, if (ok) "swiped" else "gesture failed")
    }

    suspend fun tapAt(x: Int, y: Int): UiActionResult = onMain {
        val ok = tap(x, y)
        UiActionResult(ok, if (ok) "tapped ($x,$y)" else "tap failed")
    }

    suspend fun globalActionBack(): Boolean = onMain { performGlobalAction(GLOBAL_ACTION_BACK) }
    suspend fun globalActionHome(): Boolean = onMain { performGlobalAction(GLOBAL_ACTION_HOME) }
    suspend fun globalActionNotifications(): Boolean = onMain { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }
    suspend fun globalActionRecents(): Boolean = onMain { performGlobalAction(GLOBAL_ACTION_RECENTS) }

    /**
     * Take a screenshot to app-private storage. Only permitted for accessibility
     * services with user consent (system dialog on modern Android). Returns file path.
     */
    suspend fun captureScreenshot(): UiActionResult {
        if (Build.VERSION.SDK_INT < 30) return UiActionResult(false, "screenshots need Android 11+")
        return suspendCancellableCoroutine { cont ->
            val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
            takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val name = "shot_${System.currentTimeMillis()}.png"
                    val f = java.io.File(filesDir, name)
                    try {
                        screenshot.hardwareBuffer.let { hb ->
                            val bmp = android.graphics.Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                            if (bmp == null) {
                                if (cont.isActive) cont.resume(UiActionResult(false, "unable to decode frame"))
                                return
                            }
                            val out = java.io.FileOutputStream(f)
                            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                            out.flush(); out.close()
                            hb.close()
                            if (cont.isActive) cont.resume(UiActionResult(true, f.absolutePath))
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(UiActionResult(false, "save failed: ${e.message}"))
                    } finally { exec.shutdown() }
                }
                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(UiActionResult(false, "screenshot denied (code $errorCode) — enable in system accessibility settings"))
                    exec.shutdown()
                }
            })
        }
    }

    private fun short(cls: String?): String = cls?.substringAfterLast('.') ?: "-"
}

/** Lightweight snapshot of one accessibility node. */
data class UiNode(
    val id: Long,
    val text: String?,
    val desc: String?,
    val className: String?,
    val viewId: String?,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val pkg: String?,
    val bounds: Rect
)

data class UiActionResult(val ok: Boolean, val detail: String)
