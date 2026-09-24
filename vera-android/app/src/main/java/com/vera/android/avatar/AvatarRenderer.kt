package com.vera.android.avatar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

// ── Palette ───────────────────────────────────────────────────────────────────
private val FaceBase        = Color(0xFF0F1E2C)  // base skin — dark navy
private val FaceMid         = Color(0xFF1A3245)  // midtone for center highlight
private val FaceHighlight   = Color(0xFF243E56)  // brightest facial plane
private val FaceDeep        = Color(0xFF07111C)  // deepest shadow
private val FaceWarm        = Color(0xFF132233)  // slight warmth on cheeks
private val ScleraColor     = Color(0xFFC4DCF0)  // sclera — blue-white
private val IrisLight       = Color(0xFF48C8F8)  // iris center
private val IrisMid         = Color(0xFF0EA5E9)  // iris main
private val IrisDark        = Color(0xFF0768A8)  // iris outer ring
private val LimbalRing      = Color(0xFF052840)  // dark ring at iris edge
private val PupilColor      = Color(0xFF030A12)  // pupil
private val EyeGlow         = Color(0x880EA5E9)  // iris bloom
private val LashShadow      = Color(0xFF040C18)  // eyelash/lid shadow
private val LipUpperCol     = Color(0xFF1C3450)  // upper lip body
private val LipLowerCol     = Color(0xFF28465E)  // lower lip body
private val LipSheen        = Color(0xFF3A6080)  // lip highlight
private val MouthInterior   = Color(0xFF040C18)  // dark mouth interior
private val CircuitCyan     = Color(0xFF22D3EE)  // circuit traces
private val CircuitOrange   = Color(0xFFF97316)  // circuit nodes
private val NeckBase        = Color(0xFF0B1A28)  // neck shadow
private val GlowCyan        = Color(0xFF0EA5E9)  // ambient glow
private val BrowColor       = Color(0xFF1E3B52)  // eyebrow

/**
 * VERA's digital-human face drawn in Compose Canvas.
 *
 * ── Lip-sync contract (DO NOT CHANGE) ────────────────────────────────────────
 *  renderState.lipSync (0–1) → animateFloatAsState(tween(40)) → drawMouth()
 *  openAmount = lipSync * maxOpen  — driven by AvatarLipSyncController/Visualizer
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Canvas: 300 × 380 dp.  Face centred at (width/2, height × 0.46).
 * iOS: replicate in SwiftUI Canvas using identical geometry constants.
 */
@Composable
fun VeraAvatar(
    renderState: AvatarRenderState,
    modifier: Modifier = Modifier,
) {
    val browRaise  by animateFloatAsState(renderState.expression.browRaise,     tween(280), label = "browRaise")
    val browFurrow by animateFloatAsState(renderState.expression.browFurrow,    tween(280), label = "browFurrow")
    val smileLeft  by animateFloatAsState(renderState.expression.smileLeft,     tween(280), label = "smileL")
    val smileRight by animateFloatAsState(renderState.expression.smileRight,    tween(280), label = "smileR")
    val frownLeft  by animateFloatAsState(renderState.expression.frownLeft,     tween(280), label = "frownL")
    val frownRight by animateFloatAsState(renderState.expression.frownRight,    tween(280), label = "frownR")
    val eyeWiden   by animateFloatAsState(renderState.expression.eyeWiden,      tween(200), label = "eyeWiden")
    val mouthBase  by animateFloatAsState(renderState.expression.mouthOpenBase, tween(200), label = "mouthBase")
    val blink      by animateFloatAsState(renderState.blinkAmount,              tween(60),  label = "blink")
    // ── LIP SYNC: tween(40) keeps animation tight to audio ──────────────────
    val lipSync    by animateFloatAsState(
        maxOf(renderState.lipSync, mouthBase),
        tween(40),
        label = "lip",
    )
    val breathY    by animateFloatAsState(renderState.breathAmount,             tween(80),  label = "breath")
    val gazeX      by animateFloatAsState(renderState.eyeGazeX,                tween(180), label = "gazeX")
    val gazeY      by animateFloatAsState(renderState.eyeGazeY,                tween(180), label = "gazeY")
    val pulse      by animateFloatAsState(renderState.circuitPulse,            tween(400), label = "pulse")

    Canvas(modifier = modifier.size(300.dp, 380.dp)) {
        val breathShift = breathY * 2.dp.toPx()

        drawAmbientGlow()
        drawNeckAndShoulders(breathShift)
        drawHeadShape(breathShift)
        drawFaceDepth(breathShift)
        drawCircuitTraces(breathShift, pulse)
        drawNoseStructure(breathShift)
        drawEye(true,  breathShift, blink, eyeWiden, gazeX, gazeY, browRaise, browFurrow)
        drawEye(false, breathShift, blink, eyeWiden, gazeX, gazeY, browRaise, browFurrow)
        drawMouth(breathShift, lipSync, smileLeft, smileRight, frownLeft, frownRight)
    }
}

