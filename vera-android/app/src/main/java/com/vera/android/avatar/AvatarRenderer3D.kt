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
 * Blend shapes: ARKit 52 — EyeBlink_L/R, JawOpen, MouthSmile/Frown L/R, etc.
 * Orientation: Y-up, Z-forward (glTF default)
 * Origin:      centred at eye level (y ≈ 0)
 *
 * ── SceneView 2.2.1 API notes (do not change without re-verifying) ────────────
 * ModelNode(modelInstance: FilamentInstance, autoAnimate, scaleToUnits, centerOrigin)
 *   — NO engine param; engine is derived from the FilamentInstance.
 * ModelLoader.loadModelInstanceAsync(path) { filamentInstance -> ... }
 *   — second param (resourceResolver) is optional with default; trailing lambda = onLoaded.
 *
 * ── iOS port ─────────────────────────────────────────────────────────────────
 * Same GLB + RealityKit ModelEntity + MorpherComponent.
 */
@Composable
fun VeraAvatar3D(
    renderState: AvatarRenderState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

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

    val mainLightNode = rememberMainLightNode(engine) {
        intensity = 120_000f
    }

    val cameraNode = rememberCameraNode(engine) {
        position = Position(x = 0f, y = 0.08f, z = 0.65f)
    }

    val driver = remember { AvatarAnimationDriver() }
    SideEffect { driver.latestState = renderState }

    var headNode by remember { mutableStateOf<ModelNode?>(null) }
    val childNodes = remember { mutableStateListOf<Node>() }

    LaunchedEffect(Unit) {
        // loadModelInstanceAsync: (String, resourceResolver?, (FilamentInstance)->Unit) -> Job
        // resourceResolver has a default; trailing lambda = onLoaded callback.
        // Callback delivers ModelInstance? (nullable platform type from Java generics).
        modelLoader.loadModelInstanceAsync("vera/vera_head.glb") { instance ->
            if (instance == null) return@loadModelInstanceAsync
            // ModelNode takes ModelInstance (non-null) — smart cast applies after null check.
            // No engine param: engine is derived from the ModelInstance internally.
            ModelNode(
                modelInstance = instance,
                autoAnimate   = false,
                scaleToUnits  = 0.38f,
            ).also { node ->
                headNode = node
                childNodes += node
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
