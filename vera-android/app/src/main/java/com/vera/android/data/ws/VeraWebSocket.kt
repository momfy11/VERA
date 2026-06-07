package com.vera.android.data.ws

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString

private const val WS_URL = "wss://46.62.225.46/ws"

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
    data class Error(val message: String) : ServerMessage()
}

class VeraWebSocket(private val http: OkHttpClient) {

    private val _messages = Channel<ServerMessage>(Channel.UNLIMITED)
    val messages: Flow<ServerMessage> = _messages.receiveAsFlow()

    private var ws: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun connect(token: String) {
        val req = Request.Builder().url(WS_URL).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"client.hello","token":"$token"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = json.parseToJsonElement(text).jsonObject
                val type = obj["type"]?.jsonPrimitive?.content ?: return
                val msg = when (type) {
                    "server.hello" -> ServerMessage.Hello(
                        displayName = obj["display_name"]?.jsonPrimitive?.content ?: "",
                        firstLogin = obj["first_login"]?.jsonPrimitive?.boolean ?: false,
                    )
                    "assistant.text" -> ServerMessage.AssistantText(obj["text"]?.jsonPrimitive?.content ?: "")
                    "assistant.thinking" -> ServerMessage.AssistantThinking(obj["text"]?.jsonPrimitive?.content ?: "")
                    "assistant.tts_cancel" -> ServerMessage.TtsCancel
                    "agent.action_pending" -> ServerMessage.ActionPending(
                        actionId = obj["action_id"]?.jsonPrimitive?.content ?: "",
                        tool = obj["tool"]?.jsonPrimitive?.content ?: "",
                        summary = obj["summary"]?.jsonPrimitive?.content ?: "",
                        args = obj["args"]?.jsonObject ?: buildJsonObject {},
                        timeoutSecs = obj["timeout_s"]?.jsonPrimitive?.int ?: 60,
                    )
                    "agent.action_resolved" -> ServerMessage.ActionResolved
                    "agent.open_url" -> ServerMessage.OpenUrl(obj["url"]?.jsonPrimitive?.content ?: "")
                    "agent.set_reminder" -> ServerMessage.SetReminder(
                        timeIso = obj["time"]?.jsonPrimitive?.content ?: "",
                        text = obj["text"]?.jsonPrimitive?.content ?: "",
                    )
                    "agent.media_control" -> ServerMessage.MediaControl(obj["action"]?.jsonPrimitive?.content ?: "")
                    "agent.launch_app" -> ServerMessage.LaunchApp(obj["uri"]?.jsonPrimitive?.content ?: "")
                    "server.error" -> ServerMessage.Error(obj["message"]?.jsonPrimitive?.content ?: "error")
                    else -> null
                }
                msg?.let { _messages.trySend(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _messages.trySend(ServerMessage.Error("Connection lost: ${t.message}"))
            }
        })
    }

    fun sendMessage(text: String, imageBase64: String? = null, imageMime: String? = null) {
        val payload = buildJsonObject {
            put("type", "client.message")
            put("text", text)
            imageBase64?.let { put("image_data", it) }
            imageMime?.let { put("image_mime", it) }
        }
        ws?.send(payload.toString())
    }

    fun sendSttFinal(text: String) {
        ws?.send("""{"type":"stt.final","text":"${text.replace("\"", "\\\"")}"}""")
    }

    fun sendVadStart() = ws?.send("""{"type":"voice.vad_start","ts":"${java.time.Instant.now()}"}""")
    fun sendVadEnd() = ws?.send("""{"type":"voice.vad_end","ts":"${java.time.Instant.now()}"}""")

    fun sendAudioChunk(pcm: ByteArray) {
        ws?.send(ByteString.of(*pcm))
    }

    fun disconnect() {
        ws?.close(1000, "user logout")
        ws = null
    }
}
