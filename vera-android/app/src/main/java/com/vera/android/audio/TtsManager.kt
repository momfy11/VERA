package com.vera.android.audio

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    var onDone: (() -> Unit)? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    fun init(onReady: () -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ENGLISH
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        onDone?.invoke()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                        onDone?.invoke()
                    }
                })
                ready = true
                onReady()
            }
        }
    }

    fun speak(text: String, rate: Float = 1.0f) {
        if (!ready) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val cleaned = text
            .replace(Regex("\\*+"), "")
            .replace(Regex("`+"), "")
            .replace(Regex("#+\\s*"), "")
            .trim()
        tts?.setSpeechRate(rate)
        tts?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        _isSpeaking.value = false
    }
}