// ── Geometry ──────────────────────────────────────────────────────────────────

private val DrawScope.cx get() = size.width  / 2f
private val DrawScope.cy get() = size.height * 0.46f
private val DrawScope.w  get() = 78.dp.toPx()  // head half-width
private val DrawScope.h  get() = 98.dp.toPx()  // head half-height

// ── Ambient glow ─────────────────────────────────────────────────────────────

private fun DrawScope.drawAmbientGlow() {
    // Outer halo — very faint so it doesn't compete with face
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, GlowCyan.copy(alpha = 0.07f)),
            center = Offset(cx, cy),
            radius = w * 1.8f,
        ),
        radius = w * 1.8f,
        center = Offset(cx, cy),
    )
    // Inner rim light — thin bright ring just outside head silhouette
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, GlowCyan.copy(alpha = 0.18f), Color.Transparent),
            center = Offset(cx, cy),
            radius = w * 1.08f,
        ),
        radius = w * 1.08f,
        center = Offset(cx, cy),
    )
}

// ── Neck + shoulders ──────────────────────────────────────────────────────────

private fun DrawScope.drawNeckAndShoulders(breathShift: Float) {
    val neckTop   = cy + h + breathShift
    val neckW     = 24.dp.toPx()
    val neckH     = 40.dp.toPx()
    val sholderY  = neckTop + neckH
    val sholderW  = 140.dp.toPx()

    val body = Path().apply {
        moveTo(cx - neckW, neckTop)
        lineTo(cx - neckW, neckTop + neckH * 0.5f)
        cubicTo(
            cx - neckW * 1.3f, neckTop + neckH * 0.7f,
            cx - sholderW * 0.6f, sholderY - 20.dp.toPx(),
            cx - sholderW, sholderY,
        )
        lineTo(cx + sholderW, sholderY)
        cubicTo(
            cx + sholderW * 0.6f, sholderY - 20.dp.toPx(),
            cx + neckW * 1.3f, neckTop + neckH * 0.7f,
            cx + neckW, neckTop + neckH * 0.5f,
        )
        lineTo(cx + neckW, neckTop)
        close()
    }
    drawPath(body, NeckBase)

    // Subtle collar/chest tech highlight
    drawPath(body, color = CircuitCyan.copy(alpha = 0.08f), style = Stroke(width = 0.8f.dp.toPx()))

    // Neck centre shadow (adds cylinder depth)
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, FaceDeep.copy(alpha = 0.4f), Color.Transparent),
            startX = cx - neckW * 0.6f,
            endX   = cx + neckW * 0.6f,
        ),
        topLeft = Offset(cx - neckW * 0.6f, neckTop),
        size    = Size(neckW * 1.2f, neckH),
    )
}

// ── Head silhouette ───────────────────────────────────────────────────────────
// Human-shaped path: wider at temples, tapers to chin. Not a pure oval.

