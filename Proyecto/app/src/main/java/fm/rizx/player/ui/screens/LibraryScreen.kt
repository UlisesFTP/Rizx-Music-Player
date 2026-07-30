package fm.rizx.player.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.data.download.formatBytes
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.DownloadedTrack
import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.CodeLabel
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DownloadButton
import fm.rizx.player.ui.components.FilterEmpty
import fm.rizx.player.ui.components.RizxActionButton
import fm.rizx.player.ui.components.RizxChip
import fm.rizx.player.ui.components.RizxFilterField
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.SectionHeader
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.components.tintFor
import fm.rizx.player.ui.util.ListFilter
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.library.ConfirmDialog
import fm.rizx.player.ui.library.CreatePlaylistDialog
import fm.rizx.player.ui.library.ImportPlaylistDialog
import fm.rizx.player.ui.library.LibraryViewModel
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Library's content categories — pick one instead of scrolling past the others. */
enum class LibraryTab(@StringRes val labelRes: Int) {
    All(R.string.library_tab_all),
    Playlists(R.string.library_tab_playlists),
    Liked(R.string.library_tab_liked),
    Downloads(R.string.library_tab_downloads),
    Recent(R.string.library_tab_recent),
    Local(R.string.library_tab_local),
}

/** How many rows a section previews on the [LibraryTab.All] overview before "See all". */
private const val PREVIEW_ROWS = 4

/**
 * Your library, as four categories rather than one long scroll. Previously everything stacked in a single
 * column — up to 25 recents, then *every* liked song, and playlists last — so the content you come here for
 * was the hardest to reach. Now: tabs pick a category, **All** is a short overview (playlists first), and the
 * lists are virtualized, which also fixes the unbounded liked list composing every row at once.
 */
