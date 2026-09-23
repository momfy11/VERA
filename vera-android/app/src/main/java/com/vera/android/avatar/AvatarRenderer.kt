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
private val FaceBase       = Color(0xFF0D1825)
private val FaceMid        = Color(0xFF162235)
private val ScleraColor    = Color(0xFFBDD8EE)
private val IrisOuter      = Color(0xFF0EA5E9)
private val IrisInner      = Color(0xFF38BDF8)
private val PupilColor     = Color(0xFF040A12)
private val EyeGlow        = Color(0x550EA5E9)
private val LipUpper       = Color(0xFF1B3046)
private val LipLower       = Color(0xFF243D55)
private val MouthInterior  = Color(0xFF030810)
private val CircuitCyan    = Color(0xFF22D3EE)
private val CircuitOrange  = Color(0xFFF97316)
private val SkinShadow     = Color(0xFF06101A)
private val NeckColor      = Color(0xFF0A1520)
private val GlowRing       = Color(0x1A0EA5E9)

/**
 * Draws VERA's digital-human face entirely in Compose Canvas.
 *
 * All values are smoothly animated via [animateFloatAsState] so callers
 * only need to update [renderState] — Compose handles interpolation.
 *
 * Coordinate space: 300×380 dp canvas, face centered at (150, 180).
 *
 * iOS portability: Mirror this in SwiftUI using Canvas { context, size } with
 * Core Graphics paths. Same geometry constants apply.
 */
@Composable
fun VeraAvatar(
    renderState: AvatarRenderState,
    modifier: Modifier = Modifier,
) {
    // Smooth every render value independently for natural transitions
    val browRaise    by animateFloatAsState(renderState.expression.browRaise,    tween(280), label = "browRaise")
    val browFurrow   by animateFloatAsState(renderState.expression.browFurrow,   tween(280), label = "browFurrow")
    val smileLeft    by animateFloatAsState(renderState.expression.smileLeft,    tween(280), label = "smileL")
    val smileRight   by animateFloatAsState(renderState.expression.smileRight,   tween(280), label = "smileR")
    val frownLeft    by animateFloatAsState(renderState.expression.frownLeft,    tween(280), label = "frownL")
    val frownRight   by animateFloatAsState(renderState.expression.frownRight,   tween(280), label = "frownR")
    val eyeWiden     by animateFloatAsState(renderState.expression.eyeWiden,     tween(200), label = "eyeWiden")
    val mouthBase    by animateFloatAsState(renderState.expression.mouthOpenBase,tween(200), label = "mouthBase")
    val blink        by animateFloatAsState(renderState.blinkAmount,             tween(60),  label = "blink")
    val lipSync      by animateFloatAsState(
        maxOf(renderState.lipSync, mouthBase),
        tween(40),
        label = "lip",
    )
    val breathY      by animateFloatAsState(renderState.breathAmount,            tween(80),  label = "breath")
    val gazeX        by animateFloatAsState(renderState.eyeGazeX,               tween(180), label = "gazeX")
    val gazeY        by animateFloatAsState(renderState.eyeGazeY,               tween(180), label = "gazeY")
    val pulse        by animateFloatAsState(renderState.circuitPulse,            tween(400), label = "pulse")

    Canvas(modifier = modifier.size(300.dp, 380.dp)) {
        val breathShift = breathY * 2.5f.dp.toPx()

        drawAmbientGlow()
        drawNeckAndShoulders(breathShift)
        drawHead(breathShift)
        drawFaceShading(breathShift)
        drawCircuitTraces(breathShift, pulse)
        drawNose(breathShift)
        drawEye(left = true,  breathShift, blink, eyeWiden, gazeX, gazeY, browRaise, browFurrow)
        drawEye(left = false, breathShift, blink, eyeWiden, gazeX, gazeY, browRaise, browFurrow)
        drawMouth(breathShift, lipSync, smileLeft, smileRight, frownLeft, frownRight)
    }
}

// ── Geometry helpers ──────────────────────────────────────────────────────────

/** Face center in px, relative to canvas top-left. */
private val DrawScope.faceCX get() = size.width / 2f
private val DrawScope.faceCY get() = size.height * 0.46f

/** Head half-dimensions */
private val DrawScope.headRX get() = 78.dp.toPx()
private val DrawScope.headRY get() = 98.dp.toPx()

// ── Draw sections ─────────────────────────────────────────────────────────────

private fun DrawScope.drawAmbientGlow() {
    val cx = faceCX; val cy = faceCY
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, GlowRing),
            center = Offset(cx, cy),
            radius = headRX * 1.7f,
        ),
        radius = headRX * 1.7f,
        center = Offset(cx, cy),
    )
}

