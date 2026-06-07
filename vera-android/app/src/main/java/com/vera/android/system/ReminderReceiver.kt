package com.vera.android.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.vera.android.MainActivity
import java.util.Locale

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return

        val nm = context.getSystemService(NotificationManager::class.java)
        createChannel(nm)

        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        nm.notify(
            System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("VERA Reminder")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(tapIntent)
                .setAutoCancel(true)
                .build(),
        )

        val audioManager = context.getSystemService(AudioManager::class.java)
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            val wakeLock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vera:reminder")
            wakeLock.acquire(10_000L)

            TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val tts = it as? TextToSpeech ?: return@TextToSpeech
                    tts.language = Locale.ENGLISH
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reminder")
                    tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { wakeLock.release(); tts.shutdown() }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { wakeLock.release(); tts.shutdown() }
                    })
                } else {
                    wakeLock.release()
                }
            }
        }
    }

    private fun createChannel(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VERA Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val EXTRA_TEXT = "reminder_text"
        private const val CHANNEL_ID = "vera_reminders"
    }
}
