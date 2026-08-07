package fm.rizx.player.ui.player

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.domain.model.DownloadFormat
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.DownloadStatus
import fm.rizx.player.domain.model.SpatialAudioState
import fm.rizx.player.domain.model.SpatialInactiveReason
import fm.rizx.player.domain.model.SpatialRenderState
import fm.rizx.player.domain.model.SpatialRenderStatus
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.util.availableDownloadFormats

/**
 * The Now Playing overflow menu — what you can do to *this song* that isn't a transport control.
 *
 * Hard-edged and flat rather than Material's rounded elevated sheet, to match the rest of the shell.
 */
@Composable
fun NowPlayingMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    download: DownloadState?,
    canvasOn: Boolean,
    canvasAvailable: Boolean,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onToggleCanvas: () -> Unit,
    onShare: () -> Unit,
    /** Present = the menu also offers "Download as…" with an explicit format for this one song. */
    onDownloadAs: ((DownloadFormat) -> Unit)? = null,
    spatialState: SpatialAudioState = SpatialAudioState(),
    onToggleSpatialAudio: () -> Unit = {},
    /** In-flight 8D render for this song, or null when none is running. */
    spatialRender: SpatialRenderState? = null,
    /** Whether a finished 8D file already exists for it. */
    hasSpatialRender: Boolean = false,
    onRenderSpatial: () -> Unit = {},
    onDeleteSpatialRender: () -> Unit = {},
) {
    val c = RizxTheme.colors
    // Flat second level rather than a nested popup: a DropdownMenu inside a DropdownMenu anchors to the
    // window, not the row, and lands somewhere surprising. Expanding in place keeps every option inside
    // the one panel the finger is already on.
    var formatsOpen by remember { mutableStateOf(false) }
    if (!expanded && formatsOpen) formatsOpen = false
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = c.elev,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.hardLine),
        modifier = Modifier.background(c.elev),
    ) {
        when (download?.status) {
            null -> {
                MenuRow(Icons.Filled.FileDownload, stringResource(R.string.action_download), c.text) { onDismiss(); onDownload() }
                if (onDownloadAs != null) {
                    MenuRow(
                        icon = if (formatsOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        label = stringResource(R.string.player_download_as),
                        tint = c.text2,
                    ) { formatsOpen = !formatsOpen }
                    if (formatsOpen) {
                        availableDownloadFormats().forEach { format ->
                            MenuRow(Icons.Filled.FileDownload, "   " + stringResource(downloadFormatMenuLabel(format)), c.text2) {
                                onDismiss(); onDownloadAs(format)
                            }
                        }
                    }
                }
            }

            DownloadStatus.QUEUED ->
                MenuRow(Icons.Filled.FileDownload, stringResource(R.string.player_queued_for_download), c.muted, enabled = false) {}

            DownloadStatus.DOWNLOADING ->
                MenuRow(Icons.Filled.FileDownload, stringResource(R.string.player_downloading_percent, download.progressPercent), c.muted, enabled = false) {}

            DownloadStatus.CONVERTING ->
                MenuRow(Icons.Filled.FileDownload, stringResource(R.string.player_converting_mp3), c.muted, enabled = false) {}

            DownloadStatus.COMPLETE ->
                MenuRow(Icons.Filled.DownloadDone, stringResource(R.string.player_downloaded_remove), c.text) { onDismiss(); onDeleteDownload() }

            DownloadStatus.FAILED ->
                MenuRow(Icons.Filled.ErrorOutline, stringResource(R.string.player_download_failed_retry), c.redAccent) { onDismiss(); onDownload() }
        }

        // Named for what it actually is. It is not a Spotify Canvas — it's the song's own video, muted.
        MenuRow(
            icon = if (canvasOn) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
            label = when {
                canvasOn && !canvasAvailable -> stringResource(R.string.player_video_preview_on_none)
                canvasOn -> stringResource(R.string.player_video_preview_turn_off)
                else -> stringResource(R.string.player_video_preview)
            },
            tint = if (canvasOn) c.accent else c.text,
        ) { onDismiss(); onToggleCanvas() }

        // Adaptive stereo spatialization. Three visual states rather than two, because "on but waiting"
        // is a real and common one — over the phone's speaker, or under Android's own spatializer — and
        // showing it as simply "on" would leave the listener wondering why nothing changed.
        MenuRow(
            icon = if (spatialState.enabled) Icons.Filled.Headphones else Icons.Filled.HeadsetOff,
            label = when {
                !spatialState.waiting -> stringResource(R.string.spatial_title)
                spatialState.inactiveReason == SpatialInactiveReason.SYSTEM_SPATIALIZER_ACTIVE ->
                    stringResource(R.string.spatial_waiting_system)
                spatialState.inactiveReason == SpatialInactiveReason.SPEAKER_OUTPUT ->
                    stringResource(R.string.spatial_waiting_speaker)
                else -> stringResource(R.string.spatial_waiting_format)
            },
            tint = when {
                spatialState.active -> c.accent
                spatialState.waiting -> c.muted
                else -> c.text
            },
        ) { onDismiss(); onToggleSpatialAudio() }

        // A file, not a setting — and a *second* file, separate from this song's ordinary download, so
        // it sits on its own row rather than inside "Download as…". Choosing it there would have implied
        // it replaces the download, which is exactly what it does not do.
        when (spatialRender?.status) {
            null ->
                if (hasSpatialRender) {
                    MenuRow(Icons.Filled.DownloadDone, stringResource(R.string.spatial_render_remove), c.text) {
                        onDismiss(); onDeleteSpatialRender()
                    }
                } else {
                    MenuRow(Icons.Filled.FileDownload, stringResource(R.string.spatial_render), c.text) {
                        onDismiss(); onRenderSpatial()
                    }
                }

            SpatialRenderStatus.FETCHING -> MenuRow(
                Icons.Filled.FileDownload,
                stringResource(R.string.spatial_render_fetching, spatialRender.progressPercent),
                c.muted,
                enabled = false,
            ) {}

            SpatialRenderStatus.RENDERING -> MenuRow(
                Icons.Filled.Headphones,
                stringResource(R.string.spatial_render_working),
                c.muted,
                enabled = false,
            ) {}

            SpatialRenderStatus.COMPLETE -> MenuRow(
                Icons.Filled.DownloadDone,
                stringResource(R.string.spatial_render_done),
                c.accent,
                enabled = false,
            ) {}

            SpatialRenderStatus.FAILED -> MenuRow(
                Icons.Filled.ErrorOutline,
                stringResource(R.string.spatial_render_failed_retry),
                c.redAccent,
            ) { onDismiss(); onRenderSpatial() }
        }

        MenuRow(Icons.Filled.Share, stringResource(R.string.action_share), c.text) { onDismiss(); onShare() }
    }
}