private fun DrawScope.drawHeadShape(breathShift: Float) {
    val fcx = cx; val fcy = cy + breathShift
    val fw = w; val fh = h

    val headPath = Path().apply {
        moveTo(fcx, fcy - fh)                       // crown centre

        // ── Right side: forehead → temple → jaw → chin ─────────────────
        cubicTo(
            fcx + fw * 0.62f, fcy - fh,
            fcx + fw * 1.04f, fcy - fh * 0.42f,
            fcx + fw * 1.00f, fcy - fh * 0.08f,    // right temple
        )
        cubicTo(
            fcx + fw * 0.97f, fcy + fh * 0.22f,
            fcx + fw * 0.84f, fcy + fh * 0.56f,
            fcx + fw * 0.50f, fcy + fh * 0.82f,    // jaw curve
        )
        cubicTo(
            fcx + fw * 0.28f, fcy + fh * 0.96f,
            fcx + fw * 0.12f, fcy + fh,
            fcx,              fcy + fh,             // chin centre
        )

        // ── Left side: mirror ──────────────────────────────────────────
        cubicTo(
            fcx - fw * 0.12f, fcy + fh,
            fcx - fw * 0.28f, fcy + fh * 0.96f,
            fcx - fw * 0.50f, fcy + fh * 0.82f,
        )
        cubicTo(
            fcx - fw * 0.84f, fcy + fh * 0.56f,
            fcx - fw * 0.97f, fcy + fh * 0.22f,
            fcx - fw * 1.00f, fcy - fh * 0.08f,
        )
        cubicTo(
            fcx - fw * 1.04f, fcy - fh * 0.42f,
            fcx - fw * 0.62f, fcy - fh,
            fcx,              fcy - fh,
        )
        close()
    }
    drawPath(headPath, FaceBase)
}

// ── Multi-layer face depth (replaces simple 2-gradient shading) ───────────────

private fun DrawScope.drawFaceDepth(breathShift: Float) {
    val fcx = cx; val fcy = cy + breathShift
    val fw = w; val fh = h

    // 1. Central face highlight — nose bridge area catches most light
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(FaceMid.copy(alpha = 0.9f), Color.Transparent),
            center = Offset(fcx, fcy - fh * 0.05f),
            radius = fw * 0.65f,
        ),
        radius = fw * 0.65f,
        center = Offset(fcx, fcy - fh * 0.05f),
    )

    // 2. Forehead highlight (upper central)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(FaceHighlight.copy(alpha = 0.35f), Color.Transparent),
            center = Offset(fcx, fcy - fh * 0.55f),
            radius = fw * 0.5f,
        ),
        radius = fw * 0.5f,
        center = Offset(fcx, fcy - fh * 0.55f),
    )

    // 3. Left cheekbone highlight
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(FaceWarm.copy(alpha = 0.55f), Color.Transparent),
            center = Offset(fcx - fw * 0.42f, fcy + fh * 0.05f),
            radius = fw * 0.38f,
        ),
        radius = fw * 0.38f,
        center = Offset(fcx - fw * 0.42f, fcy + fh * 0.05f),
    )

    // 4. Right cheekbone highlight
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(FaceWarm.copy(alpha = 0.55f), Color.Transparent),
            center = Offset(fcx + fw * 0.42f, fcy + fh * 0.05f),
            radius = fw * 0.38f,
        ),
        radius = fw * 0.38f,
        center = Offset(fcx + fw * 0.42f, fcy + fh * 0.05f),
    )

    // 5. Left temple shadow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(FaceDeep.copy(alpha = 0.55f), Color.Transparent),
            center = Offset(fcx - fw * 0.80f, fcy - fh * 0.25f),
            radius = fw * 0.45f,
        ),
        radius = fw * 0.45f,
        center = Offset(fcx - fw * 0.80f, fcy - fh * 0.25f),
    )

    // 6. Right temple shadow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(FaceDeep.copy(alpha = 0.55f), Color.Transparent),
            center = Offset(fcx + fw * 0.80f, fcy - fh * 0.25f),
            radius = fw * 0.45f,
        ),
        radius = fw * 0.45f,
        center = Offset(fcx + fw * 0.80f, fcy - fh * 0.25f),
    )

    // 7. Under-jaw shadow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(FaceDeep.copy(alpha = 0.5f), Color.Transparent),
            center = Offset(fcx, fcy + fh * 0.78f),
            radius = fw * 0.65f,
        ),
        radius = fw * 0.65f,
        center = Offset(fcx, fcy + fh * 0.78f),
    )

    // 8. Chin tip highlight — subtle catch-light
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(FaceMid.copy(alpha = 0.5f), Color.Transparent),
            center = Offset(fcx, fcy + fh * 0.90f),
            radius = fw * 0.18f,
        ),
        radius = fw * 0.18f,
        center = Offset(fcx, fcy + fh * 0.90f),
    )

    // 9. Edge darkening — wraps entire face silhouette
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, FaceDeep.copy(alpha = 0.45f)),
            center = Offset(fcx, fcy),
            radius = fw * 1.08f,
        ),
        radius = fw * 1.08f,
        center = Offset(fcx, fcy),
    )
}