@Composable
fun LibraryScreen(
    onOpenPlaylist: (String) -> Unit,
    onOpenLocal: () -> Unit = {},
    initialTab: LibraryTab = LibraryTab.All,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val likedSongs by vm.favoriteTracks.collectAsStateWithLifecycle()
    val playlists by vm.playlistSummaries.collectAsStateWithLifecycle()
    val recents by vm.recentTracks.collectAsStateWithLifecycle()
    val downloads by vm.downloadedTracks.collectAsStateWithLifecycle()
    val downloadStates by vm.downloadStates.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // The filter belongs to the tab it was typed on: switching tabs clears it instead of carrying a query
    // over to a list where it would silently hide almost everything.
    var filter by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDeleteDownload by remember { mutableStateOf<DownloadedTrack?>(null) }
    var confirmDeleteAllDownloads by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }

    // Toast/snackbar text is shown from callbacks that run after composition (network results, activity
    // results), where stringResource() can't be called — resolve it here, in composable scope, and capture
    // the resolved value in the closure below.
    val playlistImportedMsg = stringResource(R.string.library_playlist_imported)
    val importFailedMsg = stringResource(R.string.library_import_failed)
    val fileReadErrorMsg = stringResource(R.string.library_import_file_read_error)
    val exportSavedTemplate = stringResource(R.string.library_export_saved)
    val exportFailedMsg = stringResource(R.string.library_export_failed)
    val removedFromLikedMsg = stringResource(R.string.library_removed_from_liked)
    val undoLabel = stringResource(R.string.action_undo).uppercase()

    // Imports hit the network and can legitimately fail (private list, dead link, changed page) — say so
    // instead of leaving the user staring at an unchanged Library.
    val reportImport: (Result<String>) -> Unit = { result ->
        val message = result.fold(
            onSuccess = { playlistImportedMsg },
            onFailure = { importFailedMsg },
        )
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            // An Exportify CSV carries no playlist name — its file name is the name, so read that too.
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?.let { text -> text to context.displayNameOf(uri) }
                }.getOrNull()
            }
            if (file != null) vm.importPlaylistFile(file.first, file.second, reportImport)
            else Toast.makeText(context, fileReadErrorMsg, Toast.LENGTH_LONG).show()
        }
    }

    // Export writes outside the app, where the user has to go looking for it — so say where it landed.
    val exportDownload: (DownloadedTrack) -> Unit = { entry ->
        vm.exportDownload(entry.key) { result ->
            val message = result.fold(
                onSuccess = { String.format(exportSavedTemplate, it) },
                onFailure = { exportFailedMsg },
            )
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // Unliking removes the row from under the finger, so it needs a way back.
    val onUnfavorite: (Track) -> Unit = { track ->
        vm.unfavoriteTrack(track)
        scope.launch {
            val result = snackbars.showSnackbar(
                message = removedFromLikedMsg,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.favoriteTrack(track)
        }
    }

    if (importing) {
        ImportPlaylistDialog(
            onImport = { vm.importFromUrl(it, reportImport) },
            onFile = { importing = false; importer.launch(arrayOf("application/json", "text/csv", "*/*")) },
            onDismiss = { importing = false },
        )
    }
    if (creating) {
        CreatePlaylistDialog(onCreate = vm::createPlaylist, onDismiss = { creating = false })
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.library_clear_recent_title),
            body = stringResource(R.string.library_clear_recent_body),
            confirmLabel = stringResource(R.string.action_clear),
            onConfirm = vm::clearRecentlyPlayed,
            onDismiss = { confirmClear = false },
        )
    }
    // Deleting bytes is not undoable, so it asks first rather than offering a snackbar UNDO that would lie.
    confirmDeleteDownload?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.library_delete_download_title),
            body = stringResource(R.string.library_delete_download_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { vm.deleteDownload(entry.key) },
            onDismiss = { confirmDeleteDownload = null },
        )
    }
    if (confirmDeleteAllDownloads) {
        ConfirmDialog(
            title = stringResource(R.string.library_delete_all_downloads_title),
            body = stringResource(R.string.library_delete_all_downloads_body),
            confirmLabel = stringResource(R.string.library_delete_all_downloads_confirm),
            onConfirm = vm::deleteAllDownloads,
            onDismiss = { confirmDeleteAllDownloads = false },
        )
    }

    // Narrowed to what the filter allows — identity on the All tab, which has no field (see below). These
    // lists are already in memory, so this costs nothing and works offline; see [ListFilter].
    val visiblePlaylists = remember(playlists, filter) { playlists.filter { ListFilter.matches(filter, it.name, it.description) } }
    val visibleLiked = remember(likedSongs, filter) { likedSongs.filter { ListFilter.matchesTrack(filter, it) } }
    val visibleDownloads = remember(downloads, filter) { downloads.filter { ListFilter.matchesTrack(filter, it.track) } }
    val visibleRecents = remember(recents, filter) { recents.filter { ListFilter.matchesTrack(filter, it) } }

    val playlistsSectionTitle = stringResource(R.string.library_section_playlists)
    val likedSectionTitle = stringResource(R.string.library_section_liked)
    val downloadsSectionTitle = stringResource(R.string.library_section_downloads)
    val recentSectionTitle = stringResource(R.string.library_section_recent)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = 22.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.library_title), style = sg(28, FontWeight.Bold, -0.02f), color = c.text, modifier = Modifier.weight(1f))
                    // Labelled, not bare glyphs: "new" and "import" are not guessable from an icon, and the
                    // old import icon was a download arrow — which this screen also uses for its Downloads
                    // tab, so it read as "download" rather than "import".
                    RizxActionButton(
                        RizxIcons.Add, stringResource(R.string.library_new_playlist_label), onClick = { creating = true },
                        contentDescription = stringResource(R.string.library_new_playlist), prominent = true,
                    )
                    RizxActionButton(
                        Icons.Filled.AddLink, stringResource(R.string.action_import), onClick = { importing = true },
                        contentDescription = stringResource(R.string.library_import_playlist_desc),
                    )
                }
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LibraryTab.entries.forEach { entry ->
                        RizxChip(stringResource(entry.labelRes), active = tab == entry, onClick = { tab = entry; filter = "" })
                    }
                }
            }

            // One field per tab, filtering that tab's list and nothing else. Not on **All** — that tab is a
            // four-section overview, so a single query over it would be filtering four lists at once — and
            // not on **Local**, which is only a doorway to its own screen (which has its own field).
            val source = when (tab) {
                LibraryTab.Playlists -> playlists.size
                LibraryTab.Liked -> likedSongs.size
                LibraryTab.Downloads -> downloads.size
                LibraryTab.Recent -> recents.size
                LibraryTab.All, LibraryTab.Local -> 0
            }
            if (source > 0) {
                item(key = "filter") { RizxFilterField(filter, { filter = it }, Modifier.padding(top = 10.dp)) }
            }

            when (tab) {
                LibraryTab.All -> {
                    // Playlists first: they're what you open the Library for.
                    section(
                        title = playlistsSectionTitle,
                        count = playlists.size,
                        onSeeAll = { tab = LibraryTab.Playlists },
                    ) {
                        playlistRows(playlists.take(PREVIEW_ROWS), onOpenPlaylist)
                    }
                    if (playlists.isEmpty()) item { PlaylistsEmpty { creating = true } }

                    section(
                        title = likedSectionTitle,
                        count = likedSongs.size,
                        onSeeAll = { tab = LibraryTab.Liked },
                    ) {
                        // A preview row plays the *whole* liked list, not the four shown: `take` keeps the
                        // indices, and this is a peek at the tab rather than the tab itself.
                        likedRows(likedSongs.take(PREVIEW_ROWS), downloadStates, vm, onUnfavorite) { vm.playLiked(it, likedSongs) }
                    }
                    if (likedSongs.isEmpty()) item { LikedEmpty() }

                    if (downloads.isNotEmpty()) {
                        section(
                            title = downloadsSectionTitle,
                            count = downloads.size,
                            onSeeAll = { tab = LibraryTab.Downloads },
                        ) {
                            downloadRows(
                                downloads.take(PREVIEW_ROWS), vm,
                                onDelete = { confirmDeleteDownload = it }, onExport = exportDownload,
                                onPlay = { vm.playDownloads(it, downloads.map { entry -> entry.track }) },
                            )
                        }
                    }

                    section(
                        title = recentSectionTitle,
                        count = recents.size,
                        onSeeAll = { tab = LibraryTab.Recent },
                    ) {
                        recentRows(recents.take(PREVIEW_ROWS)) { vm.playRecent(it, recents) }
                    }
                    if (recents.isEmpty()) item { RecentEmpty() }
                }

                LibraryTab.Playlists -> {
                    if (playlists.isEmpty()) {
                        item { PlaylistsEmpty { creating = true } }
                    } else if (visiblePlaylists.isEmpty()) {
                        item { FilterEmpty(filter) }
                    } else {
                        item { TabCount(countLabel(visiblePlaylists.size, R.string.library_count_playlist_one, R.string.library_count_playlist_other)) }
                        playlistRows(visiblePlaylists, onOpenPlaylist)
                    }
                }

                LibraryTab.Liked -> {
                    if (likedSongs.isEmpty()) {
                        item { LikedEmpty() }
                    } else if (visibleLiked.isEmpty()) {
                        item { FilterEmpty(filter) }
                    } else {
                        item { TabCount(countLabel(visibleLiked.size, R.string.library_count_song_one, R.string.library_count_song_other)) }
                        // What you see is what plays: a filtered list becomes the queue, so next/prev stay
                        // inside the songs the filter left on screen.
                        likedRows(visibleLiked, downloadStates, vm, onUnfavorite) { vm.playLiked(it, visibleLiked) }
                    }
                }

                LibraryTab.Downloads -> {
                    if (downloads.isEmpty()) {
                        item {
                            DownloadsEmpty(
                                onGoToLiked = if (likedSongs.isNotEmpty()) ({ tab = LibraryTab.Liked }) else null,
                            )
                        }
                    } else if (visibleDownloads.isEmpty()) {
                        item { FilterEmpty(filter) }
                    } else {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Both halves of the readout describe the rows on screen — a filtered count
                                // over the whole library's byte total would be two different lists in one line.
                                val bytes = formatBytes(visibleDownloads.sumOf { it.sizeBytes })
                                TabCount(
                                    "${countLabel(visibleDownloads.size, R.string.library_count_song_one, R.string.library_count_song_other)} · $bytes",
                                    Modifier.weight(1f),
                                )
                                RizxIconButton(
                                    Icons.Filled.DeleteOutline,
                                    stringResource(R.string.library_delete_all_downloads_desc),
                                    onClick = { confirmDeleteAllDownloads = true },
                                    iconSize = 20.dp,
                                    tint = c.text2,
                                )
                            }
                        }
                        downloadRows(
                            visibleDownloads, vm,
                            onDelete = { confirmDeleteDownload = it }, onExport = exportDownload,
                            onPlay = { vm.playDownloads(it, visibleDownloads.map { entry -> entry.track }) },
                        )
                    }
                }

                LibraryTab.Recent -> {
                    if (recents.isEmpty()) {
                        item { RecentEmpty() }
                    } else if (visibleRecents.isEmpty()) {
                        item { FilterEmpty(filter) }
                    } else {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TabCount(countLabel(visibleRecents.size, R.string.library_count_song_one, R.string.library_count_song_other), Modifier.weight(1f))
                                RizxIconButton(
                                    Icons.Filled.DeleteOutline,
                                    stringResource(R.string.library_clear_recent_desc),
                                    onClick = { confirmClear = true },
                                    iconSize = 20.dp,
                                    tint = c.text2,
                                )
                            }
                        }
                        recentRows(visibleRecents) { vm.playRecent(it, visibleRecents) }
                    }
                }

                LibraryTab.Local -> {
                    // The on-device player (Songs / Albums / Artists + the audio permission) lives in its
                    // own screen; this tab is its entry point.
                    item {
                        LibraryEmpty(
                            icon = Icons.Outlined.LibraryMusic,
                            title = stringResource(R.string.library_local_entry_title),
                            body = stringResource(R.string.library_local_entry_body),
                            actionLabel = stringResource(R.string.library_open_local_music),
                            onAction = onOpenLocal,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(LocalBottomInset.current + 16.dp)) }
        }

        // Clears the floating chrome that RizxApp draws on top of every screen (mini-player + bottom nav,
        // ~175dp together) — otherwise the snackbar, and its UNDO, hide behind them.
        SnackbarHost(
            snackbars,
            Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = LocalBottomInset.current + 12.dp),
        )
    }
}

