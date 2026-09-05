package com.arise.assistant.engine

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import com.arise.assistant.ai.AiResult
import com.arise.assistant.ai.AIProvider
import com.arise.assistant.ai.ModelTier
import com.arise.assistant.ai.OpenAiCompatibleClient
import com.arise.assistant.audio.AudioAnalyzer
import com.arise.assistant.audio.RecordSample
import com.arise.assistant.audio.WakeWordDetector
import com.arise.assistant.intent.IntentParser
import com.arise.assistant.log.LocalLog
import com.arise.assistant.settings.Settings
import com.arise.assistant.speaker.SpeakerVerifier
import com.arise.assistant.speech.SpeechCapture
import com.arise.assistant.speech.SpeechManager
import com.arise.assistant.tools.AccessExecutors
import com.arise.assistant.tools.AgentContext
import com.arise.assistant.tools.InfoExecutors
import com.arise.assistant.tools.MessagingExecutors
import com.arise.assistant.tools.NativeExecutors
import com.arise.assistant.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * AriseEngine — single state machine for the whole assistant lifecycle:
 * SLEEP → WAKE → (verify) → ACK → LISTEN → UNDERSTAND → ROUTE → EXECUTE →
 * VERIFY → RESPOND → back to follow-up or SLEEP.
 *
 * All mutations run on the Main dispatcher. Long blocking work (network, own-mic
 * capture, gestures) hops off and back. Only lightweight audio gating runs while
 * idle so battery stays low.
 */
class AriseEngine(private val appContext: Context) {

    private val settings = Settings(appContext)
    private val parser = IntentParser()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val capture = SpeechCapture(appContext)
    private val speech: SpeechManager
    val verifier = SpeakerVerifier(appContext, settings)
    private var ai: AIProvider? = null

