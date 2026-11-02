package com.vera.android.audio

import android.content.Context
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

/**
 * Manages a SpeechRecognizer session with automatic continuation.
 *
 * When STT fires onEndOfSpeech after a mid-sentence pause, we accumulate the
 * partial result and immediately restart recognition for up to [CONTINUATION_WINDOW_MS].
 * If the user starts speaking again within that window the segments are stitched.
 * If no new speech arrives, the accumulated text is delivered as one final result.
 */
class VoiceSession(
    private val context: Context,
    private val onInterim: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onVadStart: () -> Unit,
    private val onVadEnd: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state

    private val segments = mutableListOf<String>()
    private var continuationRunnable: Runnable? = null
    private var awaitingContinuation = false

    companion object {
        private const val TAG = "VoiceSession"
        private const val CONTINUATION_WINDOW_MS = 2200L
        private const val SILENCE_COMPLETE_MS = 4000L
        private const val SILENCE_POSSIBLY_COMPLETE_MS = 3000L
        private const val SILENCE_MINIMUM_MS = 1500L
    }

    fun startListening() {
        if (_state.value == VoiceState.LISTENING) return
        segments.clear()
        awaitingContinuation = false
        cancelContinuationTimer()
        _state.value = VoiceState.LISTENING
        mainHandler.post { startRecognizer() }
    }

    private fun startRecognizer() {
        recognizer?.destroy()
        recognizer = null

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            deliverAccumulatedAndFinish()
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
            startListening(buildRecognizerIntent())
        }
    }

    private fun buildRecognizerIntent() =
        android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", SILENCE_COMPLETE_MS)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", SILENCE_POSSIBLY_COMPLETE_MS)
            putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", SILENCE_MINIMUM_MS)
        }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (awaitingContinuation) {
                Log.d(TAG, "Continuation recognizer ready")
            }
        }

        override fun onBeginningOfSpeech() {
            cancelContinuationTimer()
            awaitingContinuation = false
            onVadStart()
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            onVadEnd()
        }

        override fun onError(error: Int) {
            Log.w(TAG, "SpeechRecognizer error $error")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (awaitingContinuation) {
                        deliverAccumulatedAndFinish()
                    } else if (segments.isNotEmpty()) {
                        deliverAccumulatedAndFinish()
                    } else {
                        finishSession()
                    }
                }
                else -> {
                    deliverAccumulatedAndFinish()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""

            if (text.isNotBlank()) {
                segments.add(text)
                onInterim(segments.joinToString(" "))
            }

            startContinuationWindow()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                val full = if (segments.isNotEmpty()) {
                    segments.joinToString(" ") + " " + text
                } else {
                    text
                }
                onInterim(full)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun startContinuationWindow() {
        recognizer?.destroy()
        recognizer = null
        awaitingContinuation = true
        _state.value = VoiceState.LISTENING

        cancelContinuationTimer()
        val timeout = Runnable {
            if (awaitingContinuation) {
                Log.d(TAG, "Continuation window expired — delivering ${segments.size} segments")
                deliverAccumulatedAndFinish()
            }
        }
        continuationRunnable = timeout
        mainHandler.postDelayed(timeout, CONTINUATION_WINDOW_MS)

        mainHandler.post { startRecognizer() }
    }

    private fun cancelContinuationTimer() {
        continuationRunnable?.let { mainHandler.removeCallbacks(it) }
        continuationRunnable = null
    }

    private fun deliverAccumulatedAndFinish() {
        cancelContinuationTimer()
        awaitingContinuation = false
        val fullText = segments.joinToString(" ").trim()
        segments.clear()
        recognizer?.destroy()
        recognizer = null

        if (fullText.isNotBlank()) {
            _state.value = VoiceState.PROCESSING
            onFinal(fullText)
        }
        _state.value = VoiceState.IDLE
    }

    private fun finishSession() {
        cancelContinuationTimer()
        awaitingContinuation = false
        segments.clear()
        recognizer?.destroy()
        recognizer = null
        _state.value = VoiceState.IDLE
    }

    fun stopListening() {
        mainHandler.post {
            cancelContinuationTimer()
            awaitingContinuation = false
            val fullText = segments.joinToString(" ").trim()
            segments.clear()
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
            if (fullText.isNotBlank()) {
                _state.value = VoiceState.PROCESSING
                onFinal(fullText)
            }
            _state.value = VoiceState.IDLE
        }
    }
}
