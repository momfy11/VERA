package com.vera.android.avatar

/** Discrete expression presets VERA can display. Extensible — add new values without changing renderers. */
enum class AvatarExpression { NEUTRAL, HAPPY, CURIOUS, THINKING, CONCERNED, SURPRISED }

/**
 * Continuous morph weights that drive the face renderer.
 * Values are 0–1 floats that Compose animates with `animateFloatAsState`.
 * Add new weights here when adding new expressions — no other file needs changing.
 */
data class ExpressionWeights(
    val browRaise: Float = 0f,
    val browFurrow: Float = 0f,
    val smileLeft: Float = 0f,
    val smileRight: Float = 0f,
    val frownLeft: Float = 0f,
    val frownRight: Float = 0f,
    val eyeWiden: Float = 0f,
    val mouthOpenBase: Float = 0f,
)

/** Map of preset → weights. To add a new expression: add enum value + entry here. */
val EXPRESSION_PRESETS: Map<AvatarExpression, ExpressionWeights> = mapOf(
    AvatarExpression.NEUTRAL   to ExpressionWeights(),
    AvatarExpression.HAPPY     to ExpressionWeights(browRaise = 0.15f, smileLeft = 0.65f, smileRight = 0.65f),
    AvatarExpression.CURIOUS   to ExpressionWeights(browRaise = 0.45f, browFurrow = 0.1f),
    AvatarExpression.THINKING  to ExpressionWeights(browFurrow = 0.4f, frownLeft = 0.1f, frownRight = 0.1f),
    AvatarExpression.CONCERNED to ExpressionWeights(browFurrow = 0.6f, frownLeft = 0.35f, frownRight = 0.35f),
    AvatarExpression.SURPRISED to ExpressionWeights(browRaise = 0.9f, eyeWiden = 0.85f, mouthOpenBase = 0.4f),
)

/**
 * Complete snapshot of all values needed to render one frame of the avatar.
 * Produced by combining ViewModel state + AnimationController + LipSyncController.
 *
 * iOS note: mirror this as a Swift struct with identical field names.
 */
data class AvatarRenderState(
    val expression: ExpressionWeights = ExpressionWeights(),
    /** 0–1 mouth openness driven by audio amplitude (TTS speaking) */
    val lipSync: Float = 0f,
    /** 0–1 eyelid closure (0 = fully open, 1 = fully closed) */
    val blinkAmount: Float = 0f,
    /** 0–1 vertical breathing cycle phase */
    val breathAmount: Float = 0f,
    /** -1..1 horizontal iris offset */
    val eyeGazeX: Float = 0f,
    /** -1..1 vertical iris offset */
    val eyeGazeY: Float = 0f,
    /** 0–1 circuit-glow pulse intensity */
    val circuitPulse: Float = 0.6f,
)