    // ---------------- observable UI state ----------------
    data class UiState(
        val phase: ArisePhase = ArisePhase.IDLE,
        val agentState: AgentState = AgentState.SLEEPING,
        val statusText: String = "Say “${Settings.DEFAULT_WAKE_WORD}”…",
        val micInUse: Boolean = false,
        val wakeListening: Boolean = false,
        val taskLabel: String = "",
        val toolName: String = "",
        val toolArgs: String = "",
        val toolResult: String = "",
        val currentApp: String = "",
        val error: String = "",
        val confirmQuestion: String? = null,
        val latency: String = "",
        val audioLevel: Float = 0f
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _chats = MutableStateFlow<List<ChatItem>>(emptyList())
    val chats: StateFlow<List<ChatItem>> = _chats.asStateFlow()

    @Volatile private var wake: WakeWordDetector? = null
    @Volatile private var awaitingWake = false
    @Volatile private var busy = false          // wake-word suppressed while busy
    @Volatile private var micState = false

    private var execJob: Job? = null
    private var followUpJob: Job? = null
    private var confirmDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private var currentIntent: ParsedIntent? = null
    private val latency = LatencyReport()

    init {
        ToolRegistry.registerAll()
        speech = SpeechManager(appContext, settings)
        speech.callback = object : SpeechManager.Callback {
            override fun onTtsReady() { }
            override fun onPhaseChange(phase: ArisePhase) {
                if (phase == ArisePhase.SPEAKING) patch { copy(phase = ArisePhase.SPEAKING) }
                else patch { if (ui.value.phase == ArisePhase.SPEAKING) copy(phase = ArisePhase.IDLE) else this }
            }
        }
        updatePhase(ArisePhase.IDLE, AgentState.SLEEPING)
        if (settings.aiEndpoint.isNotBlank()) ai = OpenAiCompatibleClient(appContext, settings)
        LocalLog.i("Engine", "engine ready on ${Thread.currentThread().name}")
    }

    // ---------------- public control ----------------

    /** Start (or keep) the low-power wake-word listener. Call from the foreground service. */
    fun startWakeListener() {
        if (busy) return
        if (!settings.wakeWordEnabled || !settings.handsFreeEnabled) {
            patch { copy(wakeListening = false, statusText = "Wake word is off") }
            return
        }
        if (wake == null) startWakeThread()
        patch { copy(wakeListening = true, statusText = "Listening for “${settings.wakeWord}”") }
    }

    /** Force a fresh listener thread (used when leaving busy/attempt states). */
    private fun restartWakeListener() {
        wake?.stop()
        wake = null
        if (settings.wakeWordEnabled && settings.handsFreeEnabled && !busy) {
            startWakeThread()
            patch { copy(wakeListening = true, statusText = "Listening for “${settings.wakeWord}”") }
        }
    }

    private fun startWakeThread() {
        wake = WakeWordDetector(appContext, matcher = object : WakeWordDetector.WakeMatcher {
            override fun isAwake(): Boolean = busy || awaitingWake || micState
            override fun onSpeechCandidate() { scope.launch { onPotentialWakePhrase() } }
        }).also { it.start() }
    }

    fun stopWakeListener() {
        wake?.stop()
        wake = null
        patch { copy(wakeListening = false, statusText = "Wake word off") }
    }

    fun isMicActive(): Boolean = micState

    /** Manual "tap to talk" — skips the wake word. */
    fun pressToTalk() {
        if (!hasMicPermission()) {
            onMicPermissionMissing()
            return
        }
        scope.launch {
            stopCaptureIfAny()
            if (micState) return@launch
            awaitingWake = false
            wakeToAwakeSequence()
        }
    }

    fun stopEverything() {
        scope.launch {
            execJob?.cancel()
            followUpJob?.cancel()
            confirmDeferred?.complete(false)
            stopCaptureIfAny()
            stopOwnMic()
            cancelTts()
            busy = false
            awaitingWake = false
            addChat(ChatItem("assistant", "Stopped."))
            updatePhase(ArisePhase.IDLE, AgentState.SLEEPING)
            patch { copy(statusText = "Listening for “${settings.wakeWord}”") }
            restartWakeListener()
        }
    }

    fun answerConfirm(yes: Boolean) {
        confirmDeferred?.complete(yes)
        confirmDeferred = null
    }

    // ---------------- wake path ----------------

    private suspend fun onPotentialWakePhrase() {
        if (busy || awaitingWake || micState) return
        if (!hasMicPermission()) { onMicPermissionMissing(); return }
        awaitingWake = true
        patch { copy(phase = ArisePhase.LISTENING, statusText = "Listening for the wake word…") }
        val started = startCapture(endOfSpeech = 1100, partials = true, object : SpeechCapture.Callback {
            override fun onPartial(text: String) {
                if (text.lowercase().contains(settings.wakeWord.lowercase()) && busy.not()) {
                    scope.launch { wakeMatched() }
                }
            }
            override fun onResult(text: String) {
                if (busy) return
                if (text.lowercase().contains(settings.wakeWord.lowercase())) scope.launch { wakeMatched() }
                else scope.launch { wakeMissed() }
            }
            override fun onRmsDb(db: Float) { patch { copy(audioLevel = levelFromDb(db)) } }
            override fun onError(code: Int, message: String) {
                scope.launch {
                    if (busy) return@launch
                    LocalLog.i("Wake", "attempt error $message")
                    awaitingWake = false
                    micState = false
                    patch { copy(micInUse = false) }
                    updatePhase(ArisePhase.IDLE, AgentState.SLEEPING)
                    delay(300)
                    restartWakeListener()
                }
            }
            override fun onTimeout() {}
        })
        if (!started) {
            awaitingWake = false
            micState = false
            patch { copy(micInUse = false) }
            restartWakeListener()
        }
    }

    private suspend fun wakeMatched() {
        awaitingWake = false
        busy = true
        latency.mark("start"); latency.mark("wake")
        LocalLog.i("Engine", "wake word matched")
        stopCaptureIfAny() // release the mic from the wake-word recognizer
        patch { copy(phase = ArisePhase.WAKING, statusText = "Wake word heard", audioLevel = 0f, micInUse = false) }
        delay(60)
        // optional speaker verification (convenience layer only — never a secure gate)
        if (settings.speakerVerifyEnabled && verifier.isEnrolled()) {
            updatePhase(ArisePhase.VERIFYING, AgentState.BUSY, status = "Checking your voice…")
            patch { copy(micInUse = true) }
            delay(120)
            val frames = RecordSample.recordFrames(1.4, onLevel = { patch { copy(audioLevel = it) } })
            patch { copy(micInUse = false, audioLevel = 0f) }
            val v = verifier.verify(frames)
            if (!v.accepted) {
                speech.speak("Sorry, I didn't recognize your voice.")
                addChat(ChatItem("assistant", "Speaker check failed (score ${"%.2f".format(v.score)})."))
                finishToSleep()
                return
            }
        }
        wakeToAwakeSequence()
    }

    private fun wakeMissed() = scope.launch {
        LocalLog.i("Wake", "not a wake word — back to sleep")
        awaitingWake = false
        busy = false
        micState = false
        updatePhase(ArisePhase.IDLE, AgentState.SLEEPING)
        patch { copy(micInUse = false, statusText = "Listening for “${settings.wakeWord}”") }
        delay(250)
        restartWakeListener()
    }

    private suspend fun wakeToAwakeSequence() {
        busy = true
        updatePhase(ArisePhase.ACK, AgentState.AWAITING_COMMAND, status = "Listening…")
        if (settings.ttsSayWakeReply && settings.ttsEnabled) speech.speak("Yes?")
        else patch { copy(phase = ArisePhase.LISTENING) }
        addChat(ChatItem("assistant", "Yes?"))
        // wait out our own "Yes?" so it is never heard back as the command
        waitForSpeechEnd()
        if (settings.ttsEnabled) delay(250)
        listenForCommand(followUp = false)
    }

    /** Polls the TTS engine so a spoken prompt never leaks into the next capture. */
    private suspend fun waitForSpeechEnd() {
        var waited = 0
        while (speech.isSpeaking() && waited < 4000) { delay(100); waited += 100 }
    }

    // ---------------- command listening ----------------

    private fun listenForCommand(followUp: Boolean) {
        if (!hasMicPermission()) { onMicPermissionMissing(); return }
        micState = true
        patch { copy(micInUse = true, phase = ArisePhase.LISTENING, agentState = AgentState.AWAITING_COMMAND,
            statusText = if (followUp) "Anything else?" else "Listening…") }
        val ok = startCapture(settings.endOfSpeechMs, partials = false, object : SpeechCapture.Callback {
            override fun onPartial(text: String) { addLive(text) }
            override fun onResult(text: String) { scope.launch { onCommandText(text, followUp) } }
            override fun onRmsDb(db: Float) { patch { copy(audioLevel = levelFromDb(db)) } }
            override fun onError(code: Int, message: String) {
                scope.launch {
                    if (code == android.speech.SpeechRecognizer.ERROR_NO_MATCH || code == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        if (followUp) { finishToSleep(); return@launch }
                        patch { copy(error = "I didn't catch that — try again", phase = ArisePhase.ERROR) }
                        addChat(ChatItem("system", "No command heard."))
                        speech.speak("Sorry, I didn't catch that.")
                        delay(600); finishToSleep()
                    } else {
                        handleCaptureError(code, message)
                    }
                }
            }
            override fun onTimeout() {}
        })
        if (!ok) handleCaptureError(0, "no recognizer")
        if (followUp) scheduleFollowUpTimeout()
    }

    private fun addLive(text: String) {
        patch { copy(statusText = "“$text”…") }
    }

    private fun scheduleFollowUpTimeout() {
        followUpJob?.cancel()
        followUpJob = scope.launch {
            delay(settings.followUpTimeoutSec * 1000L)
            if (micState && ui.value.agentState == AgentState.AWAITING_COMMAND) {
                stopCaptureIfAny()
                LocalLog.i("Engine", "follow-up window ended")
                finishToSleep()
            }
        }
    }

    private suspend fun onCommandText(text: String, followUp: Boolean) {
        micState = false
        patch { copy(micInUse = false, audioLevel = 0f) }
        latency.mark("stt")
        val trimmed = text.trim()
        if (trimmed.isEmpty()) { finishToSleep(); return }
        addChat(ChatItem("user", trimmed))
        routeText(trimmed)
    }

    private fun handleCaptureError(code: Int, message: String) {
        scope.launch {
            stopCaptureIfAny()
            patch { copy(error = message, phase = ArisePhase.ERROR) }
            addChat(ChatItem("system", "Speech error: $message"))
            speech.speak("Sorry, the speech service isn't available right now.")
            delay(500)
            finishToSleep()
        }
    }

    // ---------------- routing ----------------

    private suspend fun routeText(text: String) {
        if (isKeyguardLocked()) {
            // never bypass the lock screen — only safe read-only answers while locked
            val intent = parser.parse(text)
            if (intent?.intent == "stop" || intent?.intent == "sleep") { finishToSleep(); return }
            speech.speak("Your phone is locked. Unlock it and say the command again.")
            addChat(ChatItem("system", "Phone locked — action refused (Arise never bypasses the lock screen)."))
            delay(400)
            finishToSleep()
            return
        }
        val intent = parser.parse(text)
        currentIntent = intent
        when (intent?.intent) {
            "stop", "sleep" -> {
                speech.speak(if (intent.intent == "stop") "Stopping." else "Going to sleep.")
                finishToSleep()
            }
            "ask_message" -> {
                val contact = intent.params["contact"] ?: return finishToSleep()
                speech.speak("What should I tell $contact?")
                askForMessageThenSend(contact)
            }
            else -> executeIntent(intent ?: ParsedIntent("unknown", needsModel = true, rawText = text), text)
        }
    }

    private suspend fun askForMessageThenSend(contact: String) {
        updatePhase(ArisePhase.LISTENING, AgentState.AWAITING_COMMAND, status = "What should I say?")
        val ok = startCapture(settings.endOfSpeechMs, false, object : SpeechCapture.Callback {
            override fun onPartial(text: String) {}
            override fun onResult(text: String) { scope.launch { sendWhatsAppWith(contact, text) } }
            override fun onRmsDb(db: Float) { patch { copy(audioLevel = levelFromDb(db)) } }
            override fun onError(code: Int, m: String) { scope.launch { finishToSleep() } }
            override fun onTimeout() {}
        })
        if (!ok) finishToSleep()
    }

    private suspend fun sendWhatsAppWith(contact: String, message: String) {
        addChat(ChatItem("user", message))
        val intent = ParsedIntent("send_whatsapp_message", params = mapOf("contact" to contact, "message" to message), rawText = message)
        executeIntent(intent, message)
    }

    private suspend fun executeIntent(intent: ParsedIntent, rawText: String) {
        updatePhase(ArisePhase.PROCESSING, AgentState.BUSY, status = "Working…")
        val r = route(intent, rawText)
        when (r) {
            is RouteResult.ToolCalls -> executeTools(r.toolName, r.args)
            is RouteResult.Cloud -> handleCloud(rawText, r.power)
            is RouteResult.Replies -> speakReply(r.text, intent)
            is RouteResult.NoOp -> finishToSleep()
        }
    }

    private sealed class RouteResult {
        data class ToolCalls(val toolName: String, val args: Map<String, String>) : RouteResult()
        data class Cloud(val power: Boolean = false) : RouteResult()
        data class Replies(val text: String) : RouteResult()
        object NoOp : RouteResult()
    }

    private fun route(intent: ParsedIntent, rawText: String): RouteResult {
        val p = intent.params
        fun tool(name: String, args: Map<String, String>) = RouteResult.ToolCalls(name, args)
        return when (intent.intent) {
            "open_app" -> if (p["app"] != null) tool("open_app", p) else RouteResult.Replies("Which app would you like me to open?")
            "close_app" -> tool("close_app", p)
            "go_home" -> tool("go_home", emptyMap())
            "press_back" -> tool("press_back", emptyMap())
            "launch_url" -> if (p["url"] != null) tool("launch_url", p) else RouteResult.Cloud()
            "search_web" -> if (p["query"] != null) tool("search_web", p) else RouteResult.Cloud()
            "get_current_app" -> tool("get_current_app", emptyMap())
            "open_notifications" -> tool("open_notifications", emptyMap())
            "take_screenshot" -> tool("take_screenshot", emptyMap())
            "read_screen" -> tool("read_screen", emptyMap())
            "click_on_text" -> if (p["label"] != null) tool("click", mapOf("text" to p["label"]!!)) else RouteResult.Cloud()
            "scroll" -> tool("scroll", p)
            "send_whatsapp_message" -> tool("send_whatsapp_message", p)
            "send_sms" -> tool("send_sms", p)
            "send_email" -> tool("send_email", p)
            "call_contact" -> tool("call_contact", p)
            "control_media" -> tool("control_media", p)
            "change_volume" -> tool("change_volume", p)
            "control_flashlight" -> tool("control_flashlight", p)
            "set_alarm" -> tool("set_alarm", p)
            "set_timer" -> tool("set_alarm", p) // clock handles timers via same intent screen fallback
            "get_time" -> tool("get_time", emptyMap())
            "device_status" -> tool("device_status", p)
            "get_weather" -> {
                val place = p["place"] ?: rawText.replace(Regex(".*\\b(?:in|at|for)\\s+([a-z ]+?)\\s*$", RegexOption.IGNORE_CASE)) { it.groupValues[1] }
                if (place.isNotBlank()) tool("get_weather", mapOf("place" to place.trim())) else RouteResult.Cloud()
            }
            "delete_content", "financial_action", "factory_reset" ->
                RouteResult.Replies("I can't do that on your behalf — it's a sensitive action and Android rightly keeps that control with you. You can do it in the app directly.")
            "greeting" -> RouteResult.Replies(randomGreeting())
            "acknowledge" -> RouteResult.Replies("Anytime.")
            "unknown", "ask_question" -> RouteResult.Cloud(power = intent.intent == "ask_question" && rawText.length > 60)
            else -> RouteResult.Cloud(power = intent.needsModel)
        }
    }

    private fun randomGreeting(): String = listOf("Hello!", "Hi there.", "How can I help?")[kotlin.random.Random.nextInt(3)]

    // ---------------- execution ----------------

    private suspend fun executeTools(toolName: String, args: Map<String, String>) {
        latency.mark("exec")
        val ctx = AgentContext(
            ctx = appContext, settings = settings, verifier = verifier,
            progress = { t -> patch { copy(statusText = t, toolName = toolName) } },
            confirm = { question, timeoutSec -> confirmUi(question, timeoutSec) },
            announce = { speech.speakIfFree(it) }
        )
        patch { copy(taskLabel = humanTask(toolName, args), toolName = toolName, toolArgs = args.entries.joinToString(", ") { "${it.key}=${it.value}" }) }
        addChat(ChatItem("system", "→ $toolName", meta = args.entries.joinToString(", ")))
        val result = ToolRegistry.execute(ctx, toolName, args)
        latency.mark("verify")
        val actionVerification = if (result.verified) "verified" else "unverified"
        addChat(ChatItem("assistant", result.detail, meta = "status=${result.status} ($actionVerification)", toolResult = result))
        patch {
            copy(toolResult = result.detail, statusText = result.detail,
                phase = when (result.status) {
                    ToolStatus.SUCCESS -> ArisePhase.SUCCESS
                    ToolStatus.FAILED, ToolStatus.ABORTED -> ArisePhase.ERROR
                    else -> ArisePhase.PROCESSING
                })
        }
        if (settings.latencyLogging) latency.mark("response")
        val voice = voiceFor(result)
        latencyReportDone()
        when (result.status) {
            ToolStatus.SUCCESS -> speech.speak(voice)
            ToolStatus.PARTIAL -> speech.speak(speakable(result.detail))
            ToolStatus.NO_PERMISSION, ToolStatus.BLOCKED -> {
                speech.speak(speakable(result.detail))
                addChat(ChatItem("system", result.detail))
            }
            ToolStatus.FAILED, ToolStatus.ABORTED -> speech.speak("Sorry, " + speakable(result.detail))
            else -> speech.speak(speakable(result.detail))
        }
        if (settings.followUpTimeoutSec > 0) { delay(700); listenForFollowUp() } else finishToSleepSoon()
    }

    private fun listenForFollowUp() {
        if (settings.followUpTimeoutSec <= 0) { finishToSleep(); return }
        scope.launch {
            addChat(ChatItem("assistant", "Anything else?"))
            speech.speakIfFree("Anything else?")
            waitForSpeechEnd()
            if (settings.ttsEnabled) delay(250)
            updatePhase(ArisePhase.LISTENING, AgentState.AWAITING_COMMAND, status = "Anything else?")
            listenForCommand(followUp = true)
        }
    }

    private suspend fun speakReply(text: String, intent: ParsedIntent) {
        latency.mark("response")
        latencyReportDone()
        addChat(ChatItem("assistant", text))
        speech.speak(text)
        patch { copy(phase = ArisePhase.SUCCESS, statusText = text) }
        if (settings.followUpTimeoutSec > 0) {
            waitForSpeechEnd()
            if (settings.ttsEnabled) delay(250)
            listenForFollowUp()
        } else finishToSleepSoon()
    }

    private fun voiceFor(r: ToolResult): String {
        // Keep replies short & spoken: "Opened YouTube." / "Message sent to Ali."
        var v = r.detail
        v = v.replace("—", "—").replace("…", ". ").replace(Regex("^Opened |^Pressed "), "")
        return v.trim().removePrefix(".")
    }

    private fun speakable(t: String) = t.replace(Regex("[\\[\\]{}<>|]"), "")

    private suspend fun handleCloud(text: String, power: Boolean) {
        updatePhase(ArisePhase.PROCESSING, AgentState.BUSY, status = "Thinking…")
        latency.mark("ai")
        val provider = ai ?: run {
            speech.speak("Cloud AI isn't configured. Add an AI provider in Settings, or ask me something I can do on the phone.")
            finishToSleep()
            return
        }
        if (!provider.isConfigured()) {
            speech.speak("I need an AI provider configured for that. It's in Settings under AI provider.")
            finishToSleep()
            return
        }
        val tier = if (!power) ModelTier.FAST else ModelTier.POWER
        val res = provider.complete(
            system = cloudSystemPrompt(tier),
            turns = listOf(com.arise.assistant.ai.ChatTurn("user", text)),
            tier = tier,
            temperature = settings.aiTemperature
        )
        latency.mark("ai_done")
        if (!res.ok) {
            patch { copy(error = res.error, phase = ArisePhase.ERROR) }
            addChat(ChatItem("system", "AI error: ${res.error}"))
            speech.speak("I couldn't reach the cloud assistant. Please check your connection.")
            finishToSleepSoon()
            return
        }
        handleAiReply(res, text)
    }

    private suspend fun handleAiReply(res: AiResult, original: String) {
        val json = OpenAiCompatibleClient.extractJson(res.text)
        val reply: String?
        val toolCalls = mutableListOf<Pair<String, Map<String, String>>>()
        if (json != null) {
            reply = json.optString("reply").takeIf { it.isNotBlank() }
            val arr = json.optJSONArray("tools")
            if (arr != null) for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val name = t.optString("tool")
                if (name.isBlank()) continue
                val args = mutableMapOf<String, String>()
                t.optJSONObject("args")?.keys()?.forEach { k -> args[k] = t.optJSONObject("args").optString(k) }
                toolCalls.add(name to args)
            }
        } else reply = res.text.trim()
        addChat(ChatItem("assistant", reply ?: original, meta = "model=${res.model}"))
        latency.mark("ai_parsed")
        if (toolCalls.isEmpty()) {
            if (!reply.isNullOrBlank()) {
                speech.speak(reply)
                addChat(ChatItem("assistant", reply))
            } else {
                speech.speak("I didn't get a useful answer from the model.")
            }
            if (settings.followUpTimeoutSec > 0) { delay(500); listenForFollowUp() } else finishToSleepSoon()
        } else {
            var lastGood = ""
            var allGood = true
            for ((name, args) in toolCalls) {
                val r = ToolRegistry.execute(
                    AgentContext(appContext, settings, verifier,
                        { t -> patch { copy(statusText = t, toolName = name) } },
                        { q, s -> confirmUi(q, s) }, { speech.speakIfFree(it) }),
                    name, args)
                lastGood = r.detail
                addChat(ChatItem("assistant", r.detail, meta = "tool=$name status=${r.status}", toolResult = r))
                if (r.status == ToolStatus.FAILED || r.status == ToolStatus.ABORTED) { allGood = false; break }
            }
            latency.mark("response")
            latencyReportDone()
            speech.speak(speakable(lastGood))
            if (allGood && reply.isNullOrBlank().not()) { /* model explained above */ }
            if (settings.followUpTimeoutSec > 0) { delay(500); listenForFollowUp() } else finishToSleepSoon()
        }
    }

