package com.vera.android.avatar

import android.media.audiofx.Visualizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Captures real-time audio amplitude from the device output mix using the
 * Android Visualizer API (session 0 = global output mix).
 *
 * Requirements:
 *   - android.permission.RECORD_AUDIO  (already granted for voice)
 *   - android.permission.MODIFY_AUDIO_SETTINGS  (added to manifest)
 *
 * iOS equivalent: AVAudioSession + AVAudioEngine tap on the output node.
 *
 * Architecture:
 *   TTS audio output → Visualizer → [low-pass filter] → amplitude: StateFlow<Float>
 *                                                               ↓
 *                                                     AvatarRenderer mouth morph
 */
class AvatarLipSyncController {

    private val _amplitude = MutableStateFlow(0f)
    /** Smoothed 0–1 amplitude. Collect in ViewModel and feed into AvatarRenderState.lipSync. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var visualizer: Visualizer? = null
    private var smoothed = 0f

    /** Call when TTS isSpeaking → true. Safe to call repeatedly. */
    fun start() {
        if (visualizer != null) return
        runCatching {
            visualizer = Visualizer(0 /* global output mix */).apply {
                // Smallest capture size = lowest latency for lip sync
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            v: Visualizer,
                            waveform: ByteArray,
                            samplingRate: Int,
                        ) {
                            // Waveform bytes: unsigned 0–255, silence = 128
                            var sum = 0L
                            for (b in waveform) sum += abs((b.toInt() and 0xFF) - 128)
                            val raw = (sum.toFloat() / waveform.size / 128f).coerceIn(0f, 1f)
                            // Exponential low-pass filter: smooths out noise, keeps responsiveness
                            smoothed = smoothed * 0.55f + raw * 0.45f
                            // Boost so normal speech reaches 0.6–0.9 range
                            _amplitude.value = (smoothed * 2.5f).coerceIn(0f, 1f)
                        }
                        override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) {}
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    true,  // waveform
                    false, // fft
                )
                enabled = true
            }
        }.onFailure { e ->
            Log.w("LipSync", "Visualizer unavailable: $e")
        }
    }

    /** Call when TTS isSpeaking → false. Resets amplitude to 0. */
    fun stop() {
        visualizer?.runCatching { enabled = false; release() }
        visualizer = null
        smoothed = 0f
        _amplitude.value = 0f
    }

    fun release() = stop()
}
