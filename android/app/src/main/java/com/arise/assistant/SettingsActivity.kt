package com.arise.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.arise.assistant.billing.BillingManager
import com.arise.assistant.engine.AriseEngine
import com.arise.assistant.log.LocalLog
import com.arise.assistant.service.AriseForegroundService
import com.arise.assistant.settings.Settings
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Settings — one scrollable surface covering AI, voice, wake word, speaker
 * verification, logo/animation, behaviour, privacy, permissions, accessibility,
 * performance, debugging, subscription and about. Every row explains itself in
 * plain language (permission clarity is a product requirement).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var engine: AriseEngine
    private lateinit var root: LinearLayout
    private var rootScroll: ScrollView? = null
    private var billing: BillingManager? = null
    private var pendingEnroll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        engine = AriseApp.instance.engine

        rootScroll = ScrollView(this).apply {
            setBackgroundColor(0xFF070A14.toInt())
            isFillViewport = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(30))
        }
        rootScroll!!.addView(root, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        setContentView(rootScroll)

        buildUi()

        billing = BillingManager(this) { }
        billing?.connect()
    }

    override fun onDestroy() {
        billing?.dispose()
        super.onDestroy()
    }

    // ---------- UI builders ----------

    private fun header() {
        addTitle("Settings")
        addSub("Arise ${com.arise.assistant.util.Util.versionName(this)} · everything below applies instantly")
        addSpace(8)
    }

    private fun buildUi() {
        header()

        // ============ WAKE & VOICE ============
        addSection("Wake word & hands-free")
        textRow("Wake word", settings.wakeWord) { t ->
            val v = t.trim()
            if (v.isEmpty()) { toast("Wake word can't be empty"); return@textRow }
            settings.wakeWord = v
            toast("Wake word now “$v” (say it to test)")
        }
        toggleRow("Hands-free listening", settings.handsFreeEnabled,
            "Keep the microphone gate active so “${settings.wakeWord}” works anywhere. Shows a mic notification.") { on ->
            if (on) requestWakePermissions { setHandsFree(true) } else setHandsFree(false)
        }
        toggleRow("Start after reboot", settings.autoStartOnBoot,
            "Automatically restore hands-free listening when the phone restarts.") { settings.autoStartOnBoot = it }
        textRow("TTS voice", settings.ttsVoice.ifBlank { "System default" }) { v ->
            settings.ttsVoice = v
            toast("Voice set — try it by waking Arise")
        }
        sliderRow("Speech rate", settings.ttsRate, 0.5f..2f, 0.05f, "%.2f") { settings.ttsRate = it }
        toggleRow("Voice replies", settings.ttsEnabled, "Speak replies out loud.") { settings.ttsEnabled = it }
        toggleRow("Reply “Yes?” on wake", settings.ttsSayWakeReply, "Voice acknowledgment right after the wake word.") { settings.ttsSayWakeReply = it }
        sliderRow("End-of-speech delay", settings.endOfSpeechMs.toFloat(), 400f..2200f, 100f, "%.0f ms") { settings.endOfSpeechMs = it.toInt() }
        sliderRow("Follow-up window", settings.followUpTimeoutSec.toFloat(), 0f..30f, 1f, "%.0f s") { settings.followUpTimeoutSec = it.toInt() }

        // ============ AI ============
        addSection("Cloud AI (complex tasks)")
        chipsRow("Provider", listOf("openai" to "OpenAI", "custom" to "Custom", "local" to "Local model"),
            settings.aiProvider) { settings.aiProvider = it }
        textRow("Endpoint", settings.aiEndpoint) { v -> if (v.isNotBlank()) settings.aiEndpoint = v else toast("Empty endpoint ignored") }
        textRow("API key", if (settings.aiApiKey.isEmpty()) "(empty)" else "••••••••", secret = true) { v -> settings.aiApiKey = v }
        textRow("Fast model", settings.aiFastModel) { v -> if (v.isNotBlank()) settings.aiFastModel = v }
        textRow("Powerful model", settings.aiPowerModel) { v -> if (v.isNotBlank()) settings.aiPowerModel = v }
        sliderRow("Temperature", settings.aiTemperature, 0f..1f, 0.05f, "%.2f") { settings.aiTemperature = it }
        toggleRow("Route simple commands locally", settings.aiRoutingEnabled,
            "Fast, private, offline routing for things like “open YouTube”. Cloud is only used when needed.") { settings.aiRoutingEnabled = it }
        addNote("The API key is stored only on this device (Android encrypted storage) and never uploaded anywhere else.")

        // ============ SPEAKER ============
        addSection("Speaker verification (optional)")
        toggleRow("Check speaker after wake", settings.speakerVerifyEnabled,
            "Optional convenience layer. NOT a secure biometric — the wake mic can't guarantee identity. Audio never leaves the device.") { v ->
            settings.speakerVerifyEnabled = v
            if (v && !engine.isSpeakerEnrolled()) toast("Enroll your voice below first")
        }
        actionRow(if (engine.isSpeakerEnrolled()) "Re-enroll your voice (speak ~4s)" else "Enroll your voice (speak ~4s)") {
            if (!micGranted()) { pendingEnroll = true; micPermLauncher.launch(Manifest.permission.RECORD_AUDIO); return@actionRow }
            lifecycleScopeLaunch { enroll() }
        }
        actionRow("Test verification") {
            if (!micGranted()) { pendingEnroll = false; micPermLauncher.launch(Manifest.permission.RECORD_AUDIO); return@actionRow }
            lifecycleScopeLaunch {
                val r = engine.verifySpeakerNow()
                toast(if (r.accepted) "Match (${"%.2f".format(r.score)})" else "No match (${"%.2f".format(r.score)})")
            }
        }
        if (engine.isSpeakerEnrolled()) {
            actionRow("Delete voice profile", danger = true) { engine.clearSpeakerProfile(); toast("Profile deleted"); rebuild() }
        }
        sliderRow("Verification sensitivity", settings.speakerSensitivity, 0.2f..0.95f, 0.05f, "%.2f") { settings.speakerSensitivity = it }

        // ============ LOGO ============
        addSection("Arise logo & motion")
        chipsRow("Theme", listOf("violet" to "Violet", "ocean" to "Ocean", "sunset" to "Sunset", "mono" to "Mono"),
            settings.theme) { settings.theme = it }
        chipsRow("Quality", listOf("low" to "Low", "medium" to "Med", "high" to "High"), settings.logoQuality) { settings.logoQuality = it }
        toggleRow("Animate", settings.logoAnimationEnabled, "Idle motion, particles and parallax.") { settings.logoAnimationEnabled = it }
        toggleRow("Reduced motion", settings.logoReducedMotion, "Calm, near-static logo (accessibility).") { settings.logoReducedMotion = it }
        toggleRow("Voice-reactive effects", settings.logoVoiceReactive, "Logo reacts to mic level while listening/speaking.") { settings.logoVoiceReactive = it }
        toggleRow("Auto-reduce effects on low battery", settings.batterySaverAutoFx, "Drops particle count when the battery is low.") { settings.batterySaverAutoFx = it }
        sliderRow("Idle intensity", settings.logoIdleIntensity, 0.05f..1f, 0.05f, "%.2f") { settings.logoIdleIntensity = it }

        // ============ ACCESSIBILITY ============
        addSection("Control & accessibility")
        actionRow("Arise accessibility service (universal control)") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Find “Arise control” and turn it on")
        }
        toggleRow("Allow universal app control", settings.visualAgentEnabled,
            "Lets Arise read the visible UI and tap/type to complete commands you ask for. Screen content stays on your device.") { settings.visualAgentEnabled = it }
        toggleRow("Allow launching apps & links", settings.allowExternalActions) { settings.allowExternalActions = it }
        toggleRow("Allow messaging (WhatsApp/SMS/calls)", settings.allowMessagingActions,
            "Arise still asks before anything sensitive, and never reads your messages.") { settings.allowMessagingActions = it }

        // ============ PERMISSIONS ============
        addSection("Permissions (explained)")
        permissionRow("Microphone", Manifest.permission.RECORD_AUDIO, "Needed for wake word, voice commands and TTS verification.")
        permissionRow("Notifications", Manifest.permission.POST_NOTIFICATIONS, "Shows the persistent “listening” indicator while hands-free is on.")
        permissionRow("Contacts", Manifest.permission.READ_CONTACTS, "Lets “send Ali a WhatsApp” find Ali’s number. Only queried on device.")
        permissionRow("Send SMS", Manifest.permission.SEND_SMS, "Required by Android to send a text directly. Without it Arise opens the compose screen instead.")
        permissionRow("Camera (flashlight)", Manifest.permission.CAMERA, "Only used to switch the flashlight on/off.")
        actionRow("Open app details (permissions manager)") {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }

        // ============ BEHAVIOUR ============
        addSection("Behaviour & confirmation")
        chipsRow("Confirmation policy", listOf(
            "always" to "Always confirm", "sensitive_only" to "Only sensitive", "never" to "Never confirm"),
            settings.confirmationPolicy) { settings.confirmationPolicy = it }
        addNote("Sensitive = money, deletion, security changes, factory reset. Arise never bypasses Android auth (PIN/biometric), and never acts while the phone is locked except to say it can't.")
        toggleRow("Speak errors out loud", settings.errorSpeechEnabled) { settings.errorSpeechEnabled = it }
        toggleRow("Mic chime on wake", settings.micChimeEnabled) { settings.micChimeEnabled = it }

        // ============ PRIVACY & DEBUG ============
        addSection("Privacy, logs & debugging")
        toggleRow("Debug panel", settings.debugEnabled, "Shows live tool, app and latency details on the home screen.") { settings.debugEnabled = it; rebuild() }
        toggleRow("Latency measurements", settings.latencyLogging, "Record wake/STT/AI/execution latency breakdowns.") { settings.latencyLogging = it }
        toggleRow("Local log (on this device)", settings.localLogsEnabled, "Ring buffer of recent events for troubleshooting; contains no messages, keys or credentials.") { v ->
            settings.localLogsEnabled = v
            LocalLog.enabled = v
        }
        toggleRow("Anonymous diagnostics", settings.telemetryOptIn, "Opt-in aggregate stats only (device model, latency percentiles). Never content.") { settings.telemetryOptIn = it }
        actionRow("Export local log (share file)") {
            LocalLog.dumpToFile(this)?.let { f ->
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(this@SettingsActivity, "$packageName.fileprovider", f))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(i, "Share Arise log"))
            } ?: toast("No log yet")
        }
        actionRow("Reset all settings & voice profile", danger = true) {
            confirm("Reset all settings? This deletes local preferences and your voice profile (not phone data).") { yes ->
                if (yes) { settings.wipe(); engine.clearSpeakerProfile(); toast("Reset complete"); rebuild() }
            }
        }

        // ============ ACCOUNT ============
        addSection("Support subscription")
        val subRow = actionRow("Arise Support — check subscription") {
            billing?.launchFlow(this@SettingsActivity)
        }
        billing?.addCallback(object : BillingManager.Callback {
            override fun onState(billingOk: Boolean, purchased: Boolean, price: String?) {
                runOnUiThread {
                    subRow.text = when {
                        purchased -> "✓ Arise Support active — thank you!"
                        price != null -> "Arise Support · $price / month (tap to subscribe)"
                        else -> "Arise Support · pricing unavailable (no Play connection)"
                    }
                }
            }
        })

        // ============ ABOUT ============
        addSection("About")
        addNote("Arise is a lightweight hands-free Android assistant. It uses the wake word → understand → route → execute → verify → respond pipeline. You stay in control: every sensitive action asks first, nothing is ever done silently, and no recordings are uploaded.")
        actionRow("Privacy policy · support · terms") {
            val b = AlertDialog.Builder(this)
                .setTitle("Arise")
                .setMessage("Privacy: audio and screen content never leave your phone except the exact transcription you chose to send to your configured AI provider. Cancel any time — delete Arise and its files and nothing remains.\n\nSupport: build with the included docs, or raise an issue on GitHub.")
                .setPositiveButton("Close", null)
                .setNegativeButton("GitHub", { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ariseai/arise")))
                }).show()
            b.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFF38E1C6.toInt())
            b.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF7C5CFF.toInt())
        }
    }

    // ---------- behaviour helpers ----------

    private fun setHandsFree(on: Boolean) {
        settings.handsFreeEnabled = on
        settings.wakeWordEnabled = on
        if (on) {
            AriseForegroundService.startIfPermitted(this, engine)
            toast("Hands-free listening on — say “${settings.wakeWord}”")
        } else {
            AriseForegroundService.stop(this)
            engine.stopEverything()
            toast("Hands-free off")
        }
    }

    private suspend fun enroll() {
        runOnUiThread { toast("Speak naturally for about 4 seconds…") }
        val id = engine.enrollSpeakerNow(4.0)
        runOnUiThread {
            if (id >= 0) { toast("Voice profile created (id $id)"); rebuild() }
            else toast("Enrollment failed — say something clearly, or check the microphone permission")
        }
    }

    private val micPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (pendingEnroll) { pendingEnroll = false; lifecycleScopeLaunch { enroll() } }
                else toast("Microphone granted")
            } else toast("Microphone permission is required for voice features")
        }

    private fun lifecycleScopeLaunch(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }

    private fun requestWakePermissions(onDone: () -> Unit) {
        if (!micGranted()) { permReq = onDone; micPermLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
        if (android.os.Build.VERSION.SDK_INT >= 33 && !notifGranted()) { permReq = onDone; notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS); return }
        onDone()
    }

    private var permReq: (() -> Unit)? = null
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> permReq?.invoke(); permReq = null }

    private fun micGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun notifGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // ---------- primitives ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addSpace(h: Int) { root.addView(Space(this), LinearLayout.LayoutParams(1, dp(h))) }
    private fun addTitle(t: String) {
        root.addView(TextView(this).apply {
            text = t
            setTextColor(0xFFEEF1FF.toInt())
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        })
    }
    private fun addSub(t: String) {
        root.addView(TextView(this).apply {
            text = t
            setTextColor(0xFF9AA3C0.toInt())
            textSize = 12f
        }, marginBottom(2))
    }
    private fun addSection(t: String) {
        addSpace(18)
        root.addView(TextView(this).apply {
            text = t.uppercase(Locale.ROOT)
            setTextColor(0xFF38E1C6.toInt())
            textSize = 12f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
        }, marginBottom(6))
    }
    private fun addNote(t: String) {
        root.addView(TextView(this).apply {
            text = t
            setTextColor(0xFF6C7699.toInt())
            textSize = 11f
            setPadding(0, dp(4), 0, dp(2))
        })
    }

    private fun marginBottom(b: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(b)
    }

    private fun containerBg(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(14), dp(8), dp(14), dp(8))
        l.setBackgroundColor(0xFF0D1220.toInt())
        l.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF0D1220.toInt())
            cornerRadius = dp(14).toFloat()
        }
        l.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        }
        return l
    }

    private fun label(t: String, desc: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(TextView(this).apply {
            text = t; setTextColor(0xFFEEF1FF.toInt()); textSize = 14f
        })
        if (desc.isNotEmpty()) box.addView(TextView(this).apply {
            text = desc; setTextColor(0xFF7A86B5.toInt()); textSize = 11f
        })
        box.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        return box
    }

    private fun toggleRow(t: String, initial: Boolean, desc: String = "", onChange: (Boolean) -> Unit) {
        val row = containerBg()
        row.gravity = Gravity.CENTER_VERTICAL
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(label(t, desc))
        val sw = android.widget.Switch(this).apply { isChecked = initial }
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        val swLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        swLp.leftMargin = dp(12)
        row.addView(sw, swLp)
        root.addView(row)
    }

    private fun sliderRow(t: String, initial: Float, range: ClosedFloatingPointRange<Float>, step: Float, fmt: String, onChange: (Float) -> Unit) {
        val row = containerBg()
        row.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@SettingsActivity).apply { text = t; setTextColor(0xFFEEF1FF.toInt()); textSize = 13f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val valView = TextView(this@SettingsActivity).apply { setTextColor(0xFF38E1C6.toInt()); textSize = 12f }
            valView.text = String.format(java.util.Locale.ROOT, fmt, initial)
            addView(valView)
        }
        row.addView(head)
        val sb = SeekBar(this)
        val steps = ((range.endInclusive - range.start) / step).toInt()
        sb.max = steps
        sb.progress = ((initial - range.start) / step).toInt()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = range.start + progress * step
                head.getChildAt(1).let { (it as TextView).text = String.format(java.util.Locale.ROOT, fmt, v) }
                onChange(v)
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })
        row.addView(sb)
        root.addView(row)
    }

    private fun textRow(t: String, value: String, secret: Boolean = false, onSave: (String) -> Unit) {
        val row = containerBg()
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(label(t, if (secret) "Stored only on this device (encrypted)" else ""))
        row.setOnClickListener {
            val et = EditText(this).apply {
                setText(value)
                isSingleLine = true
                if (secret) { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
            }
            AlertDialog.Builder(this)
                .setTitle(t)
                .setView(et)
                .setPositiveButton("Save") { _, _ -> onSave(et.text.toString()) }
                .setNegativeButton("Cancel", null)
                .show().let { d -> d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF7C5CFF.toInt()) }
        }
        val cur = TextView(this).apply {
            text = value
            setTextColor(0xFF38E1C6.toInt())
            textSize = 12f
            gravity = Gravity.END
        }
        row.addView(cur, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        root.addView(row)
    }

    private fun chipsRow(t: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        val row = containerBg()
        row.orientation = LinearLayout.VERTICAL
        row.addView(TextView(this).apply { text = t; setTextColor(0xFFEEF1FF.toInt()); textSize = 13f }, marginBottom(6))
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        for ((key, labelText) in options) {
            val chip = TextView(this).apply {
                text = "  $labelText  "
                setTextSize(12f)
                gravity = Gravity.CENTER
                val selected = key == current
                setTextColor(if (selected) 0xFF070A14.toInt() else 0xFFEEF1FF.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (selected) 0xFF38E1C6.toInt() else 0xFF1B2440.toInt())
                }
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            chip.setOnClickListener { onPick(key); rebuild() }
            chips.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
        row.addView(chips)
        root.addView(row)
    }

    private fun permissionRow(t: String, perm: String, desc: String) {
        actionRow("$t — ${if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) "granted" else "not granted"}") {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                // direct system permission page
                try {
                    val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    startActivity(i)
                    return@actionRow
                } catch (_: Exception) {}
            }
            requestPermissions(arrayOf(perm), 100)
        }
    }

    private fun actionRow(t: String, danger: Boolean = false, onClick: (() -> Unit)? = null): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.setPadding(dp(16), dp(14), dp(16), dp(14))
        tv.setTextColor(if (danger) 0xFFFF5470.toInt() else 0xFF38E1C6.toInt())
        tv.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(0xFF11182C.toInt())
        }
        tv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(4) }
        if (onClick != null) tv.setOnClickListener { onClick() }
        root.addView(tv)
        return tv
    }

    private fun confirm(msg: String, onResult: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Are you sure?")
            .setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> onResult(true) }
            .setNegativeButton("Cancel") { _, _ -> onResult(false) }
            .show().let { d ->
                d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFFFF5470.toInt())
                d.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xFF9AA3C0.toInt())
            }
    }

    private fun rebuild() {
        root.removeAllViews()
        buildUi()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
