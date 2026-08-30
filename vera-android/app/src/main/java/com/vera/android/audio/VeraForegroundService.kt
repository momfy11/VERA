package com.vera.android.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vera.android.R
import com.vera.android.data.buildHttpClient
import com.vera.android.data.prefs.SecurePrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class VeraForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "vera_foreground"
        private const val NOTIF_ID = 1001
        private const val WS_WAKE_URL = "wss://vera-app.hopto.org/ws/wake"
        private const val SAMPLE_RATE = 16000
        const val ACTION_PAUSE_WAKE = "pause_wake"
        const val ACTION_RESUME_WAKE = "resume_wake"

        private val _wakeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val wakeEvents = _wakeEvents.asSharedFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioRecord: AudioRecord? = null
    private var wakeWs: WebSocket? = null
    private var streaming = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        startWakeWordStream()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_WAKE -> {
                streaming = false
                audioRecord?.stop()
                wakeWs?.close(1000, "paused for training")
            }
            ACTION_RESUME_WAKE -> startWakeWordStream()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWakeWordStream() {
        if (streaming) return
        streaming = true

        val http = buildHttpClient()
        val req = Request.Builder().url(WS_WAKE_URL).build()

        wakeWs = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                startAudioStream(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = Json.parseToJsonElement(text).jsonObject
                    val detected = obj["detected"]?.jsonPrimitive?.content
                    if (detected == "true" || detected == "1") {
                        onWakeWordDetected()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                streaming = false
                // Retry after 5s
                scope.launch {
                    delay(5_000)
                    startWakeWordStream()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                streaming = false
            }
        })
    }

    private fun startAudioStream(webSocket: WebSocket) {
        scope.launch {
            val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(4096)

            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
            audioRecord = ar

            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                streaming = false
                return@launch
            }

            ar.startRecording()
            val buf = ByteArray(bufSize)

            while (isActive && streaming) {
                val read = ar.read(buf, 0, buf.size)
                if (read > 0) {
                    val sent = webSocket.send(ByteString.of(*buf.copyOf(read)))
                    if (!sent) break
                }
            }

            ar.stop()
            ar.release()
            audioRecord = null
        }
    }

    private fun onWakeWordDetected() {
        // Stop wake word stream so mic is free for VoiceSession
        streaming = false
        audioRecord?.stop()
        wakeWs?.close(1000, "wake detected")

        _wakeEvents.tryEmit(Unit)

        // Resume wake word stream after 8s (enough time for command + response)
        scope.launch {
            delay(8_000)
            startWakeWordStream()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streaming = false
        audioRecord?.release()
        wakeWs?.close(1000, "service destroyed")
        scope.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "VERA Active", NotificationManager.IMPORTANCE_LOW).apply {
            description = "VERA is listening for wake word"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VERA")
            .setContentText("Listening for wake word…")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
}
