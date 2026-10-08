package com.vera.android.audio

import android.content.Context
import android.content.Intent
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Centralises TTS-aware microphone management for the wake-word pipeline.
 *
 * Problem solved:
 *   TTS plays through speaker → mic picks it up → wake-word detector fires → VERA interrupts herself.
 *
 * Mechanism:
 *   When TTS starts:  send ACTION_PAUSE_WAKE  → VeraForegroundService stops AudioRecord.
 *   When TTS ends:    send ACTION_RESUME_WAKE → AudioRecord restarts (only if voice not actively listening).
 *
 * AcousticEchoCanceler:
 *   Hardware echo cancellation attached to the AudioRecord session.
 *   Works as a second line of defence on top of the muting approach.
 *   Not available on all devices — falls back gracefully.
 */
class VoiceActivationManager(private val context: Context) {

    /**
     * Call once from ViewModel init. Automatically pauses/resumes wake word mic with TTS state.
     *
     * @param isSpeaking     TTS output state — pause wake mic when true.
     * @param voiceState     Current voice session state — do NOT resume wake mic if actively LISTENING.
     */
    fun observeTts(
        scope: CoroutineScope,
        isSpeaking: StateFlow<Boolean>,
        voiceState: StateFlow<VoiceState>,
    ) {
        scope.launch {
            isSpeaking.distinctUntilChanged().collect { speaking ->
                if (speaking) {
                    // TTS started — silence the wake-word mic to prevent echo barge-in
                    sendServiceAction(VeraForegroundService.ACTION_PAUSE_WAKE)
                    Log.d(TAG, "TTS started → PAUSE_WAKE")
                } else {
                    // TTS finished — only re-arm wake word if voice session is idle
                    // (if user is actively speaking, the VoiceState.IDLE handler resumes instead)
                    if (voiceState.value == VoiceState.IDLE) {
                        sendServiceAction(VeraForegroundService.ACTION_RESUME_WAKE)
                        Log.d(TAG, "TTS done, voice IDLE → RESUME_WAKE")
                    } else {
                        Log.d(TAG, "TTS done but voice=${voiceState.value} — not resuming wake mic")
                    }
                }
            }
        }
    }

    private fun sendServiceAction(action: String) {
        runCatching {
            context.startService(
                Intent(context, VeraForegroundService::class.java).setAction(action)
            )
        }.onFailure { Log.e(TAG, "Failed to send $action: $it") }
    }

    companion object {
        private const val TAG = "VoiceActivation"

        /**
         * Attach AcousticEchoCanceler to an AudioRecord immediately after ar.startRecording().
         * Modifies audio data in-place before the app reads it — zero additional latency.
         * Returns the AEC instance; caller must release it when AudioRecord is released.
         */
        fun attachAec(audioSessionId: Int): AcousticEchoCanceler? {
            if (!AcousticEchoCanceler.isAvailable()) {
                Log.d(TAG, "AEC not available on this device")
                return null
            }
            return runCatching {
                AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "AEC attached to session $audioSessionId")
                }
            }.onFailure { Log.w(TAG, "AEC create failed: $it") }
             .getOrNull()
        }
    }
}