    private fun cloudSystemPrompt(tier: ModelTier): String = buildString {
        appendLine("You are Arise, a concise hands-free Android assistant. Today is ${java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(java.util.Date())}.")
        appendLine("Reply in one short spoken sentence (under 22 words).")
        appendLine("If the request needs an action on the phone, return ONLY JSON: {\"reply\":\"one short status line\",\"tools\":[{\"tool\":\"<name>\",\"args\":{...}}]}")
        if (tier == ModelTier.POWER) appendLine(ToolRegistry.systemPromptSection())
        appendLine("Only use listed tools with valid args. Never invent params. If you cannot help, return {\"reply\":\"<short honest answer>\"}.")
        appendLine("Rules: never claim something was done unless a tool verified it; refuse harmful/private-data requests politely.")
    }

    // ---------------- confirmation ----------------

    private suspend fun confirmUi(question: String, timeoutSec: Int): Boolean {
        if (confirmDeferred != null) return false
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        confirmDeferred = d
        updatePhase(ArisePhase.CONFIRMING, AgentState.CONFIRMATION, status = question)
        patch { copy(confirmQuestion = question) }
        speech.speak(question)
        addChat(ChatItem("assistant", question))
        // listen for a spoken yes/no
        startCapture(1500, true, object : SpeechCapture.Callback {
            override fun onPartial(text: String) { resolveYesNo(text, d) }
            override fun onResult(text: String) { resolveYesNo(text, d) }
            override fun onRmsDb(db: Float) {}
            override fun onError(code: Int, m: String) { if (d.isActive) d.complete(false) }
            override fun onTimeout() {}
        })
        val timeoutMs = (timeoutSec * 1000L).coerceAtLeast(4000)
        val answer = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { d.await() } ?: false
        confirmDeferred = null
        patch { copy(confirmQuestion = null) }
        return answer
    }

