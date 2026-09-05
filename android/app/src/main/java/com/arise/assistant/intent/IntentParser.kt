package com.arise.assistant.intent

import com.arise.assistant.engine.ParsedIntent
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Convenience: Java Matcher exposes the whole match like Kotlin's MatchResult.value. */
private val Matcher.value: String get() = group()

/**
 * Fast, local intent parser — the first gate before any model is contacted.
 *
 * Rules are ordered by precedence. Anything not matched clearly is flagged
 * `needsModel=true` so the engine can fall back to cloud AI; the parser never
 * fabricates a confident-but-wrong action.
 */
class IntentParser {

    private val appAliases = mapOf(
        "youtube" to "YouTube", "yt" to "YouTube",
        "whatsapp" to "WhatsApp", "wa" to "WhatsApp",
        "messages" to "Messages", "messaging" to "Messages", "sms" to "Messages",
        "gmail" to "Gmail", "mail" to "Gmail", "email" to "Gmail",
        "maps" to "Maps", "google maps" to "Maps", "navigation" to "Maps",
        "chrome" to "Chrome", "browser" to "Chrome", "internet" to "Chrome",
        "photos" to "Photos", "gallery" to "Photos",
        "camera" to "Camera", "phone" to "Phone", "dialer" to "Phone", "calls" to "Phone",
        "calculator" to "Calculator", "clock" to "Clock", "alarm" to "Clock",
        "calendar" to "Calendar", "settings" to "Settings",
        "spotify" to "Spotify", "music" to "Music", "netflix" to "Netflix",
        "instagram" to "Instagram", "facebook" to "Facebook", "twitter" to "X", "x" to "X",
        "tiktok" to "TikTok", "telegram" to "Telegram", "linkedin" to "LinkedIn"
    )

