package fm.rizx.player.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The playback line's thickness, and the side of the square marker riding on it. */
val SEEK_LINE_HEIGHT = 3.dp
val SEEK_MARKER = 9.dp

/**
 * Draws the app's playback line: a faint full-width track, a red fill up to [progress], and a square
 * red marker sitting on the line at that point.
 *
 * One function so every place that shows playback position draws the *same* line — the mini-player's
 * scrubber and the lyrics screen's transport. They used to be independent (the lyrics bar was two
 * plain boxes and had no marker at all), which is exactly how two bars that mean the same thing end up
 * looking like two different things.
 *
 * [lineCenterY] defaults to the vertical middle; the mini-player pushes it down so the marker's lower
 * half stays clear of the bar's bottom edge.
 */
fun DrawScope.drawSeekLine(
    progress: Float,
    trackColor: Color,
    fillColor: Color,
    markerBorderColor: Color,
    lineHeight: Dp = SEEK_LINE_HEIGHT,
    marker: Dp = SEEK_MARKER,
    lineCenterY: Float = size.height / 2f,
) {
    val shown = progress.coerceIn(0f, 1f)
    val markerPx = marker.toPx()
    val lineH = lineHeight.toPx()
    val lineTop = lineCenterY - lineH / 2f

    drawRect(color = trackColor, topLeft = Offset(0f, lineTop), size = Size(size.width, lineH))
    drawRect(color = fillColor, topLeft = Offset(0f, lineTop), size = Size(size.width * shown, lineH))

    // Clamped so the marker stays fully on screen at either end instead of being half-cut.
    val cx = (size.width * shown).coerceIn(markerPx / 2f, (size.width - markerPx / 2f).coerceAtLeast(markerPx / 2f))
    val corner = Offset(cx - markerPx / 2f, lineCenterY - markerPx / 2f)
    drawRect(color = fillColor, topLeft = corner, size = Size(markerPx, markerPx))
    drawRect(color = markerBorderColor, topLeft = corner, size = Size(markerPx, markerPx), style = Stroke(width = 1.dp.toPx()))
}
