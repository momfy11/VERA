package com.vera.android.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

/**
 * 3D VERA avatar rendered with Google Filament via SceneView 2.2.1.
 *
 * ── Activation ───────────────────────────────────────────────────────────────
 * Drop GLB at:  app/src/main/assets/vera/vera_head.glb
 * Without file, falls back to 2D Canvas avatar. No crash.
 *
 * ── Expected GLB spec ────────────────────────────────────────────────────────
 * Format:      binary glTF 2.0 (.glb)
 * Blend shapes: ARKit 52 (EyeBlink_L, JawOpen, MouthSmileLeft…) — see AvatarBlendShapes.kt
 * Orientation: Y-up, Z-forward (glTF default)
 * Origin:      centred at eye level (y ≈ 0)
 * Geometry:    head + neck, or bust. Full-body needs camera y/z adjustment.
 *
 * ── Fastest model source ─────────────────────────────────────────────────────
 * readyplayer.me → female → Export GLB → ARKit blend shapes → save as vera_head.glb
 * See assets/vera/PLACE_MODEL_HERE.txt for full instructions.
 *
 * ── iOS port ─────────────────────────────────────────────────────────────────
 * Same GLB + RealityKit ModelEntity + MorpherComponent.
 * AvatarAnimationDriver Rotation = simd_float3 euler (degrees).
 */
@Composable
fun VeraAvatar3D(
    renderState: AvatarRenderState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Check once — fall back to 2D while GLB is absent
    val has3dModel = remember {
        runCatching { context.assets.open("vera/vera_head.glb").close() }.isSuccess
    }
    if (!has3dModel) {
        VeraAvatar(renderState = renderState, modifier = modifier)
        return
    }

    val engine         = rememberEngine()
    val modelLoader    = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    // Directional key light — intensity only; no position for directional lights
    val mainLightNode = rememberMainLightNode(engine) {
        intensity = 120_000f
    }

    // Camera at z=0.65 looks toward –Z by default → points at head at origin
    val cameraNode = rememberCameraNode(engine) {
        position = Position(x = 0f, y = 0.08f, z = 0.65f)
    }

    val driver = remember { AvatarAnimationDriver() }
    SideEffect { driver.latestState = renderState }

    // Node is null until GLB finishes loading
    var headNode by remember { mutableStateOf<ModelNode?>(null) }
    // SnapshotStateList<Node> — Compose observes additions and triggers recompose
    val childNodes = remember { mutableStateListOf<Node>() }

    // SceneView 2.2.1: load via ModelLoader, then construct ModelNode with returned instance
    LaunchedEffect(Unit) {
        modelLoader.loadModelGlbAsync("vera/vera_head.glb") { instance ->
            if (instance != null) {
                ModelNode(
                    engine        = engine,
                    modelInstance = instance,
                    scaleToUnits  = 0.38f,
                ).also { node ->
                    headNode = node
                    childNodes += node
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            headNode?.destroy()
            childNodes.clear()
        }
    }

    Box(
        modifier         = modifier.background(Color(0xFF080812)),
        contentAlignment = Alignment.Center,
    ) {
        Scene(
            modifier       = Modifier.fillMaxSize(),
            engine         = engine,
            modelLoader    = modelLoader,
            materialLoader = materialLoader,
            cameraNode     = cameraNode,
            mainLightNode  = mainLightNode,
            childNodes     = childNodes,
            onFrame        = { _ -> headNode?.let { driver.applyToModel(it) } },
        )
    }
}
