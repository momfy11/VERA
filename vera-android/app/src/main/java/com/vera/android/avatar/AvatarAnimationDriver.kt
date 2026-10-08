package com.vera.android.avatar

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.gltfio.FilamentAsset
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode

/**
 * Maps AvatarRenderState → Filament 3D model (morph weights + head rotation).
 *
 * Threading contract:
 *   latestState — write on main thread (Compose SideEffect), read on Filament frame thread.
 *   @Volatile ensures cross-thread visibility with no lock overhead.
 *
 * Morph target indices are looked up by name once at first frame after model loads,
 * then cached per-entity. Blend shape names follow the ARKit 52 standard
 * (Ready Player Me, MetaHuman). Unknown names are silently skipped.
 *
 * iOS port: mirror in Swift — replace ModelNode with RealityKit ModelEntity,
 * setMorphWeights with MorpherComponent, Rotation with simd_float3 euler angles.
 */
class AvatarAnimationDriver {

    @Volatile
    var latestState: AvatarRenderState = AvatarRenderState()

    // entity (Int Filament handle) → (blendShapeName → morphTargetIndex)
    private val entityMorphMaps = LinkedHashMap<Int, Map<String, Int>>(4)
    private var cacheReady = false

    /**
     * Call from the Filament onFrame callback every render tick.
     * No-op if model hasn't loaded yet.
     */
    fun applyToModel(node: ModelNode) {
        val asset  = node.model  ?: return
        val engine = node.engine

        if (!cacheReady) buildCache(asset, engine)

        val state = latestState

        // ── Head rotation (node-level euler angles in degrees) ───────────────
        // SceneView Node.rotation = Rotation (Float3 euler degrees Y-up Z-forward).
        // As gazeX changes, nose/cheek/jaw shift in real 3D perspective.
        node.rotation = Rotation(
            x = state.eyeGazeY * 5f + state.breathAmount * 1.2f,  // pitch
            y = state.eyeGazeX * 8f,                               // yaw
            z = state.eyeGazeX * -1.2f,                           // subtle roll
        )

        applyMorphs(engine, state)
    }