    private fun resolveYesNo(text: String, d: kotlinx.coroutines.CompletableDeferred<Boolean>) {
        if (!d.isActive) return
        val t = text.lowercase()
        when {
            t.contains("no") || t.contains("nope") || t.contains("cancel") || t.contains("don't") || t.contains("dont") -> d.complete(false)
            t.contains("yes") || t.contains("yeah") || t.contains("sure") || t.contains("yep") || t.contains("go ahead") || t.contains("ok") || t.contains("send") -> d.complete(true)
            else -> Unit // keep listening
        }
    }

    // ---------------- misc engine helpers ----------------

    private fun humanTask(tool: String, args: Map<String, String>): String = when (tool) {
        "send_whatsapp_message" -> "Message ${args["contact"]} on WhatsApp"
        "open_app" -> "Open ${args["app"]}"
        "search_web" -> "Search ${args["query"]}"
        else -> tool.replace('_', ' ')
    }

    private fun levelFromDb(db: Float) = ((db + 50f) / 50f).coerceIn(0f, 1f)

    private fun finishToSleep() {
        scope.launch {
            stopCaptureIfAny()
            busy = false
            updatePhase(ArisePhase.IDLE, AgentState.SLEEPING)
            patch { copy(micInUse = false, confirmQuestion = null, audioLevel = 0f) }
            delay(150)
            restartWakeListener()
        }
    }