// ---- section scaffolding -------------------------------------------------------------------------

/** A titled section on the All overview: header (+ "See all" once there's more than the preview) then rows. */
private fun LazyListScope.section(
    title: String,
    count: Int,
    onSeeAll: () -> Unit,
    rows: LazyListScope.() -> Unit,
) {
    item {
        SectionHeader(
            title,
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp),
            action = if (count > PREVIEW_ROWS) stringResource(R.string.action_see_all) else null,
            onAction = if (count > PREVIEW_ROWS) onSeeAll else null,
        )
    }
    rows()
}

/** Localized "N noun(s)" — resolves the right plural resource for [count] and formats it in. */
@Composable
private fun countLabel(count: Int, @StringRes one: Int, @StringRes other: Int): String =
    stringResource(if (count == 1) one else other, count)

// Keys are prefixed per section: the same track can sit in both Liked and Recent on the All tab, and a
// LazyColumn crashes on duplicate keys.
private fun LazyListScope.playlistRows(items: List<PlaylistSummary>, onOpen: (String) -> Unit) {
    itemsIndexed(items, key = { _, p -> "pl-${p.id}" }) { index, playlist ->
        Box(Modifier.staggeredReveal(index)) {
            PlaylistRow(playlist, onClick = { onOpen(playlist.id) })
        }
    }
}

