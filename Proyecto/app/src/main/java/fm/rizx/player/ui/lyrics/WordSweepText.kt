package fm.rizx.player.ui.lyrics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import fm.rizx.player.domain.lyrics.LyricsSweep

/** How far the halo bleeds past the sung letters. Blur radius, not an offset — the glow is centred. */
private val GLOW_RADIUS = 7.dp

/**
 * One lyric line that fills in as it is sung, letter by letter.
 *
 * Drawn in two passes over the *same* text layout:
 *
 *  1. `Text` itself draws the whole line in [inactiveColor] — no manual measuring, no `BoxWithConstraints`
 *     subcomposition, and line breaking stays whatever Compose would have done anyway.
 *  2. The sung region is drawn again in [activeColor], clipped to a rectangle whose right edge is the
 *     sweep position. Optionally with a shadow, which is what the halo is: one extra *cached* layout, not
 *     an extra layer per frame.
 *
 * The edge is computed with `getHorizontalPosition`, never by adding up character widths. That is the
 * whole trick behind RTL and complex scripts working: the line is laid out once, as a line, and the sweep
 * only decides where to cut it. Ligatures, contextual forms and bidi runs survive because nothing is ever
 * re-shaped — a renderer that drew glyph by glyph would break all three.
 *
 * [sweep] is a **lambda invoked at draw time**. It reads the per-frame clock inside the draw phase, so a
 * new frame invalidates drawing and nothing else: no recomposition, no re-measure, and the surrounding
 * `LazyColumn` is untouched.
 */
@Composable
fun WordSweepText(
    text: String,
    lineIndex: Int,
    style: TextStyle,
    inactiveColor: Color,
    activeColor: Color,
    glow: Boolean,
    glowColor: Color,
    sweep: () -> LyricsSweep,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var base by remember(text, style) { mutableStateOf<TextLayoutResult?>(null) }

    val glowRadiusPx = with(LocalDensity.current) { GLOW_RADIUS.toPx() }
    val sungStyle = remember(style, activeColor, glow, glowColor, glowRadiusPx) {
        style.copy(
            color = activeColor,
            // A text shadow *is* a blur on Android, so the halo costs one more cached layout rather than
            // a `saveLayer` + `BlurMaskFilter` round trip every frame.
            shadow = if (glow) Shadow(color = glowColor, blurRadius = glowRadiusPx) else null,
        )
    }

    // Measured from the base layout's own constraints, so the two passes break lines identically.
    // Neither colour nor shadow affects metrics; if that ever stops being true the sweep will visibly
    // drift from the text, which is the symptom to look for.
    val sung = remember(base, sungStyle) {
        base?.let { b ->
            measurer.measure(
                text = AnnotatedString(text),
                style = sungStyle,
                constraints = b.layoutInput.constraints,
                layoutDirection = b.layoutInput.layoutDirection,
                softWrap = b.layoutInput.softWrap,
                maxLines = b.layoutInput.maxLines,
            )
        }
    }

    Text(
        text = text,
        style = style,
        color = inactiveColor,
        onTextLayout = { base = it },
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val layout = base ?: return@drawWithContent
                val sungLayout = sung ?: return@drawWithContent
                drawSweep(layout, sungLayout, lineIndex, sweep())
            },
    )
}

/** Draws whatever part of [sungLayout] has been sung, clipped visual line by visual line. */
private fun DrawScope.drawSweep(
    layout: TextLayoutResult,
    sungLayout: TextLayoutResult,
    lineIndex: Int,
    sweep: LyricsSweep,
) {
    // The active index is settled during composition while the clock keeps moving, so by draw time the
    // sweep can already belong to a neighbouring line. Both cases are a whole line, not a glitch.
    if (sweep.lineIndex < lineIndex) return
    if (sweep.lineIndex > lineIndex) {
        drawText(sungLayout)
        return
    }
    if (sweep.fromChar <= 0 && sweep.fraction <= 0f) return

    val edgeLine = layout.getLineForOffset(sweep.fromChar)
    val rtl = layout.getParagraphDirection(sweep.fromChar) == ResolvedTextDirection.Rtl
    val edgeX = edgeXOf(layout, sweep, edgeLine, rtl)

    for (i in 0..edgeLine) {
        val top = layout.getLineTop(i)
        val bottom = layout.getLineBottom(i)
        val left: Float
        val right: Float
        if (i < edgeLine) {
            // Wrapped lines above the edge are sung in full.
            left = 0f
            right = size.width
        } else if (rtl) {
            left = edgeX
            right = layout.getLineRight(i)
        } else {
            left = 0f
            right = edgeX
        }
        if (right <= left) continue
        clipRect(left = left, top = top, right = right, bottom = bottom) {
            drawText(sungLayout)
        }
    }
}

/** The x of the sweep edge: interpolated across the grapheme currently being sung. */
private fun edgeXOf(
    layout: TextLayoutResult,
    sweep: LyricsSweep,
    edgeLine: Int,
    rtl: Boolean,
): Float {
    val from = layout.getHorizontalPosition(sweep.fromChar, usePrimaryDirection = true)
    // A grapheme that ends exactly at a wrap point reports its end on the *next* visual line, which would
    // throw the edge back to x=0. The end of this line is what was meant.
    val to = if (sweep.toChar > sweep.fromChar && layout.getLineForOffset(sweep.toChar) == edgeLine) {
        layout.getHorizontalPosition(sweep.toChar, usePrimaryDirection = true)
    } else if (rtl) {
        layout.getLineLeft(edgeLine)
    } else {
        layout.getLineRight(edgeLine)
    }
    return from + (to - from) * sweep.fraction
}