    private val appsByName = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp", "whatsapp business" to "com.whatsapp.w4b",
        "messages" to "com.google.android.apps.messaging", "phone" to "com.google.android.dialer",
        "gmail" to "com.google.android.gm", "maps" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome", "photos" to "com.google.android.apps.photos",
        "camera" to "com.android.camera", "settings" to "com.android.settings",
        "calculator" to "com.google.android.calculator", "clock" to "com.google.android.deskclock",
        "calendar" to "com.google.android.calendar", "play store" to "com.android.vending",
        "youtube music" to "com.google.android.apps.youtube.music",
        "spotify" to "com.spotify.music", "netflix" to "com.netflix.mediaclient",
        "instagram" to "com.instagram.android", "facebook" to "com.facebook.katana",
        "telegram" to "org.telegram.messenger", "linkedin" to "com.linkedin.android",
        "snapchat" to "com.snapchat.android", "tiktok" to "com.zhiliaoapp.musically"
    )

    // ---------- rules, highest precedence first ----------

    private data class Rule(val pattern: Pattern, val builder: (Matcher) -> ParsedIntent?)

    private fun re(s: String) = Pattern.compile(s, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)

    private fun capture(m: Matcher, g: String): String? = try { m.group(g)?.trim()?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }

    private val rules: List<Rule> = listOf(
        // ---- control: specific cancellation phrases first ----
        Rule(re("\\bgo to? sleep\\b.*$"), { ParsedIntent("sleep", 0.99f, rawText = it.value) }),
        Rule(re("\\b(?:stop listening|stop the assistant|stop the wake|stop listening for|go to? sleep now|cancel the command|cancel that|never mind|nevermind)\\b.*$"), { ParsedIntent("stop", 0.99f, rawText = it.value) }),
        Rule(re("\\b(?:open|launch|start|run)\\s+(?:the\\s+)?(?:app\\s+)?(?<app>.*)$"), { m ->
            val app = appAliases[normalize(capture(m, "app") ?: return@Rule null)] ?: return@Rule null
            ParsedIntent("open_app", params = mapOf("app" to app), appHint = app, rawText = m.value)
        }),
        Rule(re("\\bopen\\s+(?<app>.*)$"), { m ->
            // fallback when alias not matched
            val raw = normalize(capture(m, "app") ?: return@Rule null)
            val app = appAliases[raw] ?: raw.replaceFirstChar { it.uppercaseChar() }
            ParsedIntent("open_app", confidence = 0.7f, params = mapOf("app" to app), appHint = app, rawText = m.value)
        }),
        Rule(re("\\b(?:close|quit|kill|exit)\\s+(?:the\\s+)?(?:app\\s+)?(?<app>.*)$"), { m ->
            val app = appAliases[normalize(capture(m, "app") ?: return@Rule null)] ?: return@Rule null
            ParsedIntent("close_app", params = mapOf("app" to app), appHint = app, rawText = m.value)
        }),
        Rule(re("\\b(?:close|go back|back out|exit)\\s*(?:this app|the app)?$"), { ParsedIntent("close_app", 0.8f, params = mapOf("app" to "current"), rawText = it.value) }),
        Rule(re("\\bgo (?:to )?(?:the )?home (?:screen|page)\\b"), { ParsedIntent("go_home", rawText = it.value) }),
        Rule(re("\\bgo\\s+back\\b"), { ParsedIntent("press_back", rawText = it.value) }),
        Rule(re("\\bopen (?:the )?notifications?\\b"), { ParsedIntent("open_notifications", rawText = it.value) }),
        Rule(re("\\b(?:what app|which app) (?:is|am) i (?:on|in|using)\\b"), { ParsedIntent("get_current_app", rawText = it.value) }),
        Rule(re("\\btake a screenshot\\b"), { ParsedIntent("take_screenshot", rawText = it.value) }),
        Rule(re("\\b(?:read|what is on|what\\'?s on)\\s+(?:the\\s+)?screen\\b"), { ParsedIntent("read_screen", rawText = it.value) }),

        // ---- WhatsApp: any phrasing containing explicit "whatsapp" ----
        Rule(re("\\b(?:send|message)\\s+(?<who>.+?)\\s+(?:an?\\s+|via\\s+)?whatsapp(?:\\s+message)?\\s+(?:saying|that says|that|telling)\\s+(?<msg>.+?)\\s*$"), { m ->
            whatsapp(capture(m, "who"), capture(m, "msg"), m.value)
        }),
        Rule(re("\\bsend\\s+(?:an?\\s+)?whatsapp(?:\\s+message)?\\s+to\\s+(?<who>.+?)\\s+(?:saying|that says|that)\\s+(?<msg>.+?)\\s*$"), { m ->
            whatsapp(capture(m, "who"), capture(m, "msg"), m.value)
        }),
        Rule(re("\\bwhatsapp\\s+(?<who>.+?)\\s+(?:saying|that says|that)\\s+(?<msg>.+?)\\s*$"), { m ->
            whatsapp(capture(m, "who"), capture(m, "msg"), m.value)
        }),
        Rule(re("\\bwhatsapp\\s+(?<who>.+?)\\s*$"), { m ->
            whatsapp(capture(m, "who"), null, m.value)
        }),

        // ---- WhatsApp: "send <message> to <contact> on whatsapp" ----
        Rule(re("\\bsend\\s+(?:a\\s+|an\\s+|the\\s+|me\\s+(?:a\\s+|an\\s+)?)?(?<msg>.+)\\s+to\\s+(?<who>.+?)\\s+(?:on|via|using|through|in)\\s+(?:the\\s+)?whatsapp\\s*$"), { m ->
            whatsapp(capture(m, "who"), stripTrailingMessage(capture(m, "msg")), m.value, 0.92f)
        }),

        // ---- SMS: explicit text/sms phrasings ----
        Rule(re("\\b(?:send|text)\\s+(?<who>.+?)\\s+(?:an?\\s+)?(?:sms|text\\s*message|text)\\s+(?:saying|that says|that)\\s+(?<msg>.+?)\\s*$"), { m ->
            ParsedIntent("send_sms", params = mapOf("contact" to cleanContact(capture(m, "who")!!), "message" to cleanMsg(capture(m, "msg")!!)), rawText = m.value)
        }),
        Rule(re("\\bsend\\s+(?<who>.+?)\\s+(?:an?\\s+)?sms\\s+(?:saying|that says|that)\\s+(?<msg>.+?)\\s*$"), { m ->
            ParsedIntent("send_sms", params = mapOf("contact" to cleanContact(capture(m, "who")!!), "message" to cleanMsg(capture(m, "msg")!!)), rawText = m.value)
        }),

        // ---- email ----
        Rule(re("\\b(?:email|send\\s+(?:an?\\s+)?email(?:\\s+to)?)\\s+(?<who>.+?)\\s+(?:saying|that says|that|reading)\\s+(?<msg>.+?)\\s*$"), { m ->
            ParsedIntent("send_email", params = mapOf("contact" to cleanContact(capture(m, "who")!!), "message" to cleanMsg(capture(m, "msg")!!)), rawText = m.value)
        }),

        // ---- generic "message/tell X (that) Y" → WhatsApp default (documented in Settings) ----
        Rule(re("\\b(?:message|send)\\s+(?<who>[^,\\.]{1,28}?)\\s+(?:that|to say|saying|that says)\\s+(?<msg>.+?)\\s*$"), { m ->
            whatsapp(capture(m, "who"), capture(m, "msg"), m.value, 0.68f)
        }),

        // ---- calls ----
        Rule(re("\\bcall\\s+(?<who>.+?)\\s*$"), { m ->
            ParsedIntent("call_contact", params = mapOf("contact" to cleanContact(capture(m, "who")!!)), rawText = m.value)
        }),

        // ---- "play <song> on YouTube" (must precede generic media play) ----
        Rule(re("\\b(?:play|put on|start)\\s+(?<song>.+?)\\s+on\\s+(?:the\\s+)?(?:youtube|yt)(?:\\s+app)?\\s*$"), { m ->
            ParsedIntent("play_on_youtube", params = mapOf("song" to (songName(capture(m, "song")) ?: "")), rawText = m.value)
        }),

        // ---- media & volume ----
        Rule(re("\\b(?:play|start|resume)\\s+(?<media>(?:some\\s+|the\\s+)?music|a\\s+song|my\\s+playlist|podcast).*$"), { ParsedIntent("control_media", params = mapOf("action" to "play"), rawText = it.value) }),
        Rule(re("\\b(?:pause|stop music|stop the music|mute|unmute)\\b"), { m ->
            when {
                m.value.contains("mute") -> ParsedIntent("control_media", params = mapOf("action" to "mute"), rawText = m.value)
                m.value.contains("unmute") -> ParsedIntent("control_media", params = mapOf("action" to "unmute"), rawText = m.value)
                m.value.contains("pause") || m.value == "stop music" || m.value.contains("stop the music") ->
                    ParsedIntent("control_media", params = mapOf("action" to "pause"), rawText = m.value)
                else -> ParsedIntent("control_media", params = mapOf("action" to "pause"), rawText = m.value)
            }
        }),
        Rule(re("\\b(?:skip|next)\\s*(?:song|track)?"), { ParsedIntent("control_media", params = mapOf("action" to "next"), rawText = it.value) }),
        Rule(re("\\b(?:previous|prev)\\s*(?:song|track)?"), { ParsedIntent("control_media", params = mapOf("action" to "previous"), rawText = it.value) }),
        Rule(re("\\b(?:volume|sound)\\s*(?:up|increase|higher)\\b"), { ParsedIntent("change_volume", params = mapOf("direction" to "up"), rawText = it.value) }),
        Rule(re("\\b(?:volume|sound)\\s*(?:down|decrease|lower|mute)\\b"), { m ->
            if (m.value.contains("mute")) ParsedIntent("change_volume", params = mapOf("action" to "mute"), rawText = m.value)
            else ParsedIntent("change_volume", params = mapOf("direction" to "down"), rawText = m.value)
        }),
        Rule(re("\\bvolume\\s+(?:to\\s+)?(?<pct>\\d{1,3})\\s*(?:percent|%)?\\b"), { m ->
            val v = capture(m, "pct")?.toIntOrNull()?.coerceIn(0, 100) ?: return@Rule null
            ParsedIntent("change_volume", params = mapOf("percent" to v.toString()), rawText = m.value)
        }),

        // bare stop / cancel / halt / quiet — must come after the media rules above
        Rule(re("^\\s*(?:stop|cancel|halt|quiet|that\\'?s? enough)(?:\\s+(?:now|it|that|everything|please))?\\s*[.?!]*\\s*$"),
            { ParsedIntent("stop", 0.99f, rawText = it.value) }),
        // tell <person> <short message> (no connective)
        Rule(re("\\btell\\s+(?<who>[a-z][\\w .\\'\\-]{0,26}?)\\s+(?<msg>[^,.]{2,})$"), { m ->
            val who = cleanContact(capture(m, "who") ?: return@Rule null)
            val msg = cleanMsg(capture(m, "msg") ?: return@Rule null)
            if (who.isEmpty() || msg.isEmpty()) return@Rule null
            ParsedIntent("send_whatsapp_message", confidence = 0.7f,
                params = mapOf("contact" to who, "message" to msg), rawText = m.value)
        }),

        // ---- web / search ----
        Rule(re("\\b(?:search|google|look up|find)\\s+(?:the\\s+)?(?<q>.+?)\\s*$"), { m ->
            val q = capture(m, "q") ?: return@Rule null
            ParsedIntent("search_web", params = mapOf("query" to q), rawText = m.value)
        }),
        Rule(re("\\b(?:go to|visit|open|launch)\\s+(?<u>(?:https?://)?[\\w.-]+\\.[a-z]{2,}(?:[/?][^\\s]*)?)"), { m ->
            val u = capture(m, "u") ?: return@Rule null
            ParsedIntent("launch_url", params = mapOf("url" to u), rawText = m.value)
        }),

        // ---- sensitive / financial / destructive ----
        Rule(re("\\b(?:delete|erase|remove)\\s+(?<target>.+)$"), { m ->
            ParsedIntent("delete_content", confidence = 0.9f, params = mapOf("target" to cleanMsg(capture(m, "target")!!)), sensitive = true, rawText = m.value)
        }),
        Rule(re("\\b(?:transfer|send money|pay|buy|purchase|subscribe|upgrade)\\b.*", ), { m ->
            ParsedIntent("financial_action", confidence = 0.7f, params = mapOf("raw" to m.value), sensitive = true, rawText = m.value, needsModel = true)
        }),
        Rule(re("\\b(?:reset|factory reset|wipe|restore factory)\\b"), { ParsedIntent("factory_reset", confidence = 0.95f, sensitive = true, rawText = it.value) }),

        // ---- device controls (settings, flashlight, wifi, bluetooth) ----
        Rule(re("\\b(?:turn|switch|flip)\\s+(?:the\\s+)?flashlight\\s+(?<st>on|off)\\b"), { m ->
            ParsedIntent("control_flashlight", params = mapOf("state" to capture(m, "st")!!), rawText = m.value)
        }),
        Rule(re("\\b(?:turn|switch)\\s+(?:the\\s+)?(?:wifi|bluetooth)\\s+(?<st>on|off)\\b"), { m ->
            val what = if (m.value.contains("wifi")) "wifi" else "bluetooth"
            ParsedIntent("toggle_radio", params = mapOf("radio" to what, "state" to capture(m, "st")!!), rawText = m.value)
        }),
        Rule(re("\\b(?:increase|raise)\\s+brightness\\b"), { ParsedIntent("change_volume", params = mapOf("kind" to "brightness", "direction" to "up"), rawText = it.value) }),
        Rule(re("\\b(?:decrease|lower)\\s+brightness\\b"), { ParsedIntent("change_volume", params = mapOf("kind" to "brightness", "direction" to "down"), rawText = it.value) }),

        // ---- system queries ----
        Rule(re("\\b(?:what time is it|what\\'?s the time|current time)\\b"), { ParsedIntent("get_time", rawText = it.value) }),
        Rule(re("\\b(?:what\\'?s|what is|how\\'?s|how is|what are)\\b.*\\b(?:battery|storage|memory)\\b"), { ParsedIntent("device_status", params = mapOf("topic" to if (it.value.contains("battery")) "battery" else "storage"), rawText = it.value) }),
        Rule(re("\\bset an? alarm(?: for)? (?<time>.+?)\\s*$"), { m ->
            ParsedIntent("set_alarm", params = mapOf("time" to cleanMsg(capture(m, "time")!!)), rawText = m.value)
        }),
        Rule(re("\\bset a timer(?: for)? (?<time>.+?)\\s*$"), { m ->
            ParsedIntent("set_timer", params = mapOf("duration" to cleanMsg(capture(m, "time")!!)), rawText = m.value)
        }),

        // ---- visual agent helpers ----
        Rule(re("\\b(?:tap|click|press)\\s+on\\s+\\\"?(?<label>[\\w ,]+?)\\\"?$"), { m ->
            ParsedIntent("click_on_text", params = mapOf("label" to cleanMsg(capture(m, "label")!!)), rawText = m.value)
        }),
        Rule(re("\\b(?:scroll)\\s+(?<dir>up|down)\\b"), { m ->
            ParsedIntent("scroll", params = mapOf("direction" to capture(m, "dir")!!), rawText = m.value)
        }),

        // ---- unknown small talk / info => model ----
        Rule(re("\\b(?:hello|hi|hey)\\b"), { ParsedIntent("greeting", rawText = it.value) }),
        Rule(re("\\b(?:who|what|why|how|where|when|tell me|can you|will you|is it)\\b"), { ParsedIntent("ask_question", needsModel = true, rawText = it.value) }),
        Rule(re("\\b(?:thank you|thanks)\\b"), { ParsedIntent("acknowledge", rawText = it.value) })
    )

    /** "send hi message to Ali …" → message text is "hi". */
    private fun stripTrailingMessage(raw: String?): String? {
        val b = cleanMsg(raw ?: return null)
        if (b.isEmpty()) return null
        val v = b.replace(Regex("\\s+message$", RegexOption.IGNORE_CASE), "").trim()
        return v.ifEmpty { null }
    }

    /** Turn a spoken "play <something> song on youtube" into a clean query. */
    private fun songName(raw: String?): String? {
        var v = cleanMsg(raw ?: return null)
        v = v.replace(Regex("\\s+(?:song|track)$", RegexOption.IGNORE_CASE), "").trim()
        v = v.replace(Regex("^(?:the|a|an|this|that|some)\\s+", RegexOption.IGNORE_CASE), "").trim()
        return v.ifEmpty { null }
    }

    private fun whatsapp(who: String?, msg: String?, raw: String, conf: Float = 0.95f): ParsedIntent? {
        if (who == null) return null
        val contact = cleanContact(who)
        if (contact.isEmpty()) return null
        return if (msg == null) {
            // e.g. "whatsapp Ali" — ask for content next
            ParsedIntent("ask_message", confidence = conf, params = mapOf("contact" to contact), rawText = raw)
        } else {
            val message = cleanMsg(msg)
            if (message.isEmpty()) return null
            ParsedIntent("send_whatsapp_message", confidence = conf,
                params = mapOf("contact" to contact, "message" to message), rawText = raw)
        }
    }

    fun parse(text: String): ParsedIntent? {
        val t = (text ?: "").trim()
        if (t.isEmpty()) return null
        for (rule in rules) {
            val m = rule.pattern.matcher(t)
            if (m.find()) {
                val intent = rule.builder(m) ?: continue
                if (!intent.sensitive && intent.intent == "stop" && t.contains("music")) continue
                return intent
            }
        }
        return ParsedIntent("unknown", confidence = 0f, rawText = t, needsModel = true)
    }

    private fun isSensitiveToken(intent: String, t: String): Boolean = false

    fun packageForApp(app: String): String? = appsByName[normalize(app)]

    private fun normalize(s: String): String = s.lowercase(Locale.ROOT).trim()
        .replace(Regex("\\s+"), " ")

    private fun cleanContact(raw: String): String =
        raw.trim().removeSuffix(".").removeSuffix(",").trim()
            .replace(Regex("\\s+"), " ")
            .let { it.removePrefix("to ") }

    private fun cleanMsg(raw: String): String =
        raw.trim().removeSuffix(".").removeSuffix(",").trim()
            .replace(Regex("\\s+"), " ")
}