/**
 * Shares the song via the system sheet. Sends the provider's own web link when the track has one (Deezer
 * and YouTube both put a real URL on their `ProviderRef`), so the recipient lands on the song rather than
 * on a bare string.
 */
fun android.content.Context.shareTrack(track: fm.rizx.player.domain.model.Track) {
    val who = track.artists.joinToString { it.name }
    val what = listOf(track.title, who).filter { it.isNotBlank() }.joinToString(" — ")
    val body = listOfNotNull(what.takeIf { it.isNotBlank() }, track.source.url).joinToString("\n")
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TITLE, what)
        putExtra(android.content.Intent.EXTRA_TEXT, body)
    }
    runCatching { startActivity(android.content.Intent.createChooser(send, null)) }
}

/**
 * Opens Android's system audio-output switcher — the "Media output" panel: phone speaker, paired Bluetooth,
 * and Cast / nearby devices. Uses the public [Settings.Panel][android.provider.Settings.Panel] intent, so
 * there's no MediaRouter/Cast SDK dependency and nothing to embed. While our session is the one playing, the
 * panel targets our audio and rerouting there moves our playback. Falls back to Bluetooth settings on the
 * odd ROM that doesn't ship the panel, so the button never dead-ends; both paths are guarded so a missing
 * activity can never crash the app.
 */
fun android.content.Context.openAudioOutputSwitcher() {
    // Not a public SDK constant, but the stable action string SystemUI/Settings register for the media
    // output picker. Settings Panels exist only since API 29 — below that there is no panel to ask for,
    // so the Bluetooth screen is the direct destination rather than a fallback.
    val panelOpened = Build.VERSION.SDK_INT >= 29 && runCatching {
        startActivity(
            android.content.Intent("android.settings.panel.action.MEDIA_OUTPUT").apply {
                // Scope the picker to our own session where the platform honours it; a version that
                // doesn't just ignores the extra and shows the active session, which is ours while
                // we're playing.
                putExtra("android.provider.extra.MEDIA_OUTPUT_PACKAGE_NAME", packageName)
            },
        )
    }.isSuccess
    if (!panelOpened) {
        runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }
}

/** Short menu names — the Settings dialog owns the long explanations. */
private fun downloadFormatMenuLabel(format: DownloadFormat): Int = when (format) {
    DownloadFormat.ORIGINAL -> R.string.download_format_original
    DownloadFormat.OPUS -> R.string.download_format_opus
    DownloadFormat.MP3 -> R.string.download_format_mp3
    DownloadFormat.FLAC -> R.string.download_format_flac
}

@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        enabled = enabled,
        onClick = onClick,
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) },
        text = { Text(label, style = mr(13, FontWeight.Medium), color = tint) },
    )
}