// ── Circuit traces ────────────────────────────────────────────────────────────

private fun DrawScope.drawCircuitTraces(breathShift: Float, pulse: Float) {
    val fcx = cx; val fcy = cy + breathShift
    val fw = w; val fh = h
    val lineAlpha = pulse * 0.22f
    val sw = 0.7f.dp.toPx()

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(CircuitCyan.copy(alpha = lineAlpha), Offset(x1, y1), Offset(x2, y2), sw)

    fun node(x: Float, y: Float, alpha: Float) {
        drawCircle(CircuitCyan.copy(alpha = alpha * pulse), 1.6f.dp.toPx(), Offset(x, y))
        drawCircle(CircuitOrange.copy(alpha = alpha * pulse * 0.9f), 1.2f.dp.toPx(), Offset(x, y))
    }

    // Left temple branch
    val lx = fcx - fw * 0.88f
    val lt = fcy - fh * 0.28f
    line(lx, lt,           lx + 12.dp.toPx(), lt)
    line(lx + 12.dp.toPx(), lt, lx + 12.dp.toPx(), lt + 12.dp.toPx())
    line(lx + 12.dp.toPx(), lt + 12.dp.toPx(), lx + 22.dp.toPx(), lt + 12.dp.toPx())
    line(lx + 12.dp.toPx(), lt, lx + 12.dp.toPx(), lt - 9.dp.toPx())
    line(lx + 12.dp.toPx(), lt - 9.dp.toPx(), lx + 24.dp.toPx(), lt - 9.dp.toPx())
    node(lx + 24.dp.toPx(), lt - 9.dp.toPx(), 0.85f)
    node(lx + 22.dp.toPx(), lt + 12.dp.toPx(), 0.7f)

    // Right jaw branch
    val rx = fcx + fw * 0.85f
    val rj = fcy + fh * 0.18f
    line(rx, rj,           rx - 12.dp.toPx(), rj)
    line(rx - 12.dp.toPx(), rj, rx - 12.dp.toPx(), rj + 14.dp.toPx())
    line(rx - 12.dp.toPx(), rj + 14.dp.toPx(), rx - 24.dp.toPx(), rj + 14.dp.toPx())
    line(rx - 12.dp.toPx(), rj, rx - 12.dp.toPx(), rj - 10.dp.toPx())
    line(rx - 12.dp.toPx(), rj - 10.dp.toPx(), rx - 26.dp.toPx(), rj - 10.dp.toPx())
    node(rx - 24.dp.toPx(), rj + 14.dp.toPx(), 0.8f)
    node(rx - 26.dp.toPx(), rj - 10.dp.toPx(), 0.65f)

    // Forehead right — subtle single horizontal trace above brow
    val frx = fcx + fw * 0.18f
    val frt = fcy - fh * 0.62f
    line(frx, frt, frx + 18.dp.toPx(), frt)
    line(frx + 18.dp.toPx(), frt, frx + 18.dp.toPx(), frt + 8.dp.toPx())
    node(frx + 18.dp.toPx(), frt + 8.dp.toPx(), 0.5f)
}

// ── Nose ──────────────────────────────────────────────────────────────────────

