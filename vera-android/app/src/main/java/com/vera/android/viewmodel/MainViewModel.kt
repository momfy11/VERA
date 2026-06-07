package com.vera.android.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vera.android.audio.TtsManager
import com.vera.android.audio.VoiceSession
import com.vera.android.audio.VoiceState
import com.vera.android.data.api.VeraApi
import com.vera.android.data.prefs.SecurePrefs
import com.vera.android.data.ws.ServerMessage
import com.vera.android.data.ws.VeraWebSocket
import com.vera.android.system.AppLauncher
import com.vera.android.system.VeraMediaController
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

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
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = SecurePrefs(app)
    private val http = OkHttpClient()
    private val api = VeraApi(http)
    private val ws = VeraWebSocket(http)
    private val tts = TtsManager(app)
    private val appLauncher = AppLauncher(app)
    private val mediaController = VeraMediaController(app)

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private var nextId = 0L
    private var ttsEnabled = prefs.ttsEnabled
    private var ttsRate = prefs.ttsRate

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

    init {
        tts.init {}
        tts.onDone = {}
        collectWsMessages()

        val token = prefs.sessionToken
        if (token != null) connectWs(token)
    }

    fun connectWs(token: String) {
        ws.connect(token)
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        addMessage("user", text)
        _ui.update { it.copy(isTyping = true) }
        ws.sendMessage(text)
    }

    fun toggleVoice() {
        if (voiceSession.state.value == VoiceState.LISTENING) {
            voiceSession.stopListening()
        } else {
            voiceSession.startListening()
        }
    }

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

    private fun collectWsMessages() {
        viewModelScope.launch {
            ws.messages.collect { msg ->
                when (msg) {
                    is ServerMessage.Hello -> {
                        _ui.update { it.copy(isConnected = true, displayName = msg.displayName) }
                        if (msg.firstLogin) addMessage("assistant", "Hello ${msg.displayName}! I'm VERA. How can I help?")
                    }
                    is ServerMessage.AssistantThinking -> {
                        addMessage("assistant", msg.text)
                        if (ttsEnabled) tts.speak(msg.text, ttsRate)
                    }
                    is ServerMessage.AssistantText -> {
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
                    is ServerMessage.Error -> _ui.update { it.copy(error = msg.message, isTyping = false) }
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
        _ui.update { state ->
            state.copy(messages = state.messages + ChatMessage(nextId++, role, text))
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceSession.stopListening()
        tts.destroy()
        ws.disconnect()
    }
}
