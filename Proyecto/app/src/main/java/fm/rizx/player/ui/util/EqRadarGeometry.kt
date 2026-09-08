package fm.rizx.player.ui.util

import kotlin.math.cos
import kotlin.math.sin

/**
 * The equalizer radar's geometry, ported from the feed design's `app.js` and parameterised by the
 * band count the device actually reports (five on most phones, ten on some).
 *
 * Everything is expressed in a 360 × 340 view box, like the design's SVG, and scaled by the caller;
 * that keeps these numbers identical to the web and lets a JVM test pin them.
 */
object EqRadarGeometry {
    const val VIEW_WIDTH = 360f
    const val VIEW_HEIGHT = 340f
    const val CENTER_X = 180f
    const val CENTER_Y = 162f

    /** The vertex a band draws at when it sits at the bottom of the range. */
    const val SHAPE_MIN_RADIUS = 38f

    /** How much further the vertex travels across the whole range. */
    const val SHAPE_SPAN = 88f

    /** The three grid rings, outermost first. */
    val RING_RADII = floatArrayOf(128f, 89f, 51f)

    /** Where an axis label sits: just outside the outer ring. */
    const val LABEL_RADIUS = 152f

    /** `(x, y)` of vertex [index] of [count], starting at the top and going clockwise. */
    fun polar(index: Int, count: Int, radius: Float): Pair<Float, Float> {
        val angle = Math.toRadians((-90.0 + index * 360.0 / maxOf(count, 1)))
        return Pair(
            (CENTER_X + cos(angle) * radius).toFloat(),
            (CENTER_Y + sin(angle) * radius).toFloat(),
        )
    }

    /**
     * The design's `38 + ((value + 6) / 12) * 88`, over the device's real range: a band at [minDb] sits on
     * the inner ring and one at [maxDb] on the outer edge of the shape.
     */
    fun shapeRadius(valueDb: Float, minDb: Float, maxDb: Float): Float {
        val span = (maxDb - minDb).takeIf { it > 0f } ?: return SHAPE_MIN_RADIUS + SHAPE_SPAN / 2f
        val clamped = valueDb.coerceIn(minDb, maxDb)
        return SHAPE_MIN_RADIUS + ((clamped - minDb) / span) * SHAPE_SPAN
    }

    /**
     * `+3 dB`, `−1.5 dB`, `0 dB` — the design's readout, to a tenth only when the value needs it. Uses the
     * real minus sign, as the web does.
     */
    fun formatDecibels(millibel: Int): String {
        val tenths = Math.round(millibel / 10.0).toInt() // 100 mB = 1 dB, so /10 is tenths of a dB
        if (tenths == 0) return "0 dB"
        val magnitude = kotlin.math.abs(tenths)
        val text = if (magnitude % 10 == 0) "${magnitude / 10}" else "${magnitude / 10}.${magnitude % 10}"
        return if (tenths > 0) "+$text dB" else "−$text dB"
    }

    /** `31 Hz`, `1 kHz`, `16 kHz`. */
    fun frequencyLabel(hz: Int): String =
        if (hz >= 1000) "${if (hz % 1000 == 0) hz / 1000 else hz / 1000f} kHz" else "$hz Hz"
}
