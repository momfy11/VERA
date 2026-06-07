package com.vera.android.system

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import com.vera.android.system.VeraNotificationListener

class VeraMediaController(private val context: Context) {

    private fun activeController() = runCatching {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(context, VeraNotificationListener::class.java)
        msm.getActiveSessions(component).firstOrNull()?.let {
            android.media.session.MediaController(context, it.sessionToken)
        }
    }.getOrNull()

    fun execute(action: String) {
        val ctrl = activeController()
        val audio = context.getSystemService(AudioManager::class.java)
        when (action) {
            "play" -> ctrl?.transportControls?.play()
            "pause" -> ctrl?.transportControls?.pause()
            "next" -> ctrl?.transportControls?.skipToNext()
            "previous" -> ctrl?.transportControls?.skipToPrevious()
            "volume_up" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        }
    }

    fun nowPlaying(): String? = runCatching {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(context, VeraNotificationListener::class.java)
        msm.getActiveSessions(component).firstOrNull()?.let { token ->
            val ctrl = android.media.session.MediaController(context, token.sessionToken)
            val meta = ctrl.metadata ?: return@let null
            val title = meta.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val artist = meta.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            "$title — $artist"
        }
    }.getOrNull()
}
