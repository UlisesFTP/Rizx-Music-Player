package fm.rizx.player.ui.util

import android.os.Build
import fm.rizx.player.domain.model.DownloadFormat

/**
 * The download formats *this device* can actually produce — what every format picker iterates instead
 * of `DownloadFormat.entries`.
 *
 * Opus needs `MediaMuxer`'s Ogg writer (API 29) for the WebM→Ogg repackage; offering it below that
 * would quietly deliver untagged `.webm` files, so the option simply doesn't exist there. (A stored
 * OPUS preference restored from a newer device's backup still degrades safely: the remuxer refuses and
 * the download keeps its original container.) Everything else works everywhere. Lives in ui/util, not
 * on the enum: `domain` must stay free of Android imports, and which formats exist on a device is a
 * presentation question — the pipeline itself is version-agnostic and JVM-tested.
 */
fun availableDownloadFormats(): List<DownloadFormat> =
    if (Build.VERSION.SDK_INT >= 29) DownloadFormat.entries
    else DownloadFormat.entries.filterNot { it == DownloadFormat.OPUS }