private fun DrawScope.drawNoseStructure(breathShift: Float) {
    val fcx = cx; val fcy = cy + breathShift
    val fw = w; val fh = h

    val noseTopY = fcy - fh * 0.07f   // where bridge meets brow
    val noseTipY = fcy + fh * 0.13f   // tip of nose
    val nostrY   = fcy + fh * 0.18f   // nostril level
    val nostrW   = 9.dp.toPx()

    // Nose bridge shadow — narrow vertical darkening
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, FaceDeep.copy(alpha = 0.22f), Color.Transparent),
            startX = fcx - 5.dp.toPx(),
            endX   = fcx + 5.dp.toPx(),
        ),
        topLeft = Offset(fcx - 5.dp.toPx(), noseTopY),
        size    = Size(10.dp.toPx(), noseTipY - noseTopY),
    )

    // Nose tip highlight
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(FaceMid.copy(alpha = 0.55f), Color.Transparent),
            center = Offset(fcx, noseTipY),
            radius = 9.dp.toPx(),
        ),
        radius = 9.dp.toPx(),
        center = Offset(fcx, noseTipY),
    )

    // Nostril wings — more natural arcs using nativeCanvas
    val nativePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.4f.dp.toPx()
        color = android.graphics.Color.argb(70, 0x07, 0x11, 0x1C)
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    drawContext.canvas.nativeCanvas.apply {
        // Left nostril
        drawArc(
            android.graphics.RectF(
                fcx - nostrW * 2.2f - 3.dp.toPx(), nostrY - 5.dp.toPx(),
                fcx - 3.dp.toPx(), nostrY + 7.dp.toPx()
            ),
            25f, 130f, false, nativePaint
        )
        // Right nostril
        drawArc(
            android.graphics.RectF(
                fcx + 3.dp.toPx(), nostrY - 5.dp.toPx(),
                fcx + nostrW * 2.2f + 3.dp.toPx(), nostrY + 7.dp.toPx()
            ),
            25f, 130f, false, nativePaint
        )
        // Columella (divider between nostrils) — very faint
        nativePaint.alpha = 40
        nativePaint.strokeWidth = 1.dp.toPx()
        drawLine(fcx, noseTipY + 2.dp.toPx(), fcx, nostrY + 3.dp.toPx(), nativePaint)
    }

    // Side shading alongside nose — nasolabial shadow hint
    val nlAlpha = 0.18f
    drawLine(
        color = FaceDeep.copy(alpha = nlAlpha),
        start = Offset(fcx - nostrW * 1.4f, noseTipY + 2.dp.toPx()),
        end   = Offset(fcx - nostrW * 1.8f, noseTipY + fh * 0.22f),
        strokeWidth = 3.dp.toPx(),
    )
    drawLine(
        color = FaceDeep.copy(alpha = nlAlpha),
        start = Offset(fcx + nostrW * 1.4f, noseTipY + 2.dp.toPx()),
        end   = Offset(fcx + nostrW * 1.8f, noseTipY + fh * 0.22f),
        strokeWidth = 3.dp.toPx(),
    )
}

// ── Eye ───────────────────────────────────────────────────────────────────────

