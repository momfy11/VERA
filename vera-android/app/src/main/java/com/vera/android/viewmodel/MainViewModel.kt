package com.vera.android.viewmodel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.vera.android.system.ProactiveQuestionReceiver
import com.vera.android.system.VeraMediaController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ChatMessage(val id: Long, val role: String, val text: String, val imageBase64: String? = null)

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
    private val ws = VeraWebSocket(http)
    private val tts = TtsManager(app)
    private val appLauncher = AppLauncher(app)
    private val mediaController = VeraMediaController(app)

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private var nextId = 0L
    private var ttsEnabled = prefs.ttsEnabled
    private var ttsRate = prefs.ttsRate
    private var typingTimeoutJob: Job? = null

    private val voiceSession = VoiceSession(
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
        if (token != null) {
            connectWs(token)
            startForegroundService(app)
        }
        viewModelScope.launch {
            VeraForegroundService.wakeEvents.collect {
                if (voiceSession.state.value == VoiceState.IDLE) {
                    voiceSession.startListening()
                }
            }
        }
    }

    private fun connectWs(token: String) = ws.connect(token)

    fun sendMessage(text: String, imageBase64: String? = null, imageMime: String? = null) {
        if (text.isBlank() && imageBase64 == null) return
        addMessage("user", text, imageBase64)
        _ui.update { it.copy(isTyping = true, error = null) }
        ws.sendMessage(text, imageBase64, imageMime)
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
                    is ServerMessage.LaunchApp -> appLauncher.launchApp(msg.uri)
                    is ServerMessage.ProactiveQuestion -> showProactiveNotification(app, msg)
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
                when (vs) {
                    VoiceState.LISTENING -> {
                        tts.stop()
                        // Free the mic so SpeechRecognizer can take over
                        app.startService(Intent(app, VeraForegroundService::class.java)
                            .setAction(VeraForegroundService.ACTION_PAUSE_WAKE))
                    }
                    VoiceState.IDLE -> {
                        // Resume wake word stream after speech session ends
                        app.startService(Intent(app, VeraForegroundService::class.java)
                            .setAction(VeraForegroundService.ACTION_RESUME_WAKE))
                    }
                    else -> {}
                }
            }
        }
    }

    private fun showProactiveNotification(app: Application, msg: ServerMessage.ProactiveQuestion) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ProactiveQuestionReceiver.CHANNEL_ID, "VERA Learning", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val notifId = msg.questionId.hashCode()

        fun actionIntent(answer: String): PendingIntent {
            val i = Intent(app, ProactiveQuestionReceiver::class.java).apply {
                putExtra(ProactiveQuestionReceiver.EXTRA_QUESTION_ID, msg.questionId)
                putExtra(ProactiveQuestionReceiver.EXTRA_ANSWER, answer)
                putExtra(ProactiveQuestionReceiver.EXTRA_NOTIF_ID, notifId)
            }
            return PendingIntent.getBroadcast(
                app, (msg.questionId + answer).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(app, ProactiveQuestionReceiver.CHANNEL_ID)
            .setContentTitle(msg.title)
            .setContentText(msg.body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(0, msg.yesLabel, actionIntent("yes"))
            .addAction(0, msg.noLabel, actionIntent("no"))
            .setAutoCancel(true)
            .build()
        nm.notify(notifId, notification)
    }

    private fun addMessage(role: String, text: String, imageBase64: String? = null) {
        _ui.update { state -> state.copy(messages = state.messages + ChatMessage(nextId++, role, text, imageBase64)) }
    }

    override fun onCleared() {
        super.onCleared()
        voiceSession.stopListening()
        tts.destroy()
        ws.disconnect()
    }
}
