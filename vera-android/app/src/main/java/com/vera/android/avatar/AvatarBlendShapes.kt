package com.vera.android.avatar

/**
 * Standard ARKit / Ready Player Me morph target names.
 *
 * These strings are matched against blend shape names in the loaded GLB at runtime.
 * Missing names are silently ignored — partial rigs work fine.
 *
 * Ready Player Me: all names present out of box.
 * VRoid Studio VRM: needs conversion (VRM blendshape names differ — see BlendShapeProxy).
 * MetaHuman: same ARKit naming.
 *
 * iOS: identical names work in ARKit FaceAnchor.BlendShapeLocation and RealityKit MorpherComponent.
 */
object AvatarBlendShapes {
    // Eyes
    const val EYE_BLINK_L       = "EyeBlink_L"
    const val EYE_BLINK_R       = "EyeBlink_R"
    const val EYE_WIDE_L        = "EyeWideLeft"
    const val EYE_WIDE_R        = "EyeWideRight"
    const val EYE_SQUINT_L      = "EyeSquint_L"
    const val EYE_SQUINT_R      = "EyeSquint_R"
    const val EYE_LOOK_UP_L     = "EyeLookUpLeft"
    const val EYE_LOOK_UP_R     = "EyeLookUpRight"
    const val EYE_LOOK_DOWN_L   = "EyeLookDownLeft"
    const val EYE_LOOK_DOWN_R   = "EyeLookDownRight"
    const val EYE_LOOK_IN_L     = "EyeLookInLeft"
    const val EYE_LOOK_OUT_L    = "EyeLookOutLeft"
    const val EYE_LOOK_IN_R     = "EyeLookInRight"
    const val EYE_LOOK_OUT_R    = "EyeLookOutRight"

    // Brows
    const val BROW_DOWN_L       = "BrowDownLeft"
    const val BROW_DOWN_R       = "BrowDownRight"
    const val BROW_INNER_UP     = "BrowInnerUp"
    const val BROW_OUTER_UP_L   = "BrowOuterUpLeft"
    const val BROW_OUTER_UP_R   = "BrowOuterUpRight"

    // Jaw
    const val JAW_OPEN          = "JawOpen"
    const val JAW_LEFT          = "JawLeft"
    const val JAW_RIGHT         = "JawRight"
    const val JAW_FORWARD       = "JawForward"

    // Mouth
    const val MOUTH_SMILE_L     = "MouthSmileLeft"
    const val MOUTH_SMILE_R     = "MouthSmileRight"
    const val MOUTH_FROWN_L     = "MouthFrownLeft"
    const val MOUTH_FROWN_R     = "MouthFrownRight"
    const val MOUTH_PUCKER      = "MouthPucker"
    const val MOUTH_FUNNEL      = "MouthFunnel"
    const val MOUTH_UPPER_UP_L  = "MouthUpperUpLeft"
    const val MOUTH_UPPER_UP_R  = "MouthUpperUpRight"
    const val MOUTH_LOWER_DOWN_L = "MouthLowerDownLeft"
    const val MOUTH_LOWER_DOWN_R = "MouthLowerDownRight"
    const val MOUTH_PRESS_L     = "MouthPressLeft"
    const val MOUTH_PRESS_R     = "MouthPressRight"
    const val MOUTH_ROLL_UPPER  = "MouthRollUpper"
    const val MOUTH_ROLL_LOWER  = "MouthRollLower"
    const val MOUTH_SHRUG_UPPER = "MouthShrugUpper"
    const val MOUTH_SHRUG_LOWER = "MouthShrugLower"
    const val MOUTH_DIMPLE_L    = "MouthDimpleLeft"
    const val MOUTH_DIMPLE_R    = "MouthDimpleRight"

    // Cheeks / Nose
    const val CHEEK_PUFF        = "CheekPuff"
    const val CHEEK_SQUINT_L    = "CheekSquint_L"
    const val CHEEK_SQUINT_R    = "CheekSquint_R"
    const val NOSE_SNEER_L      = "NoseSneer_L"
    const val NOSE_SNEER_R      = "NoseSneer_R"

    // Tongue
    const val TONGUE_OUT        = "TongueOut"
}
