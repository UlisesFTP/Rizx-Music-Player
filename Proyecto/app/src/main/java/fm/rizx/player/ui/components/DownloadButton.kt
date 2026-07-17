package fm.rizx.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.DownloadStatus
import fm.rizx.player.domain.model.Track
import fm.rizx.player.ui.theme.RizxTheme

/**
 * The download affordance on a track row: one tap to save a song for offline listening, and an honest
 * readout of where it is.
 *
 * The percentage is a monospaced [CodeLabel] rather than a progress ring — that is the typographic idiom
 * the rest of the app already speaks (every count is a `CodeLabel`), and a number reads better than a
 * 20dp arc in a dense list. [RizxIconButton] carries a 48dp touch target by default, so the minimum
 * target rule holds by construction even though the glyph is 20dp.
 */
@Composable
fun DownloadButton(
    state: DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit = onDownload,
    modifier: Modifier = Modifier,
) {
    val c = RizxTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        when (state?.status) {
            null -> RizxIconButton(
                Icons.Filled.FileDownload,
                "Download for offline",
                onClick = onDownload,
                iconSize = 20.dp,
                tint = c.text2,
            )

            DownloadStatus.QUEUED -> {
                CodeLabel("QUEUED", size = 10)
                RizxIconButton(
                    Icons.Filled.Stop,
                    "Cancel download",
                    onClick = onCancel,
                    iconSize = 20.dp,
                    tint = c.muted,
                )
            }

            DownloadStatus.DOWNLOADING -> {
                CodeLabel("${state.progressPercent}%", size = 10)
                RizxIconButton(
                    Icons.Filled.Stop,
                    "Cancel download",
                    onClick = onCancel,
                    iconSize = 20.dp,
                    tint = c.accent,
                )
            }

            DownloadStatus.COMPLETE -> RizxIconButton(
                Icons.Filled.DownloadDone,
                "Downloaded — available offline",
                onClick = {},
                iconSize = 20.dp,
                tint = c.accent,
            )

            DownloadStatus.FAILED -> RizxIconButton(
                Icons.Filled.ErrorOutline,
                state.error ?: "Download failed — tap to retry",
                onClick = onRetry,
                iconSize = 20.dp,
                tint = c.redAccent,
            )
        }
    }
}

/**
 * "Download all" for a playlist or album — a labelled action rather than another unlabelled icon in an
 * already-crowded header. It reports what's actually true of the set: how many are saved, how many are
 * still going, or that there's nothing left to do.
 */
@Composable
fun DownloadAllButton(
    tracks: List<Track>,
    states: Map<String, DownloadState>,
    onDownloadAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) return
    val c = RizxTheme.colors
    val statuses = tracks.map { states[it.source.identityKey]?.status }
    val done = statuses.count { it == DownloadStatus.COMPLETE }
    val inFlight = statuses.count { it == DownloadStatus.QUEUED || it == DownloadStatus.DOWNLOADING }
    val allDone = done == tracks.size

    val label = when {
        allDone -> "DOWNLOADED"
        inFlight > 0 -> "DOWNLOADING $done/${tracks.size}"
        else -> "DOWNLOAD ALL"
    }
    Row(
        modifier
            .then(if (allDone) Modifier else Modifier.clickableScale(scale = 0.94f, onClick = onDownloadAll))
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (allDone) Icons.Filled.DownloadDone else Icons.Filled.FileDownload,
            null,
            tint = if (allDone) c.accent else c.text2,
            modifier = Modifier.size(18.dp),
        )
        CodeLabel(label, size = 11, color = if (allDone) c.accent else c.text2)
    }
}