    private fun applyMorphs(engine: Engine, state: AvatarRenderState) {
        if (entityMorphMaps.isEmpty()) return
        val rm   = engine.renderableManager
        val expr = state.expression

        for ((entity, nameToIdx) in entityMorphMaps) {
            if (!rm.hasComponent(entity)) continue
            val ri    = rm.getInstance(entity)
            val count = rm.getMorphTargetCount(ri)
            if (count == 0) continue

            val w = FloatArray(count)

            // ── Lip sync (TTS amplitude → jaw + lips) ────────────────────────
            nameToIdx[AvatarBlendShapes.JAW_OPEN]?.let {
                w[it] = state.lipSync.coerceIn(0f, 1f)
            }
            nameToIdx[AvatarBlendShapes.MOUTH_UPPER_UP_L]?.let {
                w[it] = (state.lipSync * 0.4f).coerceIn(0f, 1f)
            }
            nameToIdx[AvatarBlendShapes.MOUTH_UPPER_UP_R]?.let {
                w[it] = (state.lipSync * 0.4f).coerceIn(0f, 1f)
            }
            nameToIdx[AvatarBlendShapes.MOUTH_LOWER_DOWN_L]?.let {
                w[it] = (state.lipSync * 0.35f).coerceIn(0f, 1f)
            }
            nameToIdx[AvatarBlendShapes.MOUTH_LOWER_DOWN_R]?.let {
                w[it] = (state.lipSync * 0.35f).coerceIn(0f, 1f)
            }

            // ── Blink ────────────────────────────────────────────────────────
            val blink = state.blinkAmount.coerceIn(0f, 1f)
            nameToIdx[AvatarBlendShapes.EYE_BLINK_L]?.let { w[it] = blink }
            nameToIdx[AvatarBlendShapes.EYE_BLINK_R]?.let { w[it] = blink }

            // ── Eye widen ────────────────────────────────────────────────────
            val widen = expr.eyeWiden.coerceIn(0f, 1f)
            nameToIdx[AvatarBlendShapes.EYE_WIDE_L]?.let { w[it] = widen }
            nameToIdx[AvatarBlendShapes.EYE_WIDE_R]?.let { w[it] = widen }

            // ── Eye look-direction blend shapes ──────────────────────────────
            val gazeX = state.eyeGazeX
            val gazeY = state.eyeGazeY
            if (gazeX > 0f) {
                nameToIdx[AvatarBlendShapes.EYE_LOOK_IN_L]?.let  { w[it] = gazeX * 0.5f }
                nameToIdx[AvatarBlendShapes.EYE_LOOK_OUT_R]?.let { w[it] = gazeX * 0.5f }
            } else {
                nameToIdx[AvatarBlendShapes.EYE_LOOK_OUT_L]?.let { w[it] = (-gazeX) * 0.5f }
                nameToIdx[AvatarBlendShapes.EYE_LOOK_IN_R]?.let  { w[it] = (-gazeX) * 0.5f }
            }
            if (gazeY > 0f) {
                nameToIdx[AvatarBlendShapes.EYE_LOOK_UP_L]?.let   { w[it] = gazeY * 0.5f }
                nameToIdx[AvatarBlendShapes.EYE_LOOK_UP_R]?.let   { w[it] = gazeY * 0.5f }
            } else {
                nameToIdx[AvatarBlendShapes.EYE_LOOK_DOWN_L]?.let { w[it] = (-gazeY) * 0.5f }
                nameToIdx[AvatarBlendShapes.EYE_LOOK_DOWN_R]?.let { w[it] = (-gazeY) * 0.5f }
            }

            // ── Mouth corners ────────────────────────────────────────────────
            nameToIdx[AvatarBlendShapes.MOUTH_SMILE_L]?.let { w[it] = expr.smileLeft.coerceIn(0f, 1f) }
            nameToIdx[AvatarBlendShapes.MOUTH_SMILE_R]?.let { w[it] = expr.smileRight.coerceIn(0f, 1f) }
            nameToIdx[AvatarBlendShapes.MOUTH_FROWN_L]?.let { w[it] = expr.frownLeft.coerceIn(0f, 1f) }
            nameToIdx[AvatarBlendShapes.MOUTH_FROWN_R]?.let { w[it] = expr.frownRight.coerceIn(0f, 1f) }
            nameToIdx[AvatarBlendShapes.CHEEK_SQUINT_L]?.let { w[it] = expr.smileLeft * 0.5f }
            nameToIdx[AvatarBlendShapes.CHEEK_SQUINT_R]?.let { w[it] = expr.smileRight * 0.5f }

            // ── Brows ────────────────────────────────────────────────────────
            val furrow = expr.browFurrow.coerceIn(0f, 1f)
            val raise  = expr.browRaise.coerceIn(0f, 1f)
            nameToIdx[AvatarBlendShapes.BROW_DOWN_L]?.let     { w[it] = furrow }
            nameToIdx[AvatarBlendShapes.BROW_DOWN_R]?.let     { w[it] = furrow }
            nameToIdx[AvatarBlendShapes.BROW_INNER_UP]?.let   { w[it] = raise }
            nameToIdx[AvatarBlendShapes.BROW_OUTER_UP_L]?.let { w[it] = raise * 0.65f }
            nameToIdx[AvatarBlendShapes.BROW_OUTER_UP_R]?.let { w[it] = raise * 0.65f }
            if (furrow > 0.3f) {
                nameToIdx[AvatarBlendShapes.BROW_OUTER_UP_L]?.let {
                    w[it] = (w[it] - furrow * 0.2f).coerceIn(0f, 1f)
                }
                nameToIdx[AvatarBlendShapes.BROW_OUTER_UP_R]?.let {
                    w[it] = (w[it] - furrow * 0.2f).coerceIn(0f, 1f)
                }
            }

            rm.setMorphWeights(ri, w, 0)
        }
    }

    private fun buildCache(asset: FilamentAsset, engine: Engine) {
        val rm = engine.renderableManager
        for (entity in asset.entities) {
            if (!rm.hasComponent(entity)) continue
            val ri    = rm.getInstance(entity)
            val count = rm.getMorphTargetCount(ri)
            if (count == 0) continue

            val names = runCatching { asset.getMorphTargetNames(entity) }.getOrElse { emptyArray() }
            if (names.isEmpty()) continue

            Log.d("AvatarDriver", "GLB entity $entity has ${names.size} morph targets: ${names.take(10).toList()}")

            // Store both original case and lowercase → case-insensitive lookup without runtime cost.
            // RPM exports "eyeBlinkLeft"; some tools export "EyeBlink_L" — both resolve correctly.
            val map = HashMap<String, Int>(names.size * 2)
            names.forEachIndexed { i, name ->
                map[name] = i
                map[name.lowercase()] = i
            }
            entityMorphMaps[entity] = map
        }
        Log.d("AvatarDriver", "Cache built: ${entityMorphMaps.size} entities with morph targets")
        cacheReady = true
    }
}