private fun DrawScope.drawEye(
    left: Boolean,
    breathShift: Float,
    blink: Float,
    eyeWiden: Float,
    gazeX: Float,
    gazeY: Float,
    browRaise: Float,
    browFurrow: Float,
) {
    val fcx = cx; val fcy = cy + breathShift
    val side = if (left) -1f else 1f

    val eyeCX  = fcx + side * 36.dp.toPx()
    val eyeCY  = fcy - 32.dp.toPx()
    // Almond proportions: wider horizontally than vertically
    val sockW  = 23.dp.toPx()
    val sockH  = (12.dp.toPx() + eyeWiden * 3.5f.dp.toPx())
    val irisR  = 10.dp.toPx()
    val pupilR = 4.5f.dp.toPx()

    val gazeOffX = gazeX * 4.dp.toPx()
    val gazeOffY = gazeY * 2.5f.dp.toPx()
    val irisCenter = Offset(eyeCX + gazeOffX, eyeCY + gazeOffY)

    // ── Almond-shaped clip path ─────────────────────────────────────────
    // Upper lid peaks toward outer third; lower lid is flatter.
    val almondPath = Path().apply {
        val innerX = eyeCX - sockW
        val outerX = eyeCX + sockW * side.let { if (left) 1f else 1f } // always outerX > innerX for right
        // inner corner slightly lower than outer corner
        moveTo(eyeCX - sockW, eyeCY + sockH * 0.18f)   // inner corner
        // upper lid — peak at outer 55%
        cubicTo(
            eyeCX - sockW * 0.3f, eyeCY - sockH * 0.85f,
            eyeCX + sockW * 0.4f, eyeCY - sockH * 1.0f,
            eyeCX + sockW, eyeCY - sockH * 0.15f,        // outer corner
        )
        // lower lid — flatter
        cubicTo(
            eyeCX + sockW * 0.5f, eyeCY + sockH * 0.75f,
            eyeCX - sockW * 0.2f, eyeCY + sockH * 0.65f,
            eyeCX - sockW, eyeCY + sockH * 0.18f,
        )
        close()
    }

    // ── Under-eye shadow (below the socket, outside clip) ─────────────
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(FaceDeep.copy(alpha = 0.3f), Color.Transparent),
            center = Offset(eyeCX, eyeCY + sockH + 3.dp.toPx()),
            radius = sockW * 1.1f,
        ),
        topLeft = Offset(eyeCX - sockW * 1.1f, eyeCY),
        size    = Size(sockW * 2.2f, sockH * 2.2f),
    )

    clipPath(almondPath) {
        // Sclera
        drawOval(
            color   = ScleraColor,
            topLeft = Offset(eyeCX - sockW, eyeCY - sockH),
            size    = Size(sockW * 2f, sockH * 2f),
        )
        // Inner corner slightly tinted (pink-grey naturalises sclera)
        drawCircle(
            color  = Color(0xFF8AAABB).copy(alpha = 0.3f),
            radius = sockW * 0.35f,
            center = Offset(eyeCX - sockW * 0.7f, eyeCY + sockH * 0.1f),
        )
        // Iris — multi-stop radial gradient for realism
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(IrisLight, IrisMid, IrisDark, LimbalRing),
                center = irisCenter,
                radius = irisR,
            ),
            radius = irisR,
            center = irisCenter,
        )
        // Pupil
        drawCircle(PupilColor, pupilR, irisCenter)
        // Primary specular (bright)
        drawCircle(
            Color.White.copy(alpha = 0.9f),
            2.8f.dp.toPx(),
            Offset(irisCenter.x - 2.8f.dp.toPx(), irisCenter.y - 2.8f.dp.toPx()),
        )
        // Secondary specular (dim, opposite side — adds depth)
        drawCircle(
            Color.White.copy(alpha = 0.25f),
            1.5f.dp.toPx(),
            Offset(irisCenter.x + 2.5f.dp.toPx(), irisCenter.y + 2.2f.dp.toPx()),
        )
        // Iris bloom / limbal glow
        drawCircle(
            color  = EyeGlow,
            radius = irisR + 2.5f.dp.toPx(),
            center = irisCenter,
            style  = Stroke(width = 1.5f.dp.toPx()),
        )

        // ── Blink: upper eyelid slides down with a natural arc ─────────
        if (blink > 0.01f) {
            val lidDropY = eyeCY - sockH + blink * (sockH * 2.3f)
            val lidPath = Path().apply {
                moveTo(eyeCX - sockW * 1.1f, eyeCY - sockH * 1.4f)
                lineTo(eyeCX + sockW * 1.1f, eyeCY - sockH * 1.4f)
                lineTo(eyeCX + sockW * 1.1f, lidDropY + sockH * 0.25f)
                cubicTo(
                    eyeCX + sockW * 0.3f, lidDropY - sockH * 0.05f,
                    eyeCX - sockW * 0.3f, lidDropY - sockH * 0.05f,
                    eyeCX - sockW * 1.1f, lidDropY + sockH * 0.25f,
                )
                close()
            }
            drawPath(lidPath, FaceBase)
        }
    }

    // ── Eyelash shadow — dark gradient along upper lid (outside clip) ──
    drawOval(
        brush = Brush.verticalGradient(
            colors = listOf(LashShadow.copy(alpha = 0.7f), Color.Transparent),
            startY = eyeCY - sockH - 2.dp.toPx(),
            endY   = eyeCY - sockH + 6.dp.toPx(),
        ),
        topLeft = Offset(eyeCX - sockW * 1.05f, eyeCY - sockH - 2.dp.toPx()),
        size    = Size(sockW * 2.1f, sockH),
    )

    // ── Brow ───────────────────────────────────────────────────────────
    val innerBrowPull = if (left) browFurrow * 3.dp.toPx() else -browFurrow * 3.dp.toPx()
    val browRiseOff   = -browRaise * 6.dp.toPx() - browFurrow * 2.dp.toPx()
    val browY         = eyeCY - sockH - 9.dp.toPx() + browRiseOff
    val browW         = sockW * 1.05f
    val bx            = eyeCX + innerBrowPull

    // Brow fill — slightly tapered (thicker medial, thinner lateral)
    val browPath = Path().apply {
        moveTo(bx - browW, browY + 4.5f.dp.toPx())
        cubicTo(bx - browW * 0.4f, browY + 1.dp.toPx(), bx + browW * 0.3f, browY, bx + browW, browY + 3.dp.toPx())
        cubicTo(bx + browW * 0.3f, browY + 4.dp.toPx(), bx - browW * 0.4f, browY + 6.dp.toPx(), bx - browW, browY + 7.dp.toPx())
        close()
    }
    drawPath(browPath, BrowColor.copy(alpha = 0.85f))
    // Highlight on brow (upper edge catch-light)
    val browHiPath = Path().apply {
        moveTo(bx - browW * 0.7f, browY + 1.5f.dp.toPx())
        cubicTo(bx, browY - 0.5f.dp.toPx(), bx + browW * 0.5f, browY + 0.5f.dp.toPx(), bx + browW * 0.9f, browY + 2.5f.dp.toPx())
    }
    drawPath(browHiPath, color = ScleraColor.copy(alpha = 0.14f), style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))
}

