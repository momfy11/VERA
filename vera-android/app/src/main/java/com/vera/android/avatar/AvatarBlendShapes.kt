package com.vera.android.avatar

/**
 * ARKit 52 morph target names — lowercase camelCase, matching Apple ARKit and Ready Player Me exports.
 *
 * Ready Player Me:   export with "ARKit blendshapes" → these names present out of box.
 * MetaHuman (UE5):   same ARKit naming convention.
 * VRoid Studio VRM:  different names — needs rename via BlendShapeProxy before GLB export.
 *
 * AvatarAnimationDriver stores a case-insensitive cache, so minor case variants still match.
 *
 * iOS: identical names used in ARKit FaceAnchor.BlendShapeLocation and RealityKit MorpherComponent.
 */
object AvatarBlendShapes {
    // Eyes
    const val EYE_BLINK_L       = "eyeBlinkLeft"
    const val EYE_BLINK_R       = "eyeBlinkRight"
    const val EYE_WIDE_L        = "eyeWideLeft"
    const val EYE_WIDE_R        = "eyeWideRight"
    const val EYE_SQUINT_L      = "eyeSquintLeft"
    const val EYE_SQUINT_R      = "eyeSquintRight"
    const val EYE_LOOK_UP_L     = "eyeLookUpLeft"
    const val EYE_LOOK_UP_R     = "eyeLookUpRight"
    const val EYE_LOOK_DOWN_L   = "eyeLookDownLeft"
    const val EYE_LOOK_DOWN_R   = "eyeLookDownRight"
    const val EYE_LOOK_IN_L     = "eyeLookInLeft"
    const val EYE_LOOK_OUT_L    = "eyeLookOutLeft"
    const val EYE_LOOK_IN_R     = "eyeLookInRight"
    const val EYE_LOOK_OUT_R    = "eyeLookOutRight"

    // Brows
    const val BROW_DOWN_L       = "browDownLeft"
    const val BROW_DOWN_R       = "browDownRight"
    const val BROW_INNER_UP     = "browInnerUp"
    const val BROW_OUTER_UP_L   = "browOuterUpLeft"
    const val BROW_OUTER_UP_R   = "browOuterUpRight"

    // Jaw
    const val JAW_OPEN          = "jawOpen"
    const val JAW_LEFT          = "jawLeft"
    const val JAW_RIGHT         = "jawRight"
    const val JAW_FORWARD       = "jawForward"

    // Mouth
    const val MOUTH_SMILE_L      = "mouthSmileLeft"
    const val MOUTH_SMILE_R      = "mouthSmileRight"
    const val MOUTH_FROWN_L      = "mouthFrownLeft"
    const val MOUTH_FROWN_R      = "mouthFrownRight"
    const val MOUTH_PUCKER       = "mouthPucker"
    const val MOUTH_FUNNEL       = "mouthFunnel"
    const val MOUTH_UPPER_UP_L   = "mouthUpperUpLeft"
    const val MOUTH_UPPER_UP_R   = "mouthUpperUpRight"
    const val MOUTH_LOWER_DOWN_L = "mouthLowerDownLeft"
    const val MOUTH_LOWER_DOWN_R = "mouthLowerDownRight"
    const val MOUTH_PRESS_L      = "mouthPressLeft"
    const val MOUTH_PRESS_R      = "mouthPressRight"
    const val MOUTH_ROLL_UPPER   = "mouthRollUpper"
    const val MOUTH_ROLL_LOWER   = "mouthRollLower"
    const val MOUTH_SHRUG_UPPER  = "mouthShrugUpper"
    const val MOUTH_SHRUG_LOWER  = "mouthShrugLower"
    const val MOUTH_DIMPLE_L     = "mouthDimpleLeft"
    const val MOUTH_DIMPLE_R     = "mouthDimpleRight"

    // Cheeks / Nose
    const val CHEEK_PUFF        = "cheekPuff"
    const val CHEEK_SQUINT_L    = "cheekSquintLeft"
    const val CHEEK_SQUINT_R    = "cheekSquintRight"
    const val NOSE_SNEER_L      = "noseSneerLeft"
    const val NOSE_SNEER_R      = "noseSneerRight"

    // Tongue
    const val TONGUE_OUT        = "tongueOut"
}