    private fun finishToSleepSoon() {
        scope.launch {
            delay(800)
            if (!micState && ui.value.agentState != AgentState.AWAITING_COMMAND) finishToSleep()
        }
    }

    private fun updatePhase(p: ArisePhase, s: AgentState, status: String? = null) {
        patch {
            copy(phase = p, agentState = s,
                statusText = status ?: statusText,
                error = if (p == ArisePhase.ERROR) error else "")
        }
    }

    private fun patch(mut: UiState.() -> UiState) {
        _ui.value = _ui.value.mut()
    }

    private fun addChat(item: ChatItem) {
        val cur = _chats.value.toMutableList()
        cur.add(item)
        if (cur.size > 80) cur.removeAt(0)
        _chats.value = cur
    }

    private fun latencyReportDone() {
        if (!settings.latencyLogging) return
        val total = latency.totalMs()
        val breakdown = StringBuilder()
        latency.events.forEach { (k, _) -> breakdown.append(k).append(" ") }
        val summary = "total ${total}ms [$breakdown]"
        patch { copy(latency = summary) }
        addChat(ChatItem("debug", "latency: $summary"))
        LocalLog.i("Latency", summary)
    }

    private fun hasMicPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun onMicPermissionMissing() {
        addChat(ChatItem("system", "Microphone permission is required for voice. Grant it in Arise permissions."))
        patch { copy(error = "Microphone permission needed", phase = ArisePhase.ERROR) }
        speech.speak("I need microphone permission to hear you. You can grant it in Settings.")
    }

