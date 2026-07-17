package fm.rizx.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A few faint dots drifting between the Now Playing background grid points, each with a short comet
 * **trail** (fading dots behind the head). Rendered at the same faint alpha as the static `dotGrid`, so
 * they read as part of the diffuse backdrop rather than a foreground effect.
 *
 * **Audio-safe by construction:** each dot/trail-segment is a tiny [Box] moved via `Modifier.offset { }`
 * (placement-only — no per-frame full-screen canvas redraw), driven by a **low-fps discrete coroutine
 * ticker** (~15 fps), never a per-vsync `infiniteTransition`. That kept the old dot-field from starving
 * audio; the same rule applies here on the sensitive Now Playing screen.
 */
@Composable
fun PlayerDotTrail(
    color: Color,
    modifier: Modifier = Modifier,
    dots: Int = 6,
    trail: Int = 9,           // longer comet tail
    stepMs: Long = 64,        // ~15 fps discrete stepping
    spacing: Dp = 30.dp,      // match the background dotGrid
    dotSize: Dp = 3.2.dp,
    speed: Float = 0.02f,
) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(stepMs)
            tick++
        }
    }
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        for (d in 0 until dots) {
            for (t in 0 until trail) {
                val fade = 1f - t.toFloat() / trail
                Box(
                    Modifier
                        .size(dotSize)
                        .offset {
                            val sp = spacing.toPx()
                            val prog = tick * speed + d.toFloat() / dots - t * (speed * 0.9f)
                            val pos = dotTrailPos(d, prog, w, h, sp)
                            IntOffset(pos.first.roundToInt(), pos.second.roundToInt())
                        }
                        .background(color.copy(alpha = color.alpha * fade), CircleShape),
                )
            }
        }
    }
}

/** Position of dot [d] at progress [prog] (looping) — glides along a grid row/column through the points. */
private fun dotTrailPos(d: Int, prog: Float, w: Float, h: Float, sp: Float): Pair<Float, Float> {
    val p = ((prog % 1f) + 1f) % 1f
    val line = sp / 2f  // dotGrid draws points at sp/2 + n*sp
    return when (d % 6) {
        0 -> (p * w) to (line + 2 * sp)                 // → along a row
        1 -> (line + 5 * sp) to (p * h)                 // ↓ along a column
        2 -> ((1f - p) * w) to (line + 6 * sp)          // ← along a lower row
        3 -> (line + 2 * sp) to ((1f - p) * h)          // ↑ along a column
        4 -> (p * w) to (line + 9 * sp)                 // → along a row
        else -> (line + 8 * sp) to (p * h)              // ↓ along a column
    }
}
