package com.vera.android.data.ws

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString

private const val WS_URL = "wss://vera-app.hopto.org/ws"
private const val TAG = "VeraWebSocket"

sealed class ServerMessage {
    data class Hello(val displayName: String, val firstLogin: Boolean) : ServerMessage()
    data class AssistantText(val text: String) : ServerMessage()
    data class AssistantThinking(val text: String) : ServerMessage()
    object TtsCancel : ServerMessage()
    data class ActionPending(val actionId: String, val tool: String, val summary: String, val args: JsonObject, val timeoutSecs: Int) : ServerMessage()
    object ActionResolved : ServerMessage()
    data class OpenUrl(val url: String) : ServerMessage()
    data class SetReminder(val timeIso: String, val text: String) : ServerMessage()
    data class MediaControl(val action: String) : ServerMessage()
    data class LaunchApp(val uri: String) : ServerMessage()
    data class ProactiveQuestion(
        val questionId: String,
        val title: String,
        val body: String,
        val yesLabel: String,
        val noLabel: String,
    ) : ServerMessage()
    data class Error(val message: String) : ServerMessage()
    data class AuthError(val message: String) : ServerMessage()
}

class VeraWebSocket(private val http: OkHttpClient) {

    private val _messages = Channel<ServerMessage>(Channel.UNLIMITED)
    val messages: Flow<ServerMessage> = _messages.receiveAsFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var ws: WebSocket? = null
    private var currentToken: String? = null
    private var reconnectJob: Job? = null
    private var retryCount = 0
    private var intentionalClose = false
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun connect(token: String) {
        intentionalClose = false
        currentToken = token
        retryCount = 0
        reconnectJob?.cancel()
        doConnect(token)
    }

    private fun doConnect(token: String) {
        ws?.close(1000, "reconnect")
        ws = null
        _connected.value = false

        val req = Request.Builder().url(WS_URL).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WS opened, sending hello")
                val tz = java.time.ZoneId.systemDefault().id
                webSocket.send("""{"type":"client.hello","payload":{"token":"$token","tz":"$tz"}}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = json.parseToJsonElement(text).jsonObject
                    val type = obj["type"]?.jsonPrimitive?.content ?: return
                    val p = obj["payload"]?.jsonObject ?: buildJsonObject {}

                    val msg: ServerMessage? = when (type) {
                        "server.hello" -> {
                            _connected.value = true
                            retryCount = 0
                            Log.d(TAG, "Connected, hello received")
                            ServerMessage.Hello(
                                displayName = p["display_name"]?.jsonPrimitive?.content ?: "",
                                firstLogin = p["first_login"]?.jsonPrimitive?.boolean ?: false,
                            )
                        }
                        "assistant.text" -> ServerMessage.AssistantText(p["text"]?.jsonPrimitive?.content ?: "")
                        "assistant.thinking" -> ServerMessage.AssistantThinking(p["text"]?.jsonPrimitive?.content ?: "")
                        "assistant.tts_cancel" -> ServerMessage.TtsCancel
                        "agent.action_pending" -> ServerMessage.ActionPending(
                            actionId = p["action_id"]?.jsonPrimitive?.content ?: "",
                            tool = p["tool"]?.jsonPrimitive?.content ?: "",
                            summary = p["summary"]?.jsonPrimitive?.content ?: "",
                            args = p["args"]?.jsonObject ?: buildJsonObject {},
                            timeoutSecs = p["timeout_s"]?.jsonPrimitive?.int ?: 60,
                        )
                        "agent.action_resolved" -> ServerMessage.ActionResolved
                        "agent.open_url" -> ServerMessage.OpenUrl(p["url"]?.jsonPrimitive?.content ?: "")
                        "agent.set_reminder" -> ServerMessage.SetReminder(
                            timeIso = p["time"]?.jsonPrimitive?.content ?: "",
                            text = p["text"]?.jsonPrimitive?.content ?: "",
                        )
                        "agent.media_control" -> ServerMessage.MediaControl(p["action"]?.jsonPrimitive?.content ?: "")
                        "agent.launch_app" -> ServerMessage.LaunchApp(p["uri"]?.jsonPrimitive?.content ?: "")
                        "agent.proactive_question" -> ServerMessage.ProactiveQuestion(
                            questionId = p["question_id"]?.jsonPrimitive?.content ?: "",
                            title = p["title"]?.jsonPrimitive?.content ?: "VERA",
                            body = p["body"]?.jsonPrimitive?.content ?: "",
                            yesLabel = p["action_yes_label"]?.jsonPrimitive?.content ?: "Yes",
                            noLabel = p["action_no_label"]?.jsonPrimitive?.content ?: "No",
                        )
                        "server.error", "error" -> {
                            val errMsg = p["message"]?.jsonPrimitive?.content
                                ?: obj["message"]?.jsonPrimitive?.content ?: "error"
                            if (errMsg.contains("unauthorized", ignoreCase = true)
                                || errMsg.contains("invalid token", ignoreCase = true)
                                || errMsg.contains("expired", ignoreCase = true)
                            ) {
                                Log.w(TAG, "Auth error: $errMsg")
                                ServerMessage.AuthError(errMsg)
                            } else {
                                ServerMessage.Error(errMsg)
                            }
                        }
                        else -> null
                    }
                    msg?.let { _messages.trySend(it) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed: $code $reason")
                _connected.value = false
                if (!intentionalClose) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure: ${t.message}")
                _connected.value = false
                _messages.trySend(ServerMessage.Error("Connection lost — reconnecting…"))
                if (!intentionalClose) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        val token = currentToken ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delaySec = when {
                retryCount < 3 -> 2L
                retryCount < 6 -> 5L
                retryCount < 10 -> 15L
                else -> 30L
            }
            Log.d(TAG, "Reconnecting in ${delaySec}s (attempt ${retryCount + 1})")
            delay(delaySec * 1000)
            retryCount++
            doConnect(token)
        }
    }

    fun sendMessage(text: String, imageBase64: String? = null, imageMime: String? = null) {
        val payload = buildJsonObject {
            put("text", text)
            imageBase64?.let { put("image_data", it) }
            imageMime?.let { put("image_mime", it) }
        }
        ws?.send(buildJsonObject {
            put("type", "client.message")
            put("payload", payload)
        }.toString())
    }

    fun sendSttFinal(text: String) {
        ws?.send("""{"type":"stt.final","payload":{"text":"${text.replace("\"", "\\\"")}"}}}""")
    }

    fun sendVadStart() {
        ws?.send("""{"type":"voice.vad_start","payload":{"ts":"${java.time.Instant.now()}"}}""")
    }

    fun sendVadEnd() {
        ws?.send("""{"type":"voice.vad_end","payload":{"ts":"${java.time.Instant.now()}"}}""")
    }

    fun sendAudioChunk(pcm: ByteArray) = ws?.send(ByteString.of(*pcm))

    fun disconnect() {
        intentionalClose = true
        reconnectJob?.cancel()
        ws?.close(1000, "logout")
        ws = null
        _connected.value = false
    }
}
