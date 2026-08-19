package fm.rizx.player.ui.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fm.rizx.player.domain.repository.PlaylistExportArtifact
import java.io.File

object PlaylistShareFiles {
    fun shareArtifact(context: Context, artifact: PlaylistExportArtifact) {
        val directory = File(context.cacheDir, "playlist_exports").apply { mkdirs() }
        val file = File(directory, artifact.fileName).apply { writeText(artifact.content, Charsets.UTF_8) }
        shareFile(context, file, artifact.mimeType, "Compartir ${artifact.fileName}")
    }

    fun qrBitmap(url: String, sizePx: Int = 768): Bitmap {
        val matrix = QRCodeWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until sizePx) for (x in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }

    fun shareQr(context: Context, url: String) {
        val directory = File(context.cacheDir, "playlist_exports").apply { mkdirs() }
        val file = File(directory, "rizx-playlist-qr.png")
        file.outputStream().use { qrBitmap(url).compress(Bitmap.CompressFormat.PNG, 100, it) }
        shareFile(context, file, "image/png", "Compartir código QR")
    }

    private fun shareFile(context: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}

