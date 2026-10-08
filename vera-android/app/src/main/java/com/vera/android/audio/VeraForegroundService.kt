package com.vera.android.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.vera.android.MainActivity
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
import kotlin.math.sqrt

class VeraForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "vera_foreground"
        private const val NOTIF_ID = 1001
        private const val WAKE_NOTIF_ID = 1002
        private const val WS_WAKE_URL = "wss://vera-app.hopto.org/ws/wake"
        private const val SAMPLE_RATE = 16000
        private const val RMS_GATE_THRESHOLD = 600f   // tune up to reduce sensitivity, down for quieter rooms
        const val ACTION_PAUSE_WAKE = "pause_wake"
        const val ACTION_RESUME_WAKE = "resume_wake"
        const val EXTRA_WAKE_TRIGGERED = "wake_triggered"

        private val _wakeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val wakeEvents = _wakeEvents.asSharedFlow()

        private val _micReleased = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        /** Emitted whenever AudioRecord is fully released — callers can await before opening mic. */
        val micReleased = _micReleased.asSharedFlow()

        /** Called from MainActivity when it handles a wake-triggered launch. */
        fun triggerWake() { _wakeEvents.tryEmit(Unit) }
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

            // VOICE_COMMUNICATION enables hardware AEC + NS on most devices.
            // Better than MIC for assistant use: hardware already filters speaker echo.
            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
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

            // Software AEC as second line of defence — hardware AEC may not be present on all devices.
            val aec: AcousticEchoCanceler? = VoiceActivationManager.attachAec(ar.audioSessionId)

            val buf = ByteArray(bufSize)

            while (isActive && streaming) {
                val read = ar.read(buf, 0, buf.size)
                if (read <= 0) continue

                // RMS energy gate: skip silent / near-silent frames (ambient noise floor).
                // Prevents wake-word server receiving constant low-level noise.
                // 600 ≈ 1.8% of PCM 16-bit max (32768) — below normal breath/speech.
                if (pcmRms(buf, read) < RMS_GATE_THRESHOLD) continue

                val sent = webSocket.send(ByteString.of(*buf.copyOf(read)))
                if (!sent) break
            }

            aec?.runCatching { enabled = false; release() }
            ar.stop()
            ar.release()
            audioRecord = null
            _micReleased.tryEmit(Unit)
        }
    }

    /** Computes RMS of a PCM-16LE byte buffer. Returns 0..32768. */
    private fun pcmRms(pcm: ByteArray, length: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < length) {
            val sample = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toFloat()
            sum += sample * sample
            i += 2
        }
        return sqrt(sum / (length / 2)).toFloat()
    }

    private fun onWakeWordDetected() {
        // Stop wake word stream so mic is free for VoiceSession
        streaming = false
        audioRecord?.stop()
        wakeWs?.close(1000, "wake detected")

        val pm = getSystemService(PowerManager::class.java)
        if (pm.isInteractive) {
            // Screen already on — wait for AudioRecord release then emit
            scope.launch {
                // Spin until IO coroutine finishes releasing AudioRecord
                while (audioRecord != null) kotlinx.coroutines.delay(10)
                _wakeEvents.tryEmit(Unit)
            }
        } else {
            // Screen off — wake screen, bring Activity to foreground via full-screen notification
            val wl = pm.newWakeLock(
                @Suppress("DEPRECATION")
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "vera:wake_screen",
            )
            wl.acquire(12_000L)

            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_WAKE_TRIGGERED, true)
            }
            val pi = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(
                WAKE_NOTIF_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("VERA")
                    .setContentText("Listening…")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setFullScreenIntent(pi, true)
                    .setAutoCancel(true)
                    .build(),
            )

            scope.launch {
                delay(10_000)
                if (wl.isHeld) wl.release()
                nm.cancel(WAKE_NOTIF_ID)
            }
        }

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