    private fun isKeyguardLocked(): Boolean {
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }

    // ---------------- capture plumbing ----------------

    private fun startCapture(endOfSpeech: Int, partials: Boolean, cb: SpeechCapture.Callback): Boolean {
        if (!hasMicPermission()) { onMicPermissionMissing(); return false }
        if (micState) return false // recognizer already holds the mic
        micState = true
        patch { copy(micInUse = true) }
        capture.start(endOfSpeech, partials, cb)
        return true
    }

    private fun stopCaptureIfAny() {
        if (!micState) return
        micState = false
        capture.stop(false)
        patch { copy(micInUse = false) }
    }

    private fun stopOwnMic() { /* AudioRecord owned by RecordSample is released in its finally */ }

    private fun cancelTts() { speech.stop() }

    // ---------------- programmatic & enrollment entry points ----------------

    /** Send a typed command (UI text box) through the exact same pipeline as voice. */
    fun sendManualTextCommand(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        scope.launch {
            if (busy) { addChat(ChatItem("system", "I'm busy with another task — wait a moment.")); return@launch }
            awaitingWake = false
            busy = true
            stopCaptureIfAny()
            latency.mark("start"); latency.mark("wake"); latency.mark("stt")
            addChat(ChatItem("user", t))
            routeText(t)
        }
    }

