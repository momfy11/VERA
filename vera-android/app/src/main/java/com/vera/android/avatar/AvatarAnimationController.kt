package com.vera.android.avatar

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Drives all continuous passive animations: breathing, blinking, eye saccades, circuit pulse.
 * Runs coroutines in the provided CoroutineScope (ViewModel scope).
 * Each animation loop is independent — cancel one without affecting others.
 *
 * iOS equivalent: Combine Timer publishers or SwiftUI .onAppear withAnimation loops.
 */
class AvatarAnimationController {

    private val _breathAmount  = MutableStateFlow(0f)
    private val _blinkAmount   = MutableStateFlow(0f)
    private val _eyeGazeX      = MutableStateFlow(0f)
    private val _eyeGazeY      = MutableStateFlow(0f)
    private val _circuitPulse  = MutableStateFlow(0.6f)

    val breathAmount:  StateFlow<Float> = _breathAmount.asStateFlow()
    val blinkAmount:   StateFlow<Float> = _blinkAmount.asStateFlow()
    val eyeGazeX:      StateFlow<Float> = _eyeGazeX.asStateFlow()
    val eyeGazeY:      StateFlow<Float> = _eyeGazeY.asStateFlow()
    val circuitPulse:  StateFlow<Float> = _circuitPulse.asStateFlow()

    private var jobs: List<Job> = emptyList()

    fun start(scope: CoroutineScope) {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch(Dispatchers.Default) { animateBreathing() },
            scope.launch(Dispatchers.Default) { animateBlink() },
            scope.launch(Dispatchers.Default) { animateEyeMovement() },
            scope.launch(Dispatchers.Default) { animateCircuitPulse() },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    // Smooth 4-second breathing sine wave. Shift is applied as Y-translate in renderer.
    private suspend fun animateBreathing() {
        var phase = 0.0
        while (true) {
            _breathAmount.value = sin(phase).toFloat()
            phase += Math.PI * 2.0 / (4000.0 / 16.0) // full cycle in 4 s at 16ms ticks
            delay(16)
        }
    }

    // Natural blink: random interval 3–6 s; occasional double-blink.
    private suspend fun animateBlink() {
        while (true) {
            delay(Random.nextLong(3_000, 6_000))
            closeLid()
            if (Random.nextFloat() < 0.25f) { // 25% chance double-blink
                delay(Random.nextLong(150, 300))
                closeLid()
            }
        }
    }

    private suspend fun closeLid() {
        // Fast close (~80ms), slightly slower open (~120ms)
        for (step in 0..4) { _blinkAmount.value = step / 4f; delay(16) }
        _blinkAmount.value = 1f
        delay(80)
        for (step in 4 downTo 0) { _blinkAmount.value = step / 4f; delay(20) }
        _blinkAmount.value = 0f
    }

    // Subtle eye saccades: small random gaze targets, fast movement, long hold.
    private suspend fun animateEyeMovement() {
        var curX = 0f; var curY = 0f
        while (true) {
            val tgtX = Random.nextFloat() * 0.4f - 0.2f
            val tgtY = Random.nextFloat() * 0.3f - 0.15f
            val steps = Random.nextInt(8, 18) // saccade over 130–290ms
            val dx = (tgtX - curX) / steps
            val dy = (tgtY - curY) / steps
            repeat(steps) {
                curX += dx; curY += dy
                _eyeGazeX.value = curX; _eyeGazeY.value = curY
                delay(16)
            }
            delay(Random.nextLong(2_000, 5_000))
        }
    }

    // Slow pulsing glow on circuit traces (7 s period).
    private suspend fun animateCircuitPulse() {
        var phase = 0.0
        while (true) {
            _circuitPulse.value = (sin(phase) * 0.25f + 0.75f).toFloat()
            phase += Math.PI * 2.0 / (7000.0 / 50.0)
            delay(50)
        }
    }
}
