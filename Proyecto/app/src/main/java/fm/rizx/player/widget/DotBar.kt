package fm.rizx.player.widget

import kotlin.math.roundToInt

/**
 * The geometry of the dotted progress bar — Nothing's rows of little squares, filled from the left.
 * Pure arithmetic in pixels; the drawing lives in [WidgetBitmaps] so this part can be tested on the JVM.
 */
object DotBar {
    data class Geometry(val columns: Int, val cellPx: Int, val gapPx: Int, val rows: Int) {
        val widthPx: Int get() = if (columns == 0) 0 else columns * cellPx + (columns - 1) * gapPx
        val heightPx: Int get() = rows * cellPx + (rows - 1) * gapPx
    }

    /** As many whole columns as fit in [widthPx]; never a partial square at the edge. */
    fun geometry(widthPx: Int, cellPx: Int, gapPx: Int, rows: Int): Geometry {
        require(cellPx > 0 && gapPx >= 0 && rows > 0)
        val columns = if (widthPx <= 0) 0 else ((widthPx + gapPx) / (cellPx + gapPx)).coerceAtLeast(0)
        return Geometry(columns, cellPx, gapPx, rows)
    }

    /** How many columns are "on" for [progress] in 0..1 — rounded, so the last square lights at the end. */
    fun filledColumns(columns: Int, progress: Float): Int =
        (progress.coerceIn(0f, 1f) * columns).roundToInt().coerceIn(0, columns)

    /**
     * The song fraction a tap on segment [index] of [segments] equal tap zones stands for: the middle of
     * the zone, so the first one is a little way in and the last a little way before the end.
     */
    fun tapFraction(index: Int, segments: Int): Float {
        require(segments > 0)
        return ((index.coerceIn(0, segments - 1) + 0.5f) / segments).coerceIn(0f, 1f)
    }
}
