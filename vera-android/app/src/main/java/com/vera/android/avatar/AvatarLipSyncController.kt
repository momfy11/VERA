package com.vera.android.avatar

import android.media.audiofx.Visualizer
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Drives mouth-open amplitude from TTS playback audio.
 *
 * Primary:  Android Visualizer(0) — taps global output mix, captures actual TTS waveform.
 *           Requires RECORD_AUDIO permission (already granted). Works on Android ≤ 11.
 *           May be unavailable on Android 12+ without CAPTURE_AUDIO_OUTPUT (system permission).
 *
 * Fallback: Synthetic sine-wave pulse at 4–6 Hz with randomised amplitude.
 *           Mimics natural speech cadence. Activates automatically if Visualizer fails.
 *
 * Pipeline:
 *   TTS audio out → Visualizer → waveform RMS → low-pass filter → amplitude StateFlow
 *                                                                        ↓
 *                                                    AvatarRenderState.lipSync
 *                                                                        ↓
 *                                         AvatarAnimationDriver → jawOpen morph weight
 */
class AvatarLipSyncController {

    private val _amplitude = MutableStateFlow(0f)
    /** Smoothed 0–1 lip amplitude. Collect in ViewModel, feed into AvatarRenderState.lipSync. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var visualizer: Visualizer? = null
    private var syntheticJob: Job? = null
    private var smoothed = 0f

    /** Call when TTS starts. Idempotent. Requires a CoroutineScope for synthetic fallback. */
    fun start(scope: CoroutineScope) {
        if (visualizer != null || syntheticJob?.isActive == true) return
        if (!tryStartVisualizer()) {
            startSyntheticPulse(scope)
        }
    }

    /** Call when TTS finishes. Resets amplitude to 0. */
    fun stop() {
        visualizer?.runCatching { enabled = false; release() }
        visualizer = null
        syntheticJob?.cancel()
        syntheticJob = null
        smoothed = 0f
        _amplitude.value = 0f
    }

    fun release() = stop()

    // ── Primary: Visualizer on global output mix ────────────────────────────

    private fun tryStartVisualizer(): Boolean {
        return runCatching {
            visualizer = Visualizer(0 /* global output mix */).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            v: Visualizer,
                            waveform: ByteArray,
                            samplingRate: Int,
                        ) {
                            // Waveform: unsigned bytes 0–255, silence = 128
                            var sum = 0L
                            for (b in waveform) sum += abs((b.toInt() and 0xFF) - 128)
                            val raw = (sum.toFloat() / waveform.size / 128f).coerceIn(0f, 1f)
                            // Exponential low-pass: removes noise spikes, keeps responsiveness
                            smoothed = smoothed * 0.55f + raw * 0.45f
                            // Boost so normal speech reaches 0.6–0.9
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
            Log.d("LipSync", "Visualizer started")
            true
        }.getOrElse { e ->
            Log.w("LipSync", "Visualizer unavailable ($e) — switching to synthetic pulse")
            false
        }
    }

    // ── Fallback: Synthetic speech-cadence pulse ────────────────────────────
    //
    // Generates a sine wave at 4–6 Hz (average human syllable rate).
    // Random frequency variation and amplitude modulation give natural appearance.
    // Not perfectly in-sync with words, but far better than a static closed mouth.

    private fun startSyntheticPulse(scope: CoroutineScope) {
        syntheticJob = scope.launch(Dispatchers.Default) {
            var phase = 0.0
            while (isActive) {
                // Each "syllable" lasts 160–250ms. Slight random drift per tick.
                val baseFreq = 4.5 + Random.nextDouble(-0.8, 0.8)   // 3.7–5.3 Hz
                val amp = 0.50f + Random.nextFloat() * 0.40f         // 0.5–0.9 per tick

                val raw = ((sin(phase).toFloat() * 0.5f + 0.5f) * amp).coerceIn(0f, 1f)
                // Slower smoothing than Visualizer — synthetic signal already smooth
                smoothed = smoothed * 0.65f + raw * 0.35f
                _amplitude.value = smoothed.coerceIn(0f, 1f)

                phase += 2.0 * PI * baseFreq / 60.0   // advance for ~60fps update
                delay(16L)
            }
        }
        Log.d("LipSync", "Synthetic pulse started")
    }
}