// ── Mouth — LIP SYNC PRESERVED ───────────────────────────────────────────────
//
// CONTRACT (do not change):
//   openAmount = lipSync * maxOpen
//   lipSync is animateFloatAsState(tween(40)) driven by AvatarLipSyncController
//   upperLipY = mouthCY - openAmount * 0.35f
//   lowerLipY = mouthCY + openAmount * 0.65f
//
private fun DrawScope.drawMouth(
    breathShift: Float,
    lipSync: Float,          // ← from Visualizer amplitude, DO NOT REMOVE
    smileL: Float,
    smileR: Float,
    frownL: Float,
    frownR: Float,
) {
    val fcx = cx; val fcy = cy + breathShift
    val fh = h

    val mouthCX = fcx
    val mouthCY = fcy + fh * 0.48f    // lower third of face
    val halfW   = 30.dp.toPx()        // slightly wider than original 26dp
    val maxOpen = 18.dp.toPx()        // max opening range
    // ── CORE LIP-SYNC MATH (preserved exactly) ────────────────────────────────
    val openAmount  = lipSync * maxOpen
    val leftCornerY  = mouthCY - smileL * 6.dp.toPx() + frownL * 6.dp.toPx()
    val rightCornerY = mouthCY - smileR * 6.dp.toPx() + frownR * 6.dp.toPx()
    val upperLipY    = mouthCY - openAmount * 0.35f
    val lowerLipY    = mouthCY + openAmount * 0.65f
    // ─────────────────────────────────────────────────────────────────────────

    // Philtrum shadow (groove above upper lip)
    drawLine(
        color       = FaceDeep.copy(alpha = 0.28f),
        start       = Offset(fcx, fcy + fh * 0.35f),
        end         = Offset(fcx, upperLipY - 2.dp.toPx()),
        strokeWidth = 3.dp.toPx(),
    )

    // Mouth interior — only rendered when open
    if (openAmount > 0.8f.dp.toPx()) {
        val interior = Path().apply {
            moveTo(mouthCX - halfW * 0.82f, upperLipY + 1.5f.dp.toPx())
            cubicTo(
                mouthCX - halfW * 0.3f, upperLipY - openAmount * 0.12f,
                mouthCX + halfW * 0.3f, upperLipY - openAmount * 0.12f,
                mouthCX + halfW * 0.82f, upperLipY + 1.5f.dp.toPx(),
            )
            cubicTo(
                mouthCX + halfW * 0.95f, (upperLipY + lowerLipY) * 0.5f,
                mouthCX + halfW * 0.82f, lowerLipY - 1.5f.dp.toPx(),
                mouthCX, lowerLipY + openAmount * 0.08f,
            )
            cubicTo(
                mouthCX - halfW * 0.82f, lowerLipY - 1.5f.dp.toPx(),
                mouthCX - halfW * 0.95f, (upperLipY + lowerLipY) * 0.5f,
                mouthCX - halfW * 0.82f, upperLipY + 1.5f.dp.toPx(),
            )
            close()
        }
        drawPath(interior, MouthInterior)
        // Teeth suggestion — very subtle white strip inside upper opening
        if (openAmount > 4.dp.toPx()) {
            val teethH = minOf(openAmount * 0.25f, 4.dp.toPx())
            drawRect(
                brush   = Brush.verticalGradient(
                    colors = listOf(Color(0xFFB0C8D8).copy(alpha = 0.3f), Color.Transparent),
                    startY = upperLipY + 2.dp.toPx(),
                    endY   = upperLipY + 2.dp.toPx() + teethH,
                ),
                topLeft = Offset(mouthCX - halfW * 0.6f, upperLipY + 2.dp.toPx()),
                size    = Size(halfW * 1.2f, teethH),
            )
        }
    }

    // Upper lip — Cupid's bow with more pronounced peaks
    val upperLip = Path().apply {
        moveTo(mouthCX - halfW, leftCornerY)
        // Left half: up to left peak, through philtrum dip, to centre
        cubicTo(
            mouthCX - halfW * 0.62f, leftCornerY - 1.5f.dp.toPx(),
            mouthCX - halfW * 0.35f, upperLipY - 5.dp.toPx(),
            mouthCX - halfW * 0.14f, upperLipY - 5.5f.dp.toPx(),   // left bow peak
        )
        cubicTo(
            mouthCX - halfW * 0.05f, upperLipY - 5.5f.dp.toPx(),
            mouthCX + halfW * 0.05f, upperLipY - 4.dp.toPx(),
            mouthCX,                 upperLipY - 3.5f.dp.toPx(),    // cupid centre dip
        )
        // Right half: centre dip → right peak (mirror of left) → corner
        cubicTo(
            mouthCX + halfW * 0.04f, upperLipY - 3.5f.dp.toPx(),
            mouthCX + halfW * 0.12f, upperLipY - 5.2f.dp.toPx(),
            mouthCX + halfW * 0.14f, upperLipY - 5.5f.dp.toPx(),   // right bow peak
        )
        cubicTo(
            mouthCX + halfW * 0.35f, upperLipY - 5.dp.toPx(),
            mouthCX + halfW * 0.62f, rightCornerY - 1.5f.dp.toPx(),
            mouthCX + halfW, rightCornerY,
        )
        // Close back along inner upper lip edge
        cubicTo(
            mouthCX + halfW * 0.4f, upperLipY + 2.dp.toPx(),
            mouthCX - halfW * 0.4f, upperLipY + 2.dp.toPx(),
            mouthCX - halfW, leftCornerY,
        )
        close()
    }
    drawPath(upperLip, LipUpperCol)

    // Lower lip — fuller, with central highlight
    val lowerLip = Path().apply {
        moveTo(mouthCX - halfW, leftCornerY)
        cubicTo(
            mouthCX - halfW * 0.55f, lowerLipY + 1.dp.toPx(),
            mouthCX + halfW * 0.55f, lowerLipY + 1.dp.toPx(),
            mouthCX + halfW, rightCornerY,
        )
        // Inner edge of lower lip
        cubicTo(
            mouthCX + halfW * 0.4f, lowerLipY + 7.dp.toPx(),
            mouthCX - halfW * 0.4f, lowerLipY + 7.dp.toPx(),
            mouthCX - halfW, leftCornerY,
        )
        close()
    }
    drawPath(lowerLip, LipLowerCol)

    // Lower lip central highlight — subtle sheen
    val lowerHi = Path().apply {
        moveTo(mouthCX - halfW * 0.42f, lowerLipY + 2.dp.toPx())
        cubicTo(
            mouthCX - halfW * 0.18f, lowerLipY + 1.dp.toPx(),
            mouthCX + halfW * 0.18f, lowerLipY + 1.dp.toPx(),
            mouthCX + halfW * 0.42f, lowerLipY + 2.dp.toPx(),
        )
        cubicTo(
            mouthCX + halfW * 0.18f, lowerLipY + 5.dp.toPx(),
            mouthCX - halfW * 0.18f, lowerLipY + 5.dp.toPx(),
            mouthCX - halfW * 0.42f, lowerLipY + 2.dp.toPx(),
        )
        close()
    }
    drawPath(lowerHi, LipSheen.copy(alpha = 0.35f))

    // Lip line — thin dark border at lip boundary for definition
    val lipBorder = Path().apply {
        moveTo(mouthCX - halfW, leftCornerY)
        cubicTo(
            mouthCX - halfW * 0.45f, upperLipY + 2.dp.toPx(),
            mouthCX + halfW * 0.45f, upperLipY + 2.dp.toPx(),
            mouthCX + halfW, rightCornerY,
        )
    }
    drawPath(lipBorder, color = FaceDeep.copy(alpha = 0.4f), style = Stroke(width = 0.8f.dp.toPx(), cap = StrokeCap.Round))
}
