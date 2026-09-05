package com.arise.assistant.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.StatFs
import android.provider.Settings
import android.view.KeyEvent
import com.arise.assistant.engine.ToolResult
import com.arise.assistant.util.AppResolver
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LEVEL 1 — native Android control via official APIs & intents.
 * Each tool validates params, checks capability, executes, then verifies before SUCCESS.
 * Tools never claim success without a verification step.
 */
object NativeExecutors {

    fun all(): List<ToolSpec> = listOf(
        ToolSpec("open_app", "Launch an installed app", "app: app name/package (required)") { a -> openApp(a) },
        ToolSpec("close_app", "Close/leave the current app and return home", "app: optional app name") { a -> closeApp(a) },
        ToolSpec("launch_url", "Open a web URL in the browser", "url: full http(s) URL (required)") { a -> launchUrl(a) },
        ToolSpec("search_web", "Search the web (opens results in the browser)", "query: search text (required)") { a -> searchWeb(a) },
        ToolSpec("play_on_youtube", "Open YouTube and search a song so it can be played", "song: song or video name (required)") { a -> playOnYouTube(a) },
        ToolSpec("get_current_app", "Report which app is in the foreground", "") { _ -> currentApp() },
        ToolSpec("open_notifications", "Open the notification shade", "") { _ -> openNotifications() },
        ToolSpec("control_media", "Play/pause/next/previous/mute media", "action: play|pause|next|previous|mute|unmute (required)") { a -> controlMedia(a) },
        ToolSpec("change_volume", "Adjust media volume or brightness", "direction up/down OR percent 0-100, kind: brightness") { a -> changeVolume(a) },
        ToolSpec("control_flashlight", "Turn camera flashlight on/off", "state: on|off (required)") { a -> flashlight(a) },
        ToolSpec("get_time", "Tell the current time and date", "") { _ -> okResult("get_time", timeNow(), output = timeNow()) },
        ToolSpec("device_status", "Report battery and storage status", "topic: battery|storage (optional)") { _ -> deviceStatus() },
        ToolSpec("set_alarm", "Open the clock app with an alarm set", "time: like \"7 30 am\" (required)") { a -> setAlarm(a) }
    )

    private suspend fun AgentContext.openApp(args: Map<String, String>): ToolResult {
        args.req("open_app", "app")?.let { return it }
        if (!settings.allowExternalActions) return blockedResult("open_app", "Launching apps is disabled in Settings")
        val app = args["app"]!!
        val launchable = AppResolver.resolve(ctx, app)
        if (launchable == null) return failResult("open_app", "I could not find “$app” installed on this phone")
        progress("Launching ${launchable.label}…")
        val intent = ctx.packageManager.getLaunchIntentForPackage(launchable.pkg)
            ?: return failResult("open_app", "No launcher activity for ${launchable.pkg}")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        val access = access
        if (access != null) {
            val before = access.currentPackage
            var ok = false
            repeat(12) {
                delay(250)
                val now = access.currentPackage
                if (now != null && now != before && now != "com.arise.assistant") { ok = true; return@repeat }
            }
            if (ok) return okResult("open_app", "Opened ${launchable.label}", output = launchable.pkg)
            return partialResult("open_app", "Launched ${launchable.label} but couldn’t verify the foreground app")
        }
        delay(450)
        return okResult("open_app", "Opened ${launchable.label}", output = launchable.pkg)
    }

    private suspend fun AgentContext.closeApp(args: Map<String, String>): ToolResult {
        val access = access
            ?: return blockedResult("close_app", "Enable Arise accessibility (Settings → Accessibility) so I can close apps")
        val target = args["app"]
        if (target.isNullOrBlank() || target.equals("current", true)) {
            if (!access.globalActionHome()) return failResult("close_app", "Could not send Home")
            delay(500)
            return okResult("close_app", "Returned to the home screen")
        }
        if (!access.globalActionHome()) return failResult("close_app", "Could not send Home")
        delay(500)
        return okResult("close_app", "Brought you home from $target (Android doesn’t allow me to force-stop other apps)")
    }