private fun DrawScope.drawNeckAndShoulders(breathShift: Float) {
    val cx = faceCX; val cy = faceCY
    val neckTop = cy + headRY + breathShift
    val neckW = 26.dp.toPx()
    val neckH = 36.dp.toPx()
    val shoulderY = neckTop + neckH
    val shoulderW = 130.dp.toPx()

    val path = Path().apply {
        moveTo(cx - neckW, neckTop)
        lineTo(cx - neckW, neckTop + neckH * 0.6f)
        quadraticBezierTo(cx - shoulderW, shoulderY - 12.dp.toPx(), cx - shoulderW, shoulderY)
        lineTo(cx + shoulderW, shoulderY)
        quadraticBezierTo(cx + shoulderW, shoulderY - 12.dp.toPx(), cx + neckW, neckTop + neckH * 0.6f)
        lineTo(cx + neckW, neckTop)
        close()
    }
    drawPath(path, NeckColor)
    // Collar subtle highlight
    drawPath(path, color = Color(0x1222D3EE), style = Stroke(width = 1.dp.toPx()))
}

private fun DrawScope.drawHead(breathShift: Float) {
    val cx = faceCX; val cy = faceCY + breathShift
    drawOval(
        color = FaceBase,
        topLeft = Offset(cx - headRX, cy - headRY),
        size = Size(headRX * 2f, headRY * 2f),
    )
}

private fun DrawScope.drawFaceShading(breathShift: Float) {
    val cx = faceCX; val cy = faceCY + breathShift
    // Center highlight (subtle 3D convexity)
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(FaceMid, Color.Transparent),
            center = Offset(cx, cy - headRY * 0.05f),
            radius = headRX * 0.9f,
        ),
        topLeft = Offset(cx - headRX, cy - headRY),
        size = Size(headRX * 2f, headRY * 2f),
    )
    // Edge darkening (ambient occlusion)
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, SkinShadow.copy(alpha = 0.55f)),
            center = Offset(cx, cy),
            radius = headRX * 1.05f,
        ),
        topLeft = Offset(cx - headRX, cy - headRY),
        size = Size(headRX * 2f, headRY * 2f),
    )
}

