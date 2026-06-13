package com.vera.android.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.vera.android.audio.TtsManager
import com.vera.android.audio.VoiceSession
import com.vera.android.audio.VoiceState
import com.vera.android.audio.VeraForegroundService
import com.vera.android.data.api.VeraApi
import com.vera.android.data.buildHttpClient
import com.vera.android.data.prefs.SecurePrefs
import com.vera.android.data.ws.ServerMessage
import com.vera.android.data.ws.VeraWebSocket
import com.vera.android.system.AppLauncher
import com.vera.android.system.VeraMediaController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ChatMessage(val id: Long, val role: String, val text: String)

data class ActionRequest(
    val actionId: String,
    val tool: String,
    val summary: String,
    val timeoutSecs: Int,
)

data class MainUiState(
    val messages: List<ChatMessage> = emptyList(),
    val displayName: String = "",
    val isConnected: Boolean = false,
    val isTyping: Boolean = false,
    val voiceState: VoiceState = VoiceState.IDLE,
    val interimText: String = "",
    val pendingAction: ActionRequest? = null,
    val firstLogin: Boolean = false,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = SecurePrefs(app)
    private val http = buildHttpClient()
    private val api = VeraApi(http)
    val ws = VeraWebSocket(http)
    private val tts = TtsManager(app)
    private val appLauncher = AppLauncher(app)
    private val mediaController = VeraMediaController(app)

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private var nextId = 0L
    private var ttsEnabled = prefs.ttsEnabled
    private var ttsRate = prefs.ttsRate
    private var typingTimeoutJob: Job? = null

    val voiceSession = VoiceSession(
        context = app,
        onInterim = { text -> _ui.update { it.copy(interimText = text) } },
        onFinal = { text ->
            _ui.update { it.copy(interimText = "") }
            sendMessage(text)
        },
        onVadStart = { ws.sendVadStart() },
        onVadEnd = { ws.sendVadEnd() },
    )

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == VeraForegroundService.ACTION_WAKE_DETECTED) {
                // Wake word detected — start listening for user command
                if (voiceSession.state.value == VoiceState.IDLE) {
                    voiceSession.startListening()
                }
            }
        }
    }

    init {
        tts.init {}
        tts.onDone = {}
        collectWsMessages()
        val token = prefs.sessionToken
        if (token != null) {
            connectWs(token)
            startForegroundService(app)
        }
        LocalBroadcastManager.getInstance(app)
            .registerReceiver(wakeReceiver, IntentFilter(VeraForegroundService.ACTION_WAKE_DETECTED))
    }

    fun connectWs(token: String) = ws.connect(token)

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        addMessage("user", text)
        _ui.update { it.copy(isTyping = true, error = null) }
        ws.sendMessage(text)
        // Auto-clear typing after 45s if no response
        typingTimeoutJob?.cancel()
        typingTimeoutJob = viewModelScope.launch {
            delay(45_000)
            _ui.update { if (it.isTyping) it.copy(isTyping = false, error = "No response — check connection") else it }
        }
    }

    fun toggleVoice() {
        if (voiceSession.state.value == VoiceState.LISTENING) voiceSession.stopListening()
        else voiceSession.startListening()
    }

    fun dismissFirstLogin() = _ui.update { it.copy(firstLogin = false) }

    fun approveAction(actionId: String) {
        viewModelScope.launch {
            prefs.sessionToken?.let { api.approveAction(it, actionId) }
            _ui.update { it.copy(pendingAction = null) }
        }
    }

    fun rejectAction(actionId: String) {
        viewModelScope.launch {
            prefs.sessionToken?.let { api.rejectAction(it, actionId) }
            _ui.update { it.copy(pendingAction = null) }
        }
    }

    fun logout() {
        viewModelScope.launch {
            prefs.sessionToken?.let { api.logout(it) }
            ws.disconnect()
            prefs.clear()
        }
    }

    private fun startForegroundService(app: Application) {
        runCatching {
            app.startForegroundService(Intent(app, VeraForegroundService::class.java))
        }
    }

    private fun collectWsMessages() {
        viewModelScope.launch {
            ws.messages.collect { msg ->
                when (msg) {
                    is ServerMessage.Hello -> {
                        _ui.update { it.copy(isConnected = true, displayName = msg.displayName, firstLogin = msg.firstLogin) }
                    }
                    is ServerMessage.AssistantThinking -> {
                        addMessage("assistant", msg.text)
                        if (ttsEnabled) tts.speak(msg.text, ttsRate)
                    }
                    is ServerMessage.AssistantText -> {
                        typingTimeoutJob?.cancel()
                        _ui.update { it.copy(isTyping = false) }
                        addMessage("assistant", msg.text)
                        if (ttsEnabled) tts.speak(msg.text, ttsRate)
                    }
                    is ServerMessage.TtsCancel -> tts.stop()
                    is ServerMessage.ActionPending -> {
                        _ui.update { it.copy(pendingAction = ActionRequest(msg.actionId, msg.tool, msg.summary, msg.timeoutSecs)) }
                    }
                    is ServerMessage.ActionResolved -> _ui.update { it.copy(pendingAction = null) }
                    is ServerMessage.OpenUrl -> appLauncher.openUri(msg.url)
                    is ServerMessage.SetReminder -> appLauncher.scheduleReminder(msg.timeIso, msg.text)
                    is ServerMessage.MediaControl -> mediaController.execute(msg.action)
                    is ServerMessage.LaunchApp -> appLauncher.openUri(msg.uri)
                    is ServerMessage.Error -> {
                        typingTimeoutJob?.cancel()
                        _ui.update { it.copy(error = msg.message, isTyping = false) }
                    }
                }
            }
        }
        viewModelScope.launch {
            voiceSession.state.collect { vs ->
                _ui.update { it.copy(voiceState = vs) }
                if (vs == VoiceState.LISTENING) tts.stop()
            }
        }
    }

    private fun addMessage(role: String, text: String) {
        _ui.update { state -> state.copy(messages = state.messages + ChatMessage(nextId++, role, text)) }
    }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(wakeReceiver)
        voiceSession.stopListening()
        tts.destroy()
        ws.disconnect()
    }
}
