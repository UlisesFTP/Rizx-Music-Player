package fm.rizx.player.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val TWO_PI = (2.0 * Math.PI).toFloat()

/**
 * Animated **monochrome dot-matrix field** — a grid of dots whose brightness and size ripple along
 * two crossing diagonal waves, so the whole field shimmers like a Nothing-OS glyph panel. Drawn
 * behind the dark Now Playing controls (replaces the album-colour aurora). Colours come from theme
 * tokens ([baseColor] = `dot`, [litColor] = `dotOn`), so it works on Paper and Ivory alike.
 */
@Composable
fun DotMatrixField(
    baseColor: Color,
    litColor: Color,
    modifier: Modifier = Modifier,
    spacing: Dp = 30.dp,
    dotRadius: Dp = 2.2.dp,
    periodMs: Int = 7200,
) {
    val transition = rememberInfiniteTransition(label = "dotField")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "dotFieldPhase",
    )
    Canvas(modifier) {
        val s = spacing.toPx()
        if (s <= 0f) return@Canvas
        val r = dotRadius.toPx()
        val cols = (size.width / s).toInt() + 1
        val rows = (size.height / s).toInt() + 1
        // Coarser grid + a single traveling diagonal wave (one sin per dot, hoisted per row) keeps the
        // per-frame cost low so it never starves audio/decoding on low-end GPUs and emulators.
        for (row in 0..rows) {
            val rowPhase = phase + row * 0.5f
            val y = row * s
            for (col in 0..cols) {
                val b = sin(rowPhase + col * 0.5f) * 0.5f + 0.5f
                drawCircle(
                    color = lerp(baseColor, litColor, b * b),
                    radius = r * (0.75f + 0.5f * b),
                    center = Offset(col * s, y),
                )
            }
        }
    }
}

/**
 * Nothing-OS loader: [dotCount] dots on a ring with a brightness "comet" rotating around them.
 * Monochrome — pass a single [color]; drop-in replacement for a `CircularProgressIndicator`.
 */
@Composable
fun DotMatrixSpinner(
    color: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 28.dp,
    dotCount: Int = 8,
    periodMs: Int = 900,
) {
    val transition = rememberInfiniteTransition(label = "dotSpinner")
    val head by transition.animateFloat(
        initialValue = 0f,
        targetValue = dotCount.toFloat(),
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "dotSpinnerHead",
    )
    Canvas(modifier.size(diameter)) {
        val ring = size.minDimension / 2f * 0.82f
        val dotR = size.minDimension * 0.085f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val halfCount = dotCount / 2f
        for (i in 0 until dotCount) {
            val angle = (i.toFloat() / dotCount) * TWO_PI - TWO_PI / 4f
            var d = abs(i - head)
            d = min(d, dotCount - d)
            val b = (1f - d / halfCount).coerceIn(0f, 1f)
            drawCircle(
                color = color.copy(alpha = 0.18f + 0.82f * b * b),
                radius = dotR * (0.6f + 0.7f * b),
                center = Offset(cx + cos(angle) * ring, cy + sin(angle) * ring),
            )
        }
    }
}

