package fm.rizx.player.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import fm.rizx.player.R
import java.io.File

/**
 * Draws the two things a RemoteViews layout cannot: text in the app's dot-matrix face, and the dotted
 * progress bar. Both are rendered in this process, so they look the same on every launcher and keep
 * Doto's `ROND` axis (round dots — the Nothing glyph matrix) that a layout attribute could not carry.
 */
class WidgetBitmaps(private val context: Context) {
    private val metrics get() = context.resources.displayMetrics

    /**
     * Doto at its heavy, round-dot instance. A font *resource* gives a Typeface whose axes a Paint
     * cannot always move, so the file is copied out once and built with the variation baked in; the
     * plain resource (and then monospace) remain as fallbacks.
     */
    private val doto: Typeface? by lazy {
        runCatching {
            val file = File(context.cacheDir, DOTO_CACHE_NAME)
            if (!file.exists() || file.length() == 0L) {
                // A font resource is a raw file under res/font; lint only knows the @RawRes spelling.
                @Suppress("ResourceType")
                context.resources.openRawResource(R.font.doto).use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            Typeface.Builder(file).setFontVariationSettings(DOTO_AXES).build()
        }.getOrNull() ?: runCatching { ResourcesCompat.getFont(context, R.font.doto) }.getOrNull()
    }

    fun dp(value: Float): Int = (value * metrics.density + 0.5f).toInt()

    private fun sp(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)

    /** One line of Doto, ellipsized to [widthPx]; the bitmap is exactly the line's height. */
    fun title(text: String, widthPx: Int, textSizeSp: Float, color: Int): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = doto ?: Typeface.MONOSPACE
            setFontVariationSettings(DOTO_AXES)
            textSize = sp(textSizeSp)
            letterSpacing = TITLE_TRACKING
            this.color = color
        }
        val width = widthPx.coerceAtLeast(1)
        val line = TextUtils.ellipsize(text, paint, width.toFloat(), TextUtils.TruncateAt.END).toString()
        val fontMetrics = paint.fontMetricsInt
        val height = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(line, 0f, -fontMetrics.ascent.toFloat(), paint)
        return bitmap
    }

    /**
     * Rows of little squares, lit from the left up to [progress], with a square knob on the playhead —
     * the app's seek line in the card's dotted idiom. Null when not even one column fits.
     */
    fun dots(widthPx: Int, progress: Float, on: Int, off: Int, knob: Int? = null): Bitmap? {
        val geometry = DotBar.geometry(widthPx, dp(DOT_CELL_DP).coerceAtLeast(1), dp(DOT_GAP_DP), DOT_ROWS)
        if (geometry.columns == 0) return null
        val filled = DotBar.filledColumns(geometry.columns, progress)
        val bitmap = Bitmap.createBitmap(geometry.widthPx, geometry.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val step = geometry.cellPx + geometry.gapPx
        for (column in 0 until geometry.columns) {
            paint.color = if (column < filled) on else off
            val left = (column * step).toFloat()
            for (row in 0 until geometry.rows) {
                val top = (row * step).toFloat()
                canvas.drawRect(left, top, left + geometry.cellPx, top + geometry.cellPx, paint)
            }
        }
        if (knob != null) {
            val side = geometry.heightPx.toFloat()
            val centre = (filled * step - geometry.gapPx / 2f).coerceIn(side / 2f, geometry.widthPx - side / 2f)
            paint.color = knob
            canvas.drawRect(centre - side / 2f, 0f, centre + side / 2f, side, paint)
        }
        return bitmap
    }

    private companion object {
        /** Black weight, round dots — the heaviest instance `ui/theme/Type.kt` registers for Doto. */
        const val DOTO_AXES = "'wght' 900, 'ROND' 100"
        const val DOTO_CACHE_NAME = "widget_doto.ttf"
        const val TITLE_TRACKING = 0.04f
        const val DOT_CELL_DP = 3f
        const val DOT_GAP_DP = 1.5f
        const val DOT_ROWS = 3
    }
}