    private suspend fun AgentContext.launchUrl(args: Map<String, String>): ToolResult {
        args.req("launch_url", "url")?.let { return it }
        if (!settings.allowExternalActions) return blockedResult("launch_url", "Opening links is disabled in Settings")
        var u = args["url"]!!
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(ctx.packageManager) == null) return failResult("launch_url", "No browser available for $u")
        ctx.startActivity(intent)
        delay(500)
        return okResult("launch_url", "Opened $u", output = u)
    }

    private suspend fun AgentContext.searchWeb(args: Map<String, String>): ToolResult {
        args.req("search_web", "query")?.let { return it }
        if (!settings.allowExternalActions) return blockedResult("search_web", "Web search is disabled in Settings")
        val q = args["query"]!!
        val url = "https://www.google.com/search?q=" + Uri.encode(q)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(600)
        return okResult("search_web", "Searching for “$q”", output = q)
    }
    private suspend fun AgentContext.playOnYouTube(args: Map<String, String>): ToolResult {
        args.req("play_on_youtube", "song")?.let { return it }
        if (!settings.allowExternalActions) return blockedResult("play_on_youtube", "Opening links is disabled in Settings")
        val song = args["song"]!!.trim()
        if (song.isEmpty()) return failResult("play_on_youtube", "I need a song name to search on YouTube")
        val url = "https://www.youtube.com/results?search_query=" + Uri.encode(song)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ytPkg = "com.google.android.youtube"
        val ytInstalled = runCatching { ctx.packageManager.getLaunchIntentForPackage(ytPkg) != null }.getOrDefault(false)
        if (ytInstalled) intent.setPackage(ytPkg) // open inside the YouTube app, not the browser
        if (intent.resolveActivity(ctx.packageManager) == null) {
            return failResult("play_on_youtube", "No YouTube or browser is available to play “$song”")
        }
        progress("Searching YouTube for “$song”…")
        val access = access
        val before = access?.currentPackage
        ctx.startActivity(intent)
        if (access != null) {
            var moved = false
            repeat(10) {
                delay(250)
                val now = access.currentPackage
                if (now != null && now != "com.arise.assistant" && now != before) { moved = true; return@repeat }
            }
            if (moved) return okResult("play_on_youtube", "Opened YouTube for “$song” — tap the video to play it", output = url)
            return partialResult("play_on_youtube", "Launched YouTube for “$song” but I can't confirm the screen yet")
        }
        delay(600)
        return okResult("play_on_youtube", "Opened YouTube for “$song” — tap the video to play it", output = url)
    }


    private suspend fun AgentContext.currentApp(): ToolResult {
        val label = access?.currentAppLabel()
        val app = if (!label.isNullOrBlank() && label != "unknown") label else run {
            @Suppress("DEPRECATION")
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            try { am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName } catch (_: Exception) { null }
        }
        return if (app.isNullOrBlank()) failResult("get_current_app", "Cannot determine the foreground app")
        else okResult("get_current_app", "You are in $app", output = app)
    }

    private suspend fun AgentContext.openNotifications(): ToolResult {
        val access = access
            ?: return blockedResult("open_notifications", "Enable Arise accessibility to open the notification shade")
        return if (access.globalActionNotifications()) okResult("open_notifications", "Opened notifications")
        else failResult("open_notifications", "Could not open notifications")
    }

    private suspend fun AgentContext.controlMedia(args: Map<String, String>): ToolResult {
        args.req("control_media", "action")?.let { return it }
        if (!settings.allowExternalActions) return blockedResult("control_media", "Media control disabled in Settings")
        val action = args["action"]!!
        if (action !in setOf("play", "pause", "next", "previous", "mute", "unmute")) {
            return failResult("control_media", "Unknown action $action")
        }
        ctx.sendBroadcast(Intent("com.android.music.musicservicecommand").putExtra("command", action))
        val key = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "mute" -> KeyEvent.KEYCODE_MUTE
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        for ((name, pkg) in AppResolver.KNOWN_PACKAGES) {
            if (name !in setOf("spotify", "youtube music", "netflix", "music")) continue
            if (!AppResolver.isPackageInstalled(ctx, pkg)) continue
            ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, key)))
            ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, key)))
        }
        // From a normal app the outcome can’t be proven without a notification listener,
        // so we say exactly that instead of inventing a success.
        return partialResult("control_media", "Sent “$action” to media players — confirm it worked on screen")
    }

    private suspend fun AgentContext.changeVolume(args: Map<String, String>): ToolResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (args["kind"] == "brightness") {
            return try {
                val down = args["direction"] == "down"
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                var b = try { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 128 }
                b = (b + if (down) -25 else 25).coerceIn(10, 255)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, b)
                okResult("change_volume", "Brightness ${if (down) "lowered" else "raised"} to ${b * 100 / 255}%")
            } catch (_: SecurityException) {
                noPermResult("change_volume", "Adjusting brightness needs the write-settings permission that Android restricts")
            }
        }
        val percent = args.optNum("percent")
        if (percent != null) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val idx = (percent / 100.0 * max).toInt().coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, AudioManager.FLAG_SHOW_UI)
            return if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == idx) {
                okResult("change_volume", "Volume set to ${percent.toInt()}%")
            } else partialResult("change_volume", "Volume request sent but the read-back level differs")
        }
        val down = args["direction"] == "down"
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (down) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        return okResult("change_volume", "Volume ${if (down) "down" else "up"} (now ${am.getStreamVolume(AudioManager.STREAM_MUSIC)})")
    }

    private suspend fun AgentContext.flashlight(args: Map<String, String>): ToolResult {
        args.req("control_flashlight", "state")?.let { return it }
        val on = args["state"] == "on"
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { c ->
                cm.getCameraCharacteristics(c).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return failResult("control_flashlight", "This device has no flashlight")
            cm.setTorchMode(id, on)
            delay(120)
            okResult("control_flashlight", "Flashlight ${if (on) "on" else "off"}")
        } catch (_: SecurityException) {
            noPermResult("control_flashlight", "Camera permission is required for the flashlight — grant it in Arise permissions")
        } catch (e: Exception) {
            failResult("control_flashlight", "Flashlight unavailable: ${e.message}")
        }
    }

    private fun timeNow(): String =
        SimpleDateFormat("h:mm a, EEEE d MMMM yyyy", Locale.getDefault()).format(Date())

    private suspend fun AgentContext.deviceStatus(): ToolResult {
        val level = try {
            val bm = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val l = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val s = bm?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (l < 0) -1 else l * 100 / s
        } catch (_: Exception) { -1 }
        val stat = StatFs(ctx.filesDir.absolutePath)
        val free = stat.availableBytes / (1024.0 * 1024 * 1024)
        val total = stat.totalBytes / (1024.0 * 1024 * 1024)
        val txt = "Battery ${if (level < 0) "unknown" else "$level%"}, free storage ${"%.1f".format(free)} GB of ${"%.1f".format(total)} GB"
        return okResult("device_status", txt, output = txt)
    }

    private suspend fun AgentContext.setAlarm(args: Map<String, String>): ToolResult {
        args.req("set_alarm", "time")?.let { return it }
        val hm = parseClock(args["time"]!!)
            ?: return failResult("set_alarm", "I didn’t understand the time “${args["time"]}”")
        val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.putExtra(android.provider.AlarmClock.EXTRA_HOUR, hm.first)
        i.putExtra(android.provider.AlarmClock.EXTRA_MINUTES, hm.second)
        ctx.startActivity(i)
        return partialResult("set_alarm", "Clock opened with an alarm at ${hm.first}:${"%02d".format(hm.second)} — confirm it in the clock app")
    }

    /** Parse "7:30 am", "7 am", "19 15", "6 30 pm" → (hour, minute). */
    private fun parseClock(raw: String): Pair<Int, Int>? {
        val t = raw.lowercase(Locale.ROOT).replace("o'clock", "").replace(" at", "").trim()
        val m = Regex("(\\d{1,2})\\s*(?::|\\s+)(\\d{1,2})?\\s*(am|pm)?").find(t) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        val ap = m.groupValues[3]
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        return if (h in 0..23 && min in 0..59) h to min else null
    }
}
