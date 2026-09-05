package com.arise.assistant.log

import android.content.Context
import com.arise.assistant.engine.LogLine
import com.arise.assistant.util.Util
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of local events (never secrets — [logSecret] is filtered).
 * Visible from the Debug screen and optionally dumped to a file in app-private storage.
 */
object LocalLog {
    private const val MAX = 500
    private val buffer = ArrayDeque<LogLine>()
    @Volatile var enabled: Boolean = true

    private fun push(tag: String, msg: String) {
        if (!enabled) return
        synchronized(buffer) {
            buffer.addLast(LogLine(System.currentTimeMillis(), tag, Util.redact(msg)))
            while (buffer.size > MAX) buffer.removeFirst()
        }
        android.util.Log.d("Arise/$tag", Util.redact(msg))
    }

    fun d(tag: String, msg: String) = push(tag, msg)
    fun i(tag: String, msg: String) = push(tag, msg)
    fun w(tag: String, msg: String) = push(tag, msg)
    fun e(tag: String, msg: String) = push(tag, msg)
    fun secret(tag: String) = Unit // never log secrets — this method is a deliberate no-op

    fun clear() = synchronized(buffer) { buffer.clear() }

    fun snapshot(): List<LogLine> = synchronized(buffer) { buffer.toList() }

    fun dumpToFile(context: Context): File? = try {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
        val out = File(context.filesDir, "logs/arise_${System.currentTimeMillis()}.log")
        out.parentFile?.mkdirs()
        out.bufferedWriter().use { w ->
            snapshot().forEach { w.write("${fmt.format(Date(it.ts))}  ${it.tag.padEnd(12)} ${it.msg}\n") }
        }
        out
    } catch (e: Exception) {
        android.util.Log.e("Arise/LocalLog", "dump failed", e)
        null
    }
}