/** [onPlay] takes the row's index; the caller decides which list that index counts into (full vs filtered). */
private fun LazyListScope.likedRows(
    items: List<Track>,
    states: Map<String, DownloadState>,
    vm: LibraryViewModel,
    onUnfavorite: (Track) -> Unit,
    onPlay: (Int) -> Unit,
) {
    itemsIndexed(items, key = { _, t -> "lk-${t.source.provider}:${t.source.id}" }) { index, track ->
        Box(Modifier.staggeredReveal(index)) {
            TrackRow(track, onPlay = { onPlay(index) }) {
                DownloadButton(
                    state = states[track.source.identityKey],
                    onDownload = { vm.downloadTrack(track) },
                    onCancel = { vm.cancelDownload(track.source.identityKey) },
                )
                RizxIconButton(
                    RizxIcons.Favorite,
                    stringResource(R.string.library_remove_from_liked_desc),
                    onClick = { onUnfavorite(track) },
                    iconSize = 22.dp,
                    tint = RizxTheme.colors.redAccent,
                )
            }
        }
    }
}

/**
 * A downloaded song. The trailing slot carries what's true of a *file* — its format and size — plus the
 * two things you can only do to a file: send it somewhere else, or delete it.
 */
private fun LazyListScope.downloadRows(
    items: List<DownloadedTrack>,
    vm: LibraryViewModel,
    onDelete: (DownloadedTrack) -> Unit,
    onExport: (DownloadedTrack) -> Unit,
    onPlay: (Int) -> Unit,
) {
    itemsIndexed(items, key = { _, d -> "dl-${d.key}" }) { index, entry ->
        Box(Modifier.staggeredReveal(index)) {
            TrackRow(entry.track, onPlay = { onPlay(index) }) {
                CodeLabel("${entry.container.uppercase()} · ${formatBytes(entry.sizeBytes)}", size = 10)
                RizxIconButton(
                    Icons.Filled.DriveFileMove,
                    if (entry.exportedUri != null) stringResource(R.string.library_export_again_desc) else stringResource(R.string.library_export_desc),
                    onClick = { onExport(entry) },
                    iconSize = 20.dp,
                    tint = if (entry.exportedUri != null) RizxTheme.colors.accent else RizxTheme.colors.text2,
                )
                RizxIconButton(
                    Icons.Filled.DeleteOutline,
                    stringResource(R.string.library_delete_download_desc),
                    onClick = { onDelete(entry) },
                    iconSize = 20.dp,
                    tint = RizxTheme.colors.text2,
                )
            }
        }
    }
}

