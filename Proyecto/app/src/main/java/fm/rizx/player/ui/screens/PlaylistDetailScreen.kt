package fm.rizx.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import fm.rizx.player.R
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.PlaylistItem
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.CodeLabel
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DownloadAllButton
import fm.rizx.player.ui.components.DownloadButton
import fm.rizx.player.ui.components.FilterEmpty
import fm.rizx.player.ui.components.RizxFilterField
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.util.ListFilter
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.library.CreatePlaylistDialog
import fm.rizx.player.ui.library.PlaylistDetailViewModel
import fm.rizx.player.ui.library.PlaylistShareFiles
import fm.rizx.player.domain.repository.PlaylistExportFormat
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal

@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    vm: PlaylistDetailViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val downloadStates by vm.downloadStates.collectAsStateWithLifecycle()
    val readOnly = playlist?.isReadOnly == true
    var renaming by remember { mutableStateOf(false) }
    var sharing by rememberSaveable { mutableStateOf(false) }
    // Survives rotation; there is nothing to restore after process death, since the list itself is reloaded.
    var filter by rememberSaveable { mutableStateOf("") }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (sharing) {
        PlaylistShareSheet(
            vm = vm,
            playlistName = playlist?.name.orEmpty(),
            onDismiss = { sharing = false },
        )
    }

    if (renaming) {
        CreatePlaylistDialog(onCreate = vm::rename, onDismiss = { renaming = false })
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                RizxIcons.Back, stringResource(R.string.detail_back), tint = c.text,
                modifier = Modifier.size(26.dp).clickableScale(scale = 0.88f, onClick = onBack),
            )
            Text(
                playlist?.name ?: stringResource(R.string.detail_playlist_fallback_name),
                style = sg(24, FontWeight.Bold, -0.02f), color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.Share, stringResource(R.string.detail_export_playlist), tint = c.text2,
                modifier = Modifier.size(24.dp).clickableScale(scale = 0.86f, onClick = { sharing = true }),
            )
            if (!readOnly) {
                Icon(
                    Icons.Filled.Edit, stringResource(R.string.detail_rename), tint = c.text2,
                    modifier = Modifier.size(24.dp).clickableScale(scale = 0.86f, onClick = { renaming = true }),
                )
            }
            Icon(
                Icons.Filled.DeleteOutline, stringResource(R.string.detail_delete_playlist), tint = c.text2,
                modifier = Modifier.size(24.dp).clickableScale(scale = 0.86f, onClick = { vm.delete(onDeleted = onBack) }),
            )
        }

        // The description is where an import records that it was cut short ("First 100 tracks only…").
        // It was written on every import but drawn nowhere the user would read it — the Library row
        // ellipsizes it away after the track count — so a truncated import looked like a complete one.
        playlist?.description?.takeIf { it.isNotBlank() }?.let { note ->
            Text(
                note,
                style = mr(12, FontWeight.Medium), color = c.muted,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        val items = playlist?.items.orEmpty()
        // A 240-track import is not something you scroll: the filter searches this playlist and only this
        // playlist. Rows keep their **playlist** position while filtered, so a hit still tells you where it
        // sits in the list; `visible` therefore carries the original index next to each item.
        val visible = remember(items, filter) {
            items.withIndex().filter { (_, item) -> ListFilter.matchesTrack(filter, item.track) }
        }
        val visibleTracks = remember(visible) { visible.map { it.value.track } }

        // "Download all" lives on the count row, not the header: the header already carries four
        // unlabelled icons, and this action reads far better with a word on it.
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CodeLabel(
                if (visible.size == 1) {
                    stringResource(R.string.detail_track_count_caps_one, visible.size)
                } else {
                    stringResource(R.string.detail_track_count_caps_other, visible.size)
                },
                modifier = Modifier.weight(1f),
                color = c.muted,
                size = 11,
            )
            DownloadAllButton(
                tracks = visibleTracks,
                states = downloadStates,
                onDownloadAll = { vm.downloadAll(visibleTracks) },
            )
        }

        if (items.isNotEmpty()) {
            RizxFilterField(filter, { filter = it }, Modifier.padding(bottom = 10.dp))
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.detail_playlist_empty), style = mr(14, FontWeight.Medium), color = c.muted)
            }
        } else if (visible.isEmpty()) {
            FilterEmpty(filter)
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(visible, key = { _, (_, item) -> item.id }) { row, (position, item) ->
                    Box(Modifier.staggeredReveal(row)) {
                        PlaylistItemRow(
                            position = position + 1,
                            item = item,
                            removable = !readOnly,
                            downloadState = downloadStates[item.track.source.identityKey],
                            onPlay = { vm.play(row, visibleTracks) },
                            onRemove = { vm.removeItem(item.id) },
                            onDownload = { vm.downloadTrack(item.track) },
                            onCancelDownload = { vm.cancelDownload(item.track.source.identityKey) },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PlaylistShareSheet(
    vm: PlaylistDetailViewModel,
    playlistName: String,
    onDismiss: () -> Unit,
) {
    val c = RizxTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.shareState.collectAsStateWithLifecycle()
    var exporting by remember { mutableStateOf<PlaylistExportFormat?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.elev, contentColor = c.text) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.share_playlist_title),
                style = sg(22, FontWeight.Bold, -0.02f),
                color = c.text,
            )
            Text(
                stringResource(R.string.share_playlist_caption),
                style = mr(12, FontWeight.Medium),
                color = c.muted,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            ExportRow(Icons.Filled.Code, stringResource(R.string.share_json), stringResource(R.string.share_json_caption), exporting == PlaylistExportFormat.RIZX_JSON) {
                exporting = PlaylistExportFormat.RIZX_JSON
                scope.launch {
                    vm.export(PlaylistExportFormat.RIZX_JSON)?.let { PlaylistShareFiles.shareArtifact(context, it) }
                    exporting = null
                }
            }
            ExportRow(Icons.Filled.Description, stringResource(R.string.share_xspf), stringResource(R.string.share_xspf_caption), exporting == PlaylistExportFormat.XSPF) {
                exporting = PlaylistExportFormat.XSPF
                scope.launch {
                    vm.export(PlaylistExportFormat.XSPF)?.let { PlaylistShareFiles.shareArtifact(context, it) }
                    exporting = null
                }
            }
            ExportRow(Icons.Filled.Description, stringResource(R.string.share_m3u8), stringResource(R.string.share_m3u8_caption), exporting == PlaylistExportFormat.M3U8) {
                exporting = PlaylistExportFormat.M3U8
                scope.launch {
                    vm.export(PlaylistExportFormat.M3U8)?.let { artifact ->
                        PlaylistShareFiles.shareArtifact(context, artifact)
                        if (artifact.omittedItems > 0) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.share_omitted, artifact.omittedItems),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                    exporting = null
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = c.hardLine)

            if (state.link == null) {
                ExportRow(
                    Icons.Filled.Link,
                    stringResource(R.string.share_link_qr),
                    if (vm.cloudSharingConfigured) stringResource(R.string.share_link_caption) else stringResource(R.string.share_cloud_disabled),
                    state.isWorking,
                    enabled = vm.cloudSharingConfigured && !state.isWorking,
                ) { vm.createShareLink() }
            } else {
                val link = state.link!!
                val qr = remember(link.url) { PlaylistShareFiles.qrBitmap(link.url, 360).asImageBitmap() }
                Image(
                    qr,
                    contentDescription = stringResource(R.string.share_qr_description, playlistName),
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(220.dp).padding(8.dp),
                )
                Text(
                    stringResource(R.string.share_expires, link.expiresAtIso.take(10)),
                    style = mr(12, FontWeight.Medium), color = c.muted,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp),
                )
                ExportRow(Icons.Filled.ContentCopy, stringResource(R.string.share_copy_link), link.url, false) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Rizx playlist", link.url))
                }
                ExportRow(Icons.Filled.IosShare, stringResource(R.string.share_qr_action), stringResource(R.string.share_qr_action_caption), false) {
                    scope.launch { PlaylistShareFiles.shareQr(context, link.url) }
                }
                ExportRow(Icons.Filled.DeleteOutline, stringResource(R.string.share_revoke), stringResource(R.string.share_revoke_caption), state.isWorking) {
                    vm.revokeShare()
                }
            }

            state.error?.let { message ->
                Text(
                    message,
                    style = mr(12, FontWeight.SemiBold),
                    color = c.redAccent,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            Text(
                stringResource(R.string.share_privacy_note),
                style = mr(11, FontWeight.Medium),
                color = c.muted,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun ExportRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    caption: String,
    loading: Boolean,
    enabled: Boolean = !loading,
    onClick: () -> Unit,
) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (enabled) Modifier.clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = if (enabled) c.text2 else c.muted, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = mr(14, FontWeight.SemiBold), color = if (enabled) c.text else c.muted)
            Text(caption, style = mr(11, FontWeight.Medium), color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.redAccent)
    }
}

@Composable
private fun PlaylistItemRow(
    position: Int,
    item: PlaylistItem,
    removable: Boolean,
    downloadState: DownloadState?,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Text("$position", style = mr(13, FontWeight.Medium), color = c.muted)
        }
        CoverArt(tintFor(item.track.source.id), initial = null, Modifier.size(44.dp), imageUrl = item.track.artwork.coverUrl())
        Column(Modifier.weight(1f)) {
            Text(item.track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.track.artists.joinToString { it.name }.ifEmpty { stringResource(R.string.unknown_artist) }, style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // The duration gave way to the download control: with a position, a cover and a remove button
        // already on the row, three trailing items is one too many, and the runtime is the least useful.
        DownloadButton(state = downloadState, onDownload = onDownload, onCancel = onCancelDownload)
        if (removable) {
            Icon(
                RizxIcons.Close, stringResource(R.string.action_remove), tint = c.text2,
                modifier = Modifier.size(22.dp).clickableScale(scale = 0.84f, onClick = onRemove),
            )
        }
    }
}

private fun tintFor(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % 7
