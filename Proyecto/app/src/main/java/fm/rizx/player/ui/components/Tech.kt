package fm.rizx.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.cornerBrackets

/**
 * Industrial "spec-sheet" chrome (refs #2/#4): mono code labels, rotated side labels, section eyebrows,
 * HUD corner-bracket frames, and dither/checker strips. All lightweight and static.
 */

/** Technical mono code/serial label (UPPERCASE), e.g. "SER. AD-0457". */
@Composable
fun CodeLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = RizxTheme.colors.muted,
    size: Int = 10,
) {
    Text(
        text.uppercase(),
        style = code(size),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Vertical (rotated 90° CCW, reads bottom-to-top) technical label, e.g. "NOW PLAYING" running up the
 * side (refs #3/#4). Uses the graphicsLayer-rotate + layout-swap recipe so it occupies a real tall-thin
 * layout slot (a plain `rotate()` would keep the wide unrotated bounds and misalign).
 */
@Composable
fun VerticalLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = RizxTheme.colors.muted,
    size: Int = 10,
) {
    Text(
        text.uppercase(),
        style = code(size, FontWeight.Bold),
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .graphicsLayer(rotationZ = -90f)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.height, placeable.width) {
                    placeable.place(
                        x = -(placeable.width / 2 - placeable.height / 2),
                        y = -(placeable.height / 2 - placeable.width / 2),
                    )
                }
            },
    )
}

/** Section eyebrow: a small solid accent tick + mono uppercase label. */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = RizxTheme.colors.muted,
    tick: Color = RizxTheme.colors.redAccent,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp).background(tick))
        Text(text.uppercase(), style = code(10, FontWeight.Bold), color = color, maxLines = 1)
    }
}

/** HUD frame: corner brackets (⌐ ¬ L ⌐) wrapping [content]. */
@Composable
fun HudFrame(
    modifier: Modifier = Modifier,
    color: Color = RizxTheme.colors.hardLine,
    bracketLen: Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.cornerBrackets(color, len = bracketLen), content = content)
}

/** Checkerboard / dither strip (ref #4 bottom edge). Place at a screen or section edge. */
@Composable
fun CheckerStrip(
    color: Color,
    modifier: Modifier = Modifier,
    cell: Dp = 7.dp,
    rows: Int = 2,
) {
    Canvas(modifier.fillMaxWidth().height(cell * rows)) {
        val c = cell.toPx()
        if (c <= 0f) return@Canvas
        val cols = (size.width / c).toInt() + 1
        for (r in 0 until rows) {
            for (col in 0 until cols) {
                if ((r + col) % 2 == 0) {
                    drawRect(color, topLeft = Offset(col * c, r * c), size = Size(c, c))
                }
            }
        }
    }
}
