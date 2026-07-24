package fm.rizx.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import fm.rizx.player.R
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.PlaylistItem
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.CodeLabel
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DownloadAllButton
import fm.rizx.player.ui.components.DownloadButton
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.library.CreatePlaylistDialog
import fm.rizx.player.ui.library.PlaylistDetailViewModel
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

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            val json = vm.exportJson()
            if (json != null) withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
            }
        }
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
                Icons.Filled.FileUpload, stringResource(R.string.detail_export_playlist), tint = c.text2,
                modifier = Modifier.size(24.dp).clickableScale(scale = 0.86f, onClick = { exporter.launch("${playlist?.name ?: "playlist"}.json") }),
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

        val items = playlist?.items.orEmpty()
        // "Download all" lives on the count row, not the header: the header already carries four
        // unlabelled icons, and this action reads far better with a word on it.
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CodeLabel(
                if (items.size == 1) {
                    stringResource(R.string.detail_track_count_caps_one, items.size)
                } else {
                    stringResource(R.string.detail_track_count_caps_other, items.size)
                },
                modifier = Modifier.weight(1f),
                color = c.muted,
                size = 11,
            )
            DownloadAllButton(
                tracks = items.map { it.track },
                states = downloadStates,
                onDownloadAll = vm::downloadAll,
            )
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.detail_playlist_empty), style = mr(14, FontWeight.Medium), color = c.muted)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    Box(Modifier.staggeredReveal(index)) {
                        PlaylistItemRow(
                            position = index + 1,
                            item = item,
                            removable = !readOnly,
                            downloadState = downloadStates[item.track.source.identityKey],
                            onPlay = { vm.play(index) },
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
