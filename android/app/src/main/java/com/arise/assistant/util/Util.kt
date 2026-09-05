package com.arise.assistant.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.PowerManager
import java.util.Locale

/** Display name for a given phase shown on screen and in logs. */
fun humanizePhase(phase: String): String = when (phase) {
    "IDLE" -> "Idle"
    "WAKING" -> "Waking"
    "VERIFYING" -> "Verifying speaker"
    "ACK" -> "Awake"
    "LISTENING" -> "Listening"
    "PROCESSING" -> "Working"
    "SPEAKING" -> "Speaking"
    "CONFIRMING" -> "Confirming"
    "SUCCESS" -> "Done"
    "ERROR" -> "Error"
    else -> phase.lowercase().replaceFirstChar { it.uppercaseChar() }
}

/** App launcher resolution, cached after first query (queried once per process). */
object AppResolver {

    data class Launchable(val label: String, val pkg: String, val cls: String)

    private var cache: List<Launchable>? = null

    private fun load(context: Context): List<Launchable> {
        cache?.let { return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = mutableListOf<Launchable>()
        val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }
        for (ri in resolved) {
            val label = ri.loadLabel(pm)?.toString()?.trim() ?: continue
            list.add(Launchable(label, ri.activityInfo.packageName, ri.activityInfo.name))
        }
        cache = list
        return list
    }

    /**
     * Resolve a spoken app name to an installed launchable package.
     * Checks: exact package name → known alias → launcher-label fuzzy match.
     */
    fun resolve(context: Context, app: String): Launchable? {
        val query = app.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) return null
        val known = KNOWN_PACKAGES[query]
        if (known != null) {
            val l = findLaunchableByPkg(context, known)
            if (l != null) return l
        }
        // launcher label match
        var best: Launchable? = null
        var bestScore = -1
        for (l in load(context)) {
            val label = l.label.lowercase(Locale.ROOT)
            val score = when {
                label == query -> 100
                label.startsWith(query) || query.startsWith(label) && label.length >= 4 -> 60
                label.contains(query) || query.contains(label) && label.length >= 5 -> 40
                else -> -1
            }
            if (score > bestScore) { bestScore = score; best = l }
        }
        if (bestScore >= 40) return best
        // ambiguous nicknames get their canonical package if installed
        return null
    }

    fun findLaunchableByPkg(context: Context, pkg: String): Launchable? {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return null
        launch.component?.let { c ->
            return Launchable(launch.component!!.flattenToString(), c.packageName, c.className)
        }
        return null
    }

    fun isPackageInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) { false }

    val KNOWN_PACKAGES = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "whatsapp business" to "com.whatsapp.w4b",
        "messages" to "com.google.android.apps.messaging",
        "phone" to "com.google.android.dialer",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome",
        "photos" to "com.google.android.apps.photos",
        "camera" to "com.android.camera",
        "settings" to "com.android.settings",
        "play store" to "com.android.vending",
        "calculator" to "com.google.android.calculator",
        "clock" to "com.google.android.deskclock",
        "alarms" to "com.google.android.deskclock",
        "calendar" to "com.google.android.calendar",
        "youtube music" to "com.google.android.apps.youtube.music",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "telegram" to "org.telegram.messenger",
        "linkedin" to "com.linkedin.android",
        "tiktok" to "com.zhiliaoapp.musically"
    )
}

/** Misc helpers. */
object Util {
    /** True when PowerManager says interactive (screen on & unlocked state gates elsewhere). */
    fun isInteractive(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    /** Hide obvious secrets in log text. */
    fun redact(text: String): String =
        text.replace(Regex("(?i)(sk-[A-Za-z0-9_\\-]{6,}|bearer\\s+[A-Za-z0-9._\\-]+|api[_-]?key\\s*[:=]\\s*[A-Za-z0-9._\\-]{6,})"), "[REDACTED]")

    fun require(cond: Boolean, msg: String) { if (!cond) throw IllegalArgumentException(msg) }

    fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}
