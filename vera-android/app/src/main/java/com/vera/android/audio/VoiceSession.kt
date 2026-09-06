package com.vera.android.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VoiceState { IDLE, LISTENING, PROCESSING }

class VoiceSession(
    private val context: Context,
    private val onInterim: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onVadStart: () -> Unit,
    private val onVadEnd: () -> Unit,
) {
    // SpeechRecognizer must be created and used on the main thread
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state

    fun startListening() {
        if (_state.value == VoiceState.LISTENING) return
        _state.value = VoiceState.LISTENING
        mainHandler.post { startRecognizer() }
    }

    private fun startRecognizer() {
        recognizer?.destroy()
        recognizer = null

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = VoiceState.IDLE
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() { onVadStart() }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    onVadEnd()
                    _state.value = VoiceState.PROCESSING
                }
                override fun onError(error: Int) {
                    Log.w("VoiceSession", "SpeechRecognizer error $error")
                    recognizer?.destroy()
                    recognizer = null
                    _state.value = VoiceState.IDLE
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    recognizer?.destroy()
                    recognizer = null
                    if (text.isNotBlank()) onFinal(text)
                    _state.value = VoiceState.IDLE
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    if (text.isNotBlank()) onInterim(text)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
    }

    fun stopListening() {
        mainHandler.post {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
        }
        _state.value = VoiceState.IDLE
    }
}
