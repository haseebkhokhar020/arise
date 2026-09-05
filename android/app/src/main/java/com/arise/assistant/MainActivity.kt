package com.arise.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.arise.assistant.engine.AgentState
import com.arise.assistant.engine.AriseEngine
import com.arise.assistant.log.LocalLog
import com.arise.assistant.service.AriseForegroundService
import com.arise.assistant.settings.Settings
import com.arise.assistant.ui.ChatAdapter
import com.arise.assistant.util.humanizePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var engine: AriseEngine
    private lateinit var settings: Settings
    private lateinit var chatAdapter: ChatAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var uiJob: Job? = null
    private var confirmShowing = false

    private val micPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) engine.pressToTalk() else toast("Microphone permission is required for voice")
        }
    private val wakePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) enableHandsFree() else toast("Microphone permission is required for hands-free listening")
        }
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            enableHandsFree()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        engine = AriseApp.instance.engine
        settings = Settings(this)

        chatAdapter = ChatAdapter()
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chat_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = chatAdapter
        }
        val chatEmpty = findViewById<View>(R.id.chat_empty)
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chat_list)
            .addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (chatAdapter.consumeScroll()) scrollChat()
            }

        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btn_mic).setOnClickListener { onMicTap() }
        findViewById<View>(R.id.btn_send).setOnClickListener { sendText() }
        findViewById<View>(R.id.wake_pill).setOnClickListener { onWakeTap() }
        findViewById<View>(R.id.stop_pill).setOnClickListener {
            findViewById<View>(R.id.stop_pill).visibility = View.GONE
            engine.stopEverything()
        }
        findViewById<View>(R.id.confirm_yes).setOnClickListener { confirmResponse(true) }
        findViewById<View>(R.id.confirm_no).setOnClickListener { confirmResponse(false) }
        findViewById<EditText>(R.id.input_text).setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendText(); true } else false
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            // first-run hint; no hard gate until they use voice
        }
        updateWakePill()
    }

    override fun onStart() {
        super.onStart()
        uiJob = scope.launch {
            engine.ui.collect { st -> render(st) }
        }
        scope.launch {
            engine.chats.collect { list ->
                if (list.isEmpty()) {
                    findViewById<View>(R.id.chat_empty).visibility = View.VISIBLE
                } else {
                    findViewById<View>(R.id.chat_empty).visibility = View.GONE
                }
                chatAdapter.submit(list)
                scrollChat()
            }
        }
    }

    override fun onStop() {
        uiJob?.cancel()
        super.onStop()
    }

    private fun scrollChat() {
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chat_list)
        (rv.layoutManager as? LinearLayoutManager)?.let { lm ->
            if (chatAdapter.itemCount > 0) lm.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    private fun render(st: AriseEngine.UiState) {
        findViewById<TextView>(R.id.status_text).text = st.statusText
        findViewById<TextView>(R.id.task_text).let {
            if (st.taskLabel.isNotBlank()) {
                it.text = st.taskLabel; it.visibility = View.VISIBLE
            } else it.visibility = View.GONE
        }
        findViewById<TextView>(R.id.phase_badge).text = humanizePhase(st.phase.name)

        val logo = findViewById<com.arise.assistant.ui.AriseLogoView>(R.id.logo)
        logo.setPhase(st.phase)
        logo.setAudioLevel(st.audioLevel)

        val busy = st.agentState == AgentState.BUSY ||
            st.agentState == AgentState.CONFIRMATION ||
            st.agentState == AgentState.AWAITING_COMMAND
        findViewById<View>(R.id.stop_pill).visibility =
            if (busy || st.micInUse) View.VISIBLE else View.GONE

        val debug = findViewById<TextView>(R.id.debug_text)
        if (settings.debugEnabled) {
            val parts = mutableListOf<String>()
            if (st.latency.isNotBlank()) parts.add(st.latency)
            if (st.toolName.isNotBlank()) parts.add("tool: ${st.toolName} ${st.toolArgs}")
            if (st.currentApp.isNotBlank()) parts.add("app: ${st.currentApp}")
            debug.text = parts.joinToString("\n")
            debug.visibility = if (parts.isNotEmpty()) View.VISIBLE else View.GONE
        } else debug.visibility = View.GONE

        findViewById<TextView>(R.id.latency_label).let {
            if (settings.latencyLogging && st.latency.isNotBlank()) {
                it.text = st.latency.substringAfter("total ").substringBefore(" [")
                it.visibility = View.VISIBLE
            } else it.visibility = View.GONE
        }

        // confirmation card
        val card = findViewById<View>(R.id.confirm_card)
        val q = st.confirmQuestion
        if (q != null && !confirmShowing) {
            confirmShowing = true
            findViewById<TextView>(R.id.confirm_question).text = q
            card.visibility = View.VISIBLE
        } else if (q == null && confirmShowing) {
            confirmShowing = false
            card.visibility = View.GONE
        }
    }

    private fun confirmResponse(yes: Boolean) {
        findViewById<View>(R.id.confirm_card).visibility = View.GONE
        confirmShowing = false
        engine.answerConfirm(yes)
    }

    private fun onMicTap() {
        if (micPermitted()) engine.pressToTalk()
        else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun onWakeTap() {
        if (settings.handsFreeEnabled) {
            settings.handsFreeEnabled = false
            AriseForegroundService.stop(this)
            engine.stopEverything()
            updateWakePill()
        } else {
            if (!micPermitted()) { wakePermLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
            if (Build.VERSION.SDK_INT >= 33 && notifPermitted().not()) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            enableHandsFree()
        }
    }

    private fun enableHandsFree() {
        if (!micPermitted()) { toast("Microphone permission required"); return }
        settings.handsFreeEnabled = true
        settings.wakeWordEnabled = true
        AriseForegroundService.startIfPermitted(this, engine)
        LocalLog.i("Main", "hands-free enabled")
        updateWakePill()
    }

    private fun updateWakePill() {
        val on = settings.handsFreeEnabled
        findViewById<TextView>(R.id.wake_text).text =
            if (on) "Listening for “${settings.wakeWord}”" else "Hands-free off — tap to enable"
    }

    private fun sendText() {
        val et = findViewById<EditText>(R.id.input_text)
        val t = et.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        et.setText("")
        if (settings.handsFreeEnabled.not() && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            // engine may still TTS replies; fine without wake listener
        }
        engine.sendManualTextCommand(t)
    }

    private fun micPermitted(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun notifPermitted(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
