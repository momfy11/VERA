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
import com.vera.android.avatar.AvatarAnimationController
import com.vera.android.avatar.AvatarExpression
import com.vera.android.avatar.AvatarLipSyncController
import com.vera.android.avatar.AvatarRenderState
import com.vera.android.avatar.EXPRESSION_PRESETS
import com.vera.android.system.AppLauncher
import com.vera.android.system.ProactiveQuestionReceiver
import com.vera.android.system.VeraMediaController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class FaceState { IDLE, LISTENING, THINKING, SPEAKING }

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

    val faceState: StateFlow<FaceState> = combine(ui, tts.isSpeaking) { uiState, speaking ->
        when {
            uiState.voiceState == VoiceState.LISTENING -> FaceState.LISTENING
            speaking -> FaceState.SPEAKING
            uiState.isTyping -> FaceState.THINKING
            else -> FaceState.IDLE
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FaceState.IDLE)

    // ── Avatar system ─────────────────────────────────────────────────────────
    private val animController = AvatarAnimationController()
    private val lipSync = AvatarLipSyncController()

    private val _expression = MutableStateFlow(AvatarExpression.NEUTRAL)

    // Intermediate: bundle the 4 continuous animation ticks into one typed flow
    private data class AnimTick(val breath: Float, val blink: Float, val gazeX: Float, val pulse: Float)
    private val _animTick = combine(
        animController.breathAmount,
        animController.blinkAmount,
        animController.eyeGazeX,
        animController.circuitPulse,
    ) { breath, blink, gazeX, pulse -> AnimTick(breath, blink, gazeX, pulse) }

    /** Single render state consumed by [VeraAvatar]. Combine at most 3 flows here. */
    val avatarState: StateFlow<AvatarRenderState> = combine(
        _expression,
        _animTick,
        lipSync.amplitude,
    ) { expr, anim, amp ->
        AvatarRenderState(
            expression   = EXPRESSION_PRESETS[expr] ?: com.vera.android.avatar.ExpressionWeights(),
            lipSync      = amp,
            blinkAmount  = anim.blink,
            breathAmount = anim.breath,
            eyeGazeX     = anim.gazeX,
            eyeGazeY     = 0f,
            circuitPulse = anim.pulse,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AvatarRenderState())

    /** Set VERA's facial expression from backend or internal logic. */
    fun setExpression(expression: AvatarExpression) {
        _expression.value = expression
    }

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
        animController.start(viewModelScope)
        // Start/stop Visualizer in sync with TTS speaking state
        viewModelScope.launch {
            tts.isSpeaking.collect { speaking ->
                if (speaking) lipSync.start() else lipSync.stop()
            }
        }
        collectWsMessages()
        val token = prefs.sessionToken
        if (token != null) {
            connectWs(token)
            startForegroundService(app)
        }
        viewModelScope.launch {
            var lastWakeMs = 0L
            VeraForegroundService.wakeEvents.collect {
                val now = System.currentTimeMillis()
                if (voiceSession.state.value == VoiceState.IDLE && now - lastWakeMs > 30_000) {
                    lastWakeMs = now
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

    fun startVoiceManual() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, VeraForegroundService::class.java)
            .setAction(VeraForegroundService.ACTION_PAUSE_WAKE))
        viewModelScope.launch {
            withTimeoutOrNull(400) { VeraForegroundService.micReleased.first() }
            voiceSession.startListening()
        }
    }

    fun stopVoice() = voiceSession.stopListening()

    fun toggleVoice() {
        if (voiceSession.state.value == VoiceState.LISTENING) {
            voiceSession.stopListening()
            return
        }
        val ctx = getApplication<Application>()
        // Send PAUSE_WAKE to release AudioRecord, then await confirmation before opening mic
        ctx.startService(Intent(ctx, VeraForegroundService::class.java)
            .setAction(VeraForegroundService.ACTION_PAUSE_WAKE))
        viewModelScope.launch {
            // Wait for service to actually release AudioRecord, max 400ms
            withTimeoutOrNull(400) { VeraForegroundService.micReleased.first() }
            voiceSession.startListening()
        }
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
        val ctx = getApplication<Application>()
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
                    is ServerMessage.ProactiveQuestion -> showProactiveNotification(msg)
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
                        // PAUSE_WAKE already sent by toggleVoice() (button) or service stopped itself (wake word)
                    }
                    VoiceState.IDLE -> {
                        viewModelScope.launch {
                            tts.isSpeaking.first { !it }
                            ctx.startService(Intent(ctx, VeraForegroundService::class.java)
                                .setAction(VeraForegroundService.ACTION_RESUME_WAKE))
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun showProactiveNotification(msg: ServerMessage.ProactiveQuestion) {
        val ctx = getApplication<Application>()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ProactiveQuestionReceiver.CHANNEL_ID, "VERA Learning", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val notifId = msg.questionId.hashCode()

        fun actionIntent(answer: String): PendingIntent {
            val i = Intent(ctx, ProactiveQuestionReceiver::class.java).apply {
                putExtra(ProactiveQuestionReceiver.EXTRA_QUESTION_ID, msg.questionId)
                putExtra(ProactiveQuestionReceiver.EXTRA_ANSWER, answer)
                putExtra(ProactiveQuestionReceiver.EXTRA_NOTIF_ID, notifId)
            }
            return PendingIntent.getBroadcast(
                ctx, (msg.questionId + answer).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(ctx, ProactiveQuestionReceiver.CHANNEL_ID)
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
        animController.stop()
        lipSync.release()
        voiceSession.stopListening()
        tts.destroy()
        ws.disconnect()
    }
}