private fun LazyListScope.recentRows(items: List<Track>, onPlay: (Int) -> Unit) {
    itemsIndexed(items, key = { _, t -> "rc-${t.source.provider}:${t.source.id}" }) { index, track ->
        Box(Modifier.staggeredReveal(index)) {
            TrackRow(track, onPlay = { onPlay(index) })
        }
    }
}

// ---- rows ---------------------------------------------------------------------------------------

/** The library's one track row. [trailing] is what differs (liked shows duration + heart, recents nothing). */
@Composable
private fun TrackRow(track: Track, onPlay: () -> Unit, trailing: @Composable (RowScope.() -> Unit)? = null) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        CoverArt(tintFor(track.source.id), initial = null, Modifier.size(46.dp), imageUrl = track.artwork.coverUrl())
        Column(Modifier.weight(1f)) {
            Text(track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                track.artists.joinToString { it.name }.ifEmpty { stringResource(R.string.unknown_artist) },
                style = mr(12, FontWeight.Medium),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke(this)
    }
}

@Composable
private fun PlaylistRow(playlist: PlaylistSummary, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // Imported playlists carry their real cover; anything without one falls back to the tinted tile
        // with its initial, exactly as before (CoverArt already handles a null/failed image).
        CoverArt(
            tintFor(playlist.id),
            initial = playlist.name.take(1).uppercase(),
            Modifier.size(46.dp),
            initialSize = 20,
            imageUrl = playlist.artworkUrl,
        )
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = mr(15, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                playlistSubtitle(
                    playlist,
                    countLabel(playlist.itemCount, R.string.library_count_track_one, R.string.library_count_track_other),
                ),
                style = mr(12, FontWeight.Medium),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // A chevron, not a play button: tapping opens the playlist, it doesn't play it.
        Icon(RizxIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(22.dp))
    }
}

private fun playlistSubtitle(playlist: PlaylistSummary, trackCountLabel: String): String = buildList {
    add(trackCountLabel)
    playlist.description?.takeIf { it.isNotBlank() }?.let { add(it) }
}.joinToString(" · ")

// ---- counts & empty states ----------------------------------------------------------------------

@Composable
private fun TabCount(text: String, modifier: Modifier = Modifier) =
    CodeLabel(text, modifier.padding(top = 14.dp, bottom = 4.dp), size = 11)

@Composable
private fun PlaylistsEmpty(onCreate: () -> Unit) = LibraryEmpty(
    icon = RizxIcons.QueueMusic,
    title = stringResource(R.string.library_no_playlists_title),
    body = stringResource(R.string.library_no_playlists_body),
    actionLabel = stringResource(R.string.library_new_playlist),
    onAction = onCreate,
)

@Composable
private fun LikedEmpty() = LibraryEmpty(
    icon = RizxIcons.FavoriteBorder,
    title = stringResource(R.string.library_no_liked_title),
    body = stringResource(R.string.library_no_liked_body),
)

@Composable
private fun RecentEmpty() = LibraryEmpty(
    icon = Icons.Filled.History,
    title = stringResource(R.string.library_no_recent_title),
    body = stringResource(R.string.library_no_recent_body),
)

/** The CTA only appears when there's somewhere to send you — an empty Liked tab would be a dead end. */
@Composable
private fun DownloadsEmpty(onGoToLiked: (() -> Unit)?) = LibraryEmpty(
    icon = Icons.Filled.DownloadForOffline,
    title = stringResource(R.string.library_no_downloads_title),
    body = stringResource(R.string.library_no_downloads_body),
    actionLabel = onGoToLiked?.let { stringResource(R.string.library_go_to_liked) },
    onAction = onGoToLiked,
)

/** Empty states earn their space: an icon, what's missing, and — where one exists — the way out. */
@Composable
private fun LibraryEmpty(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = RizxTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = c.muted, modifier = Modifier.size(46.dp))
        Text(title, style = sg(17, FontWeight.Bold), color = c.text, modifier = Modifier.padding(top = 14.dp))
        Text(
            body,
            style = mr(13, FontWeight.Medium),
            color = c.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (actionLabel != null && onAction != null) {
            Box(
                Modifier
                    .padding(top = 16.dp)
                    .clip(RectangleShape)
                    .background(c.fill)
                    .clickableScale(scale = 0.94f, onClick = onAction)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text(actionLabel, style = sg(14, FontWeight.Bold), color = c.onFill)
            }
        }
    }
}

/** The file name behind a SAF [uri], extension dropped — names imports whose format carries none (CSV). */
private fun Context.displayNameOf(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
