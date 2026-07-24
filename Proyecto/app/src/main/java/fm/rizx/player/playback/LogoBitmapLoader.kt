package fm.rizx.player.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import fm.rizx.player.R
import java.util.concurrent.Executor

/**
 * Wraps a [BitmapLoader] and stamps a small **monochrome** Rizx logo into the **bottom-right corner** of
 * every artwork it loads. The album thumbnail then carries the brand mark wherever the platform draws it —
 * the media notification, the lock screen, the output switcher, a paired watch — since all of them ask the
 * [androidx.media3.session.MediaSession]'s bitmap loader for the same cover.
 *
 * The mark is a white logomark inside a dark disc (with a hairline ring) so it stays legible on both light
 * and dark covers. We can't restyle the system's media card, but we *do* own the artwork bitmap, so this is
 * the one place a brand mark can ride along with it.
 *
 * Composited **once per cover** because a [androidx.media3.session.CacheBitmapLoader] wraps this and caches
 * the stamped result; the source bitmap is always copied, never mutated (it may be shared/reused upstream).
 */
@UnstableApi
class LogoBitmapLoader(
    context: Context,
    private val delegate: BitmapLoader,
) : BitmapLoader {

    private val appContext = context.applicationContext
    // The composite is a few ms of Canvas work; run it inline on whatever thread completes the load.
    private val inline = Executor { it.run() }

    override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = stamp(delegate.decodeBitmap(data))

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = stamp(delegate.loadBitmap(uri))

    private fun stamp(future: ListenableFuture<Bitmap>): ListenableFuture<Bitmap> =
        Futures.transform(
            future,
            com.google.common.base.Function { bmp: Bitmap? -> bmp?.let(::withLogo) },
            inline,
        )

    private fun withLogo(src: Bitmap): Bitmap {
        // Always copy — the upstream bitmap may be cached/reused, and a HARDWARE bitmap can't be drawn on.
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val canvas = Canvas(out)
        val side = minOf(out.width, out.height).toFloat()
        val radius = side * 0.15f
        val inset = side * 0.055f
        val cx = out.width - inset - radius
        val cy = out.height - inset - radius

        // Dark disc so the white mark reads on any cover.
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 12, 12, 14) }
        canvas.drawCircle(cx, cy, radius, disc)
        // Hairline ring so the disc itself is visible even on a near-black cover.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(64, 255, 255, 255)
            strokeWidth = (side * 0.006f).coerceAtLeast(1f)
        }
        canvas.drawCircle(cx, cy, radius, ring)

        // The white Rizx logomark, centred in the disc.
        ContextCompat.getDrawable(appContext, R.drawable.ic_notification)?.let { logo ->
            logo.setTint(Color.WHITE)
            val ls = (radius * 1.2f).toInt()
            val l = (cx - ls / 2f).toInt()
            val t = (cy - ls / 2f).toInt()
            logo.setBounds(l, t, l + ls, t + ls)
            logo.draw(canvas)
        }
        return out
    }
}