    /** Enroll the speaker profile from live audio (Settings → speaker verification). */
    suspend fun enrollSpeakerNow(seconds: Double = 4.0): Int {
        val heldBusy = busy
        busy = true
        wake?.stop(); wake = null
        stopCaptureIfAny()
        patch { copy(micInUse = true, phase = ArisePhase.VERIFYING, statusText = "Recording your voice… please speak normally") }
        val frames = RecordSample.recordFrames(seconds, onLevel = { patch { copy(audioLevel = it) } })
        patch { copy(micInUse = false, audioLevel = 0f) }
        val id = verifier.enroll(frames)
        busy = heldBusy
        if (!heldBusy) restartWakeListener()
        return id
    }

    /** Quick live check against the enrolled profile (Settings → verify). */
    suspend fun verifySpeakerNow(): SpeakerVerifier.Result {
        val heldBusy = busy
        busy = true
        wake?.stop(); wake = null
        stopCaptureIfAny()
        patch { copy(micInUse = true, phase = ArisePhase.VERIFYING, statusText = "Listening…") }
        val frames = RecordSample.recordFrames(2.0, onLevel = { patch { copy(audioLevel = it) } })
        patch { copy(micInUse = false, audioLevel = 0f) }
        val result = verifier.verify(frames)
        busy = heldBusy
        if (!heldBusy) restartWakeListener()
        return result
    }

    fun clearSpeakerProfile() {
        verifier.deleteProfile()
        patch { copy(statusText = "Speaker profile deleted") }
    }

    fun isSpeakerEnrolled(): Boolean = verifier.isEnrolled()

    /** Releases recognizer etc. Called from Application teardown only. */
    fun shutdown() {
        scope.launch {
            execJob?.cancel()
            followUpJob?.cancel()
            stopCaptureIfAny()
            wake?.stop()
            speech.shutdown()
        }
    }
}

// one-time registration of every executor so engine + tests share the registry
fun ToolRegistry.registerAll() {
    val specs = NativeExecutors.all() + AccessExecutors.all() + MessagingExecutors.all() + InfoExecutors.all()
    for (s in specs) register(s)
}