private fun DrawScope.drawCircuitTraces(breathShift: Float, pulse: Float) {
    val cx = faceCX; val cy = faceCY + breathShift
    val lineAlpha = pulse * 0.28f
    val nodePaint = Paint().apply { color = CircuitOrange.copy(alpha = pulse * 0.85f) }

    // Left temple traces (hardcoded branch pattern relative to face)
    val lx = cx - headRX + 4.dp.toPx()   // left edge of face
    val ty = cy - headRY * 0.3f           // upper third
    val stroke = Stroke(width = 0.8f.dp.toPx())

    fun traceLine(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(CircuitCyan.copy(alpha = lineAlpha), Offset(x1, y1), Offset(x2, y2), strokeWidth = 0.8f.dp.toPx())

    // Left side
    traceLine(lx, ty,               lx + 14.dp.toPx(), ty)
    traceLine(lx + 14.dp.toPx(), ty, lx + 14.dp.toPx(), ty + 14.dp.toPx())
    traceLine(lx + 14.dp.toPx(), ty + 14.dp.toPx(), lx + 24.dp.toPx(), ty + 14.dp.toPx())
    traceLine(lx + 14.dp.toPx(), ty, lx + 14.dp.toPx(), ty - 10.dp.toPx())
    traceLine(lx + 14.dp.toPx(), ty - 10.dp.toPx(), lx + 26.dp.toPx(), ty - 10.dp.toPx())

    // Right jaw traces
    val rx = cx + headRX - 4.dp.toPx()
    val jy = cy + headRY * 0.15f
    traceLine(rx, jy,               rx - 14.dp.toPx(), jy)
    traceLine(rx - 14.dp.toPx(), jy, rx - 14.dp.toPx(), jy + 16.dp.toPx())
    traceLine(rx - 14.dp.toPx(), jy + 16.dp.toPx(), rx - 26.dp.toPx(), jy + 16.dp.toPx())
    traceLine(rx - 14.dp.toPx(), jy, rx - 14.dp.toPx(), jy - 12.dp.toPx())
    traceLine(rx - 14.dp.toPx(), jy - 12.dp.toPx(), rx - 28.dp.toPx(), jy - 12.dp.toPx())

    // Circuit node dots (orange, glowing)
    val nodeR = 2.2f.dp.toPx()
    drawContext.canvas.nativeCanvas.apply {
        drawCircle(lx + 26.dp.toPx(), ty - 10.dp.toPx(), nodeR, android.graphics.Paint().apply {
            color = android.graphics.Color.argb((pulse * 210).toInt(), 0xF9, 0x73, 0x16)
            style = android.graphics.Paint.Style.FILL
        })
        drawCircle(lx + 24.dp.toPx(), ty + 14.dp.toPx(), nodeR, android.graphics.Paint().apply {
            color = android.graphics.Color.argb((pulse * 170).toInt(), 0xF9, 0x73, 0x16)
            style = android.graphics.Paint.Style.FILL
        })
        drawCircle(rx - 26.dp.toPx(), jy + 16.dp.toPx(), nodeR, android.graphics.Paint().apply {
            color = android.graphics.Color.argb((pulse * 200).toInt(), 0xF9, 0x73, 0x16)
            style = android.graphics.Paint.Style.FILL
        })
        drawCircle(rx - 28.dp.toPx(), jy - 12.dp.toPx(), nodeR, android.graphics.Paint().apply {
            color = android.graphics.Color.argb((pulse * 160).toInt(), 0xF9, 0x73, 0x16)
            style = android.graphics.Paint.Style.FILL
        })
    }
}

private fun DrawScope.drawNose(breathShift: Float) {
    val cx = faceCX; val cy = faceCY + breathShift
    val noseY = cy + 14.dp.toPx()
    val nostrW = 8.dp.toPx()
    // Two subtle nostril arcs
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(50, 0x06, 0x10, 0x1A)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.5.dp.toPx()
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.apply {
        drawArc(
            android.graphics.RectF(cx - nostrW * 2 - 4.dp.toPx(), noseY - 4.dp.toPx(), cx - 4.dp.toPx(), noseY + 6.dp.toPx()),
            30f, 120f, false, paint,
        )
        drawArc(
            android.graphics.RectF(cx + 4.dp.toPx(), noseY - 4.dp.toPx(), cx + nostrW * 2 + 4.dp.toPx(), noseY + 6.dp.toPx()),
            30f, 120f, false, paint,
        )
    }
}

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
    val cx = faceCX; val cy = faceCY + breathShift
    val side = if (left) -1f else 1f

    val eyeCX = cx + side * 37.dp.toPx()
    val eyeCY = cy - 28.dp.toPx()
    val sockW = 22.dp.toPx()
    val sockH = (14.dp.toPx() + eyeWiden * 4.dp.toPx())
    val irisR = 11.dp.toPx()
    val pupilR = 5.dp.toPx()

    val gazeOffX = gazeX * 4.dp.toPx()
    val gazeOffY = gazeY * 3.dp.toPx()

    // Clip drawing to eye socket shape
    val socketPath = Path().apply {
        addOval(Rect(Offset(eyeCX - sockW, eyeCY - sockH), Size(sockW * 2f, sockH * 2f)))
    }

    clipPath(socketPath) {
        // Sclera
        drawOval(
            color = ScleraColor,
            topLeft = Offset(eyeCX - sockW, eyeCY - sockH),
            size = Size(sockW * 2f, sockH * 2f),
        )
        // Iris — radial gradient (bright center, darker outer)
        val irisCenter = Offset(eyeCX + gazeOffX, eyeCY + gazeOffY)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(IrisInner, IrisOuter, Color(0xFF0260A8)),
                center = irisCenter,
                radius = irisR,
            ),
            radius = irisR,
            center = irisCenter,
        )
        // Pupil
        drawCircle(PupilColor, radius = pupilR, center = irisCenter)
        // Specular highlight
        drawCircle(
            Color.White.copy(alpha = 0.85f),
            radius = 2.5f.dp.toPx(),
            center = Offset(irisCenter.x - 3.dp.toPx(), irisCenter.y - 3.dp.toPx()),
        )
        // Iris glow ring
        drawCircle(
            color = EyeGlow,
            radius = irisR + 3.dp.toPx(),
            center = irisCenter,
            style = Stroke(width = 2.dp.toPx()),
        )

        // Eyelid (blink cover): skin-colored rect sliding down from top
        val lidHeight = blink * sockH * 2f
        drawRect(
            color = FaceBase,
            topLeft = Offset(eyeCX - sockW, eyeCY - sockH),
            size = Size(sockW * 2f, lidHeight),
        )
    }

    // Eye shadow ring (outside socket, subtle)
    drawOval(
        color = SkinShadow.copy(alpha = 0.4f),
        topLeft = Offset(eyeCX - sockW - 3.dp.toPx(), eyeCY - sockH - 2.dp.toPx()),
        size = Size((sockW + 3.dp.toPx()) * 2f, (sockH + 2.dp.toPx()) * 2f),
        style = Stroke(width = 2.dp.toPx()),
    )

    // Brow
    val browXOffset = if (left) -2.dp.toPx() * browFurrow else 2.dp.toPx() * browFurrow
    val browYOffset = -browRaise * 5.dp.toPx() - browFurrow * 2.dp.toPx()
    val browY = eyeCY - sockH - 8.dp.toPx() + browYOffset
    val browW = sockW * 1.1f
    val path = Path().apply {
        val bx = eyeCX + browXOffset
        moveTo(bx - browW, browY + 3.dp.toPx())
        quadraticBezierTo(bx, browY, bx + browW, browY + 3.dp.toPx())
    }
    drawPath(path, color = FaceMid.copy(alpha = 0.9f), style = Stroke(width = 3.5f.dp.toPx(), cap = StrokeCap.Round))
    drawPath(path, color = ScleraColor.copy(alpha = 0.12f), style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.drawMouth(
    breathShift: Float,
    lipSync: Float,
    smileL: Float,
    smileR: Float,
    frownL: Float,
    frownR: Float,
) {
    val cx = faceCX; val cy = faceCY + breathShift
    val mouthCX = cx
    val mouthCY = cy + 44.dp.toPx()
    val halfW = 26.dp.toPx()
    val maxOpen = 16.dp.toPx()
    val openAmount = lipSync * maxOpen

    // Corner vertical offsets: positive = down (frown), negative = up (smile)
    val leftCornerY  = mouthCY - smileL * 5.dp.toPx() + frownL * 5.dp.toPx()
    val rightCornerY = mouthCY - smileR * 5.dp.toPx() + frownR * 5.dp.toPx()

    val upperLipY = mouthCY - openAmount * 0.35f
    val lowerLipY = mouthCY + openAmount * 0.65f

    // Mouth interior (only visible when open)
    if (openAmount > 1.dp.toPx()) {
        val interior = Path().apply {
            moveTo(mouthCX - halfW * 0.85f, upperLipY + 1.dp.toPx())
            quadraticBezierTo(mouthCX, upperLipY - openAmount * 0.1f, mouthCX + halfW * 0.85f, upperLipY + 1.dp.toPx())
            quadraticBezierTo(mouthCX + halfW, (upperLipY + lowerLipY) / 2f, mouthCX + halfW * 0.85f, lowerLipY - 1.dp.toPx())
            quadraticBezierTo(mouthCX, lowerLipY + openAmount * 0.1f, mouthCX - halfW * 0.85f, lowerLipY - 1.dp.toPx())
            quadraticBezierTo(mouthCX - halfW, (upperLipY + lowerLipY) / 2f, mouthCX - halfW * 0.85f, upperLipY + 1.dp.toPx())
            close()
        }
        drawPath(interior, MouthInterior)
    }

    // Upper lip
    val upperLip = Path().apply {
        moveTo(mouthCX - halfW, leftCornerY)
        // Cupid's bow shape
        cubicTo(
            mouthCX - halfW * 0.5f, leftCornerY - 2.dp.toPx(),
            mouthCX - halfW * 0.2f, upperLipY - 4.dp.toPx(),
            mouthCX, upperLipY - 4.dp.toPx(),
        )
        cubicTo(
            mouthCX + halfW * 0.2f, upperLipY - 4.dp.toPx(),
            mouthCX + halfW * 0.5f, rightCornerY - 2.dp.toPx(),
            mouthCX + halfW, rightCornerY,
        )
        quadraticBezierTo(mouthCX, upperLipY + 1.dp.toPx(), mouthCX - halfW, leftCornerY)
        close()
    }
    drawPath(upperLip, LipUpper)
    drawPath(upperLip, color = ScleraColor.copy(alpha = 0.06f), style = Stroke(width = 0.8f.dp.toPx()))

    // Lower lip
    val lowerLip = Path().apply {
        moveTo(mouthCX - halfW, leftCornerY)
        quadraticBezierTo(mouthCX, lowerLipY, mouthCX + halfW, rightCornerY)
        quadraticBezierTo(mouthCX, lowerLipY + 5.dp.toPx(), mouthCX - halfW, leftCornerY)
        close()
    }
    drawPath(lowerLip, LipLower)
    // Lower lip highlight
    val highlight = Path().apply {
        moveTo(mouthCX - halfW * 0.4f, lowerLipY + 1.dp.toPx())
        quadraticBezierTo(mouthCX, lowerLipY + 5.dp.toPx(), mouthCX + halfW * 0.4f, lowerLipY + 1.dp.toPx())
        quadraticBezierTo(mouthCX, lowerLipY + 3.dp.toPx(), mouthCX - halfW * 0.4f, lowerLipY + 1.dp.toPx())
        close()
    }
    drawPath(highlight, color = ScleraColor.copy(alpha = 0.08f))
}
