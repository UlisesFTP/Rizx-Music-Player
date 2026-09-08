package fm.rizx.player.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.data.download.formatBytes
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.DownloadedTrack
import fm.rizx.player.domain.model.PlaylistDigest
import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.domain.model.Track
import fm.rizx.player.ui.components.CodeLabel
import fm.rizx.player.ui.components.CollageArt
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DisplayTitle
import fm.rizx.player.ui.components.DownloadButton
import fm.rizx.player.ui.components.Editorial
import fm.rizx.player.ui.components.EditorialButton
import fm.rizx.player.ui.components.EditorialSearchField
import fm.rizx.player.ui.components.EditorialSurface
import fm.rizx.player.ui.components.EditorialTab
import fm.rizx.player.ui.components.EmptyBlock
import fm.rizx.player.ui.components.EqualRow
import fm.rizx.player.ui.components.IndexTag
import fm.rizx.player.ui.components.Kicker
import fm.rizx.player.ui.components.Lede
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.RowArrow
import fm.rizx.player.ui.components.SignalEyebrow
import fm.rizx.player.ui.components.SurfaceHeading
import fm.rizx.player.ui.components.SurfaceTitle
import fm.rizx.player.ui.components.bleed
import fm.rizx.player.ui.components.bottomRule
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.components.tileUrl
import fm.rizx.player.ui.components.tintFor
import fm.rizx.player.ui.components.topRule
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.library.ConfirmDialog
import fm.rizx.player.ui.library.CreatePlaylistDialog
import fm.rizx.player.ui.library.ImportPlaylistDialog
import fm.rizx.player.ui.library.LibraryViewModel
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.isTablet
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.pagePadding
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal
import fm.rizx.player.ui.util.LibraryStats
import fm.rizx.player.ui.util.ListFilter
import fm.rizx.player.ui.util.rememberSaveToPhonePermission
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

/** How many playlist rows the overview shows before "See all". */
private const val OVERVIEW_PLAYLISTS = 4

/** How many song rows a song section previews on the overview. */
private const val OVERVIEW_SONGS = 3

/**
 * Your library, in the editorial layout of the feed design: a kicker and a giant title over two hero
 * actions, a strip of tab pills, and then either the overview — framed surfaces for playlists, liked
 * songs, downloads and history — or one category at full width with its own display heading, search
 * field and numbered song list. Lists stay virtualized: the liked list composing every row at once was
 * the Library's original performance bug, and a prettier row does not change that.
 */
@Composable
fun LibraryScreen(
    onOpenPlaylist: (String) -> Unit,
    onOpenLocal: () -> Unit = {},
    initialTab: LibraryTab = LibraryTab.All,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val likedSongs by vm.favoriteTracks.collectAsStateWithLifecycle()
    val playlists by vm.playlistSummaries.collectAsStateWithLifecycle()
    val digests by vm.playlistDigests.collectAsStateWithLifecycle()
    val recents by vm.recentTracks.collectAsStateWithLifecycle()
    val downloads by vm.downloadedTracks.collectAsStateWithLifecycle()
    val downloadStates by vm.downloadStates.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // The filter belongs to the tab it was typed on: switching tabs clears it instead of carrying a query
    // over to a list where it would silently hide almost everything.
    var filter by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var savingLiked by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDeleteDownload by remember { mutableStateOf<DownloadedTrack?>(null) }
    var confirmDeleteAllDownloads by remember { mutableStateOf(false) }
    // The rows a "save all" would copy — held rather than recomputed so the dialog states the same
    // number and the same size the button offered, even if a download lands while it is open.
    var confirmSaveAll by remember { mutableStateOf<List<DownloadedTrack>?>(null) }

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
    val exportSavedManyTemplate = stringResource(R.string.library_export_saved_many)
    val exportSavedOneMsg = stringResource(R.string.library_export_saved_one)
    val nothingToSaveMsg = stringResource(R.string.library_export_none)
    val exportFailedMsg = stringResource(R.string.library_export_failed)
    val removedFromLikedMsg = stringResource(R.string.library_removed_from_liked)
    val likedPlaylistCreatedTemplate = stringResource(R.string.library_liked_playlist_created)
    val likedPlaylistFailedMsg = stringResource(R.string.library_liked_playlist_failed)
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

    // A link that arrived from outside the app — a scanned QR, a tapped share link. Taken out of the
    // inbox *before* the import starts, so a recomposition or a second Library entry can't run it twice;
    // on success the new playlist opens, which is the whole point of scanning instead of pasting.
    val pendingShare by vm.pendingShareLink.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShare) {
        val url = pendingShare ?: return@LaunchedEffect
        if (vm.consumeShareLink() == null) return@LaunchedEffect
        tab = LibraryTab.Playlists
        vm.importFromUrl(url) { result ->
            result.fold(
                onSuccess = { id ->
                    Toast.makeText(context, playlistImportedMsg, Toast.LENGTH_LONG).show()
                    onOpenPlaylist(id)
                },
                onFailure = { Toast.makeText(context, importFailedMsg, Toast.LENGTH_LONG).show() },
            )
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            // An Exportify CSV carries no playlist name — its file name is the name, so read that too.
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        // Bounded read: a picked file larger than a few MB is refused rather than
                        // materialized whole into memory. Playlist files are small; a giant one is a
                        // mistake or an attack, and readText() on it would OOM.
                        val maxBytes = 8 * 1024 * 1024
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        var ok = true
                        while (true) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            if (out.size() + n > maxBytes) { ok = false; break }
                            out.write(buf, 0, n)
                        }
                        if (ok) out.toString(Charsets.UTF_8.name()) else null
                    }?.let { text -> text to context.displayNameOf(uri) }
                }.getOrNull()
            }
            if (file != null) vm.importPlaylistFile(file.first, file.second, reportImport)
            else Toast.makeText(context, fileReadErrorMsg, Toast.LENGTH_LONG).show()
        }
    }

    // Saving writes outside the app, where the user has to go looking for it — so say where it landed.
    // On Android 8–9 (API < 29) the write additionally needs the legacy storage permission first; a
    // refusal surfaces as the same failure snackbar the export itself would show.
    val ensureSavePermission = rememberSaveToPhonePermission(
        onDenied = { scope.launch { snackbars.showSnackbar(exportFailedMsg, duration = SnackbarDuration.Short) } },
    )
    val exportDownload: (DownloadedTrack) -> Unit = { entry ->
        ensureSavePermission {
            vm.exportDownload(entry.key) { result ->
                val message = result.fold(
                    onSuccess = { String.format(exportSavedTemplate, it) },
                    onFailure = { exportFailedMsg },
                )
                scope.launch { snackbars.showSnackbar(message, duration = SnackbarDuration.Short) }
            }
        }
    }

    val exportAll: (List<DownloadedTrack>) -> Unit = { entries ->
        ensureSavePermission {
            vm.exportDownloads(entries) { saved, failed ->
                val message = when {
                    saved == 0 && failed == 0 -> nothingToSaveMsg
                    failed > 0 -> exportFailedMsg
                    saved == 1 -> exportSavedOneMsg
                    else -> String.format(exportSavedManyTemplate, saved)
                }
                scope.launch { snackbars.showSnackbar(message, duration = SnackbarDuration.Short) }
            }
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
    if (savingLiked) {
        CreatePlaylistDialog(
            onCreate = { name ->
                vm.saveLikedAsPlaylist(name, likedSongs) { result ->
                    result.onSuccess { id ->
                        Toast.makeText(
                            context,
                            String.format(likedPlaylistCreatedTemplate, likedSongs.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                        onOpenPlaylist(id)
                    }.onFailure {
                        scope.launch {
                            snackbars.showSnackbar(likedPlaylistFailedMsg, duration = SnackbarDuration.Short)
                        }
                    }
                }
            },
            onDismiss = { savingLiked = false },
        )
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
            // A copy on the phone belongs to the user like any other file of theirs, so it survives — and
            // the dialog says so, rather than leaving them to discover it.
            body = if (entry.exportedUri != null) {
                stringResource(R.string.library_delete_download_body) + " " +
                    stringResource(R.string.library_delete_download_keeps_copy)
            } else {
                stringResource(R.string.library_delete_download_body)
            },
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
    // Copying a whole library doubles what it takes up, so the size is on the dialog and the user says go.
    confirmSaveAll?.let { entries ->
        val pending = entries.filter { it.exportedUri == null }
        ConfirmDialog(
            title = stringResource(R.string.library_save_all_title),
            body = stringResource(
                R.string.library_save_all_body,
                // The count comes pre-worded so one song isn't announced as "1 canciones".
                countLabel(pending.size, R.string.library_count_song_one, R.string.library_count_song_other),
                formatBytes(pending.sumOf { it.sizeBytes }),
            ),
            confirmLabel = stringResource(R.string.library_save_all_confirm),
            onConfirm = { exportAll(pending) },
            onDismiss = { confirmSaveAll = null },
        )
    }

    // Narrowed to what the filter allows — identity on the All tab, which has no field (see below). These
    // lists are already in memory, so this costs nothing and works offline; see [ListFilter].
    val visiblePlaylists = remember(playlists, filter) { playlists.filter { ListFilter.matches(filter, it.name, it.description) } }
    val visibleLiked = remember(likedSongs, filter) { likedSongs.filter { ListFilter.matchesTrack(filter, it) } }
    val visibleDownloads = remember(downloads, filter) { downloads.filter { ListFilter.matchesTrack(filter, it.track) } }
    val visibleRecents = remember(recents, filter) { recents.filter { ListFilter.matchesTrack(filter, it) } }

    val margin = pagePadding()
    val columns = if (isTablet()) 3 else 2

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = margin),
        ) {
            item(key = "hero") {
                LibraryHero(likedCount = likedSongs.size, onNew = { creating = true }, onImport = { importing = true })
            }
            item(key = "tabs") {
                LibraryTabs(tab, margin) { tab = it; filter = "" }
            }

            when (tab) {
                LibraryTab.All -> overview(
                    playlists = playlists,
                    digests = digests,
                    liked = likedSongs,
                    downloads = downloads,
                    recents = recents,
                    onOpenPlaylist = onOpenPlaylist,
                    onNewPlaylist = { creating = true },
                    onShow = { tab = it },
                    onPlayLiked = { vm.playLiked(it, likedSongs) },
                    onPlayDownload = { vm.playDownloads(it, downloads.map { entry -> entry.track }) },
                    onPlayRecent = { vm.playRecent(it, recents) },
                )

                LibraryTab.Playlists -> {
                    item(key = "hdr-playlists") {
                        ViewHeader(
                            eyebrow = stringResource(R.string.library_your_archive_eyebrow),
                            title = stringResource(R.string.library_playlists_heading),
                            summary = stringResource(R.string.library_playlists_summary, playlists.size, playlists.sumOf { it.itemCount }),
                            search = if (playlists.isNotEmpty()) {
                                { EditorialSearchField(filter, { filter = it }, stringResource(R.string.library_search_playlists)) }
                            } else null,
                        )
                    }
                    if (playlists.isNotEmpty() && visiblePlaylists.isEmpty()) {
                        item(key = "empty") { NoMatches(filter) }
                    } else {
                        // The trailing "new playlist" tile is part of the grid, exactly as on the web — so an
                        // empty library is one inviting tile rather than a dead end.
                        playlistGrid(visiblePlaylists, digests, columns, onOpenPlaylist, onNew = { creating = true })
                    }
                }

                LibraryTab.Liked -> {
                    item(key = "hdr-liked") {
                        ViewHeader(
                            eyebrow = stringResource(R.string.library_saved_eyebrow, likedSongs.size),
                            title = stringResource(R.string.library_songs_you_like),
                            summary = likedStats(likedSongs),
                            action = if (likedSongs.isNotEmpty()) {
                                {
                                    EditorialButton(
                                        stringResource(R.string.library_save_liked_label),
                                        onClick = { savingLiked = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                                        contentDescription = stringResource(R.string.library_save_liked_desc),
                                    )
                                }
                            } else null,
                            search = if (likedSongs.isNotEmpty()) {
                                { EditorialSearchField(filter, { filter = it }, stringResource(R.string.library_search_liked)) }
                            } else null,
                        )
                    }
                    when {
                        likedSongs.isEmpty() -> item(key = "empty") { LikedEmpty() }
                        visibleLiked.isEmpty() -> item(key = "empty") { NoMatches(filter) }
                        else -> {
                            item(key = "list-top") { ListTop() }
                            // What you see is what plays: a filtered list becomes the queue, so next/prev stay
                            // inside the songs the filter left on screen.
                            likedRows(visibleLiked, downloadStates, vm, onUnfavorite) { vm.playLiked(it, visibleLiked) }
                        }
                    }
                }

                LibraryTab.Downloads -> {
                    val bytes = formatBytes(visibleDownloads.sumOf { it.sizeBytes })
                    val onPhone = visibleDownloads.count { it.exportedUri != null }
                    val pending = visibleDownloads.size - onPhone
                    item(key = "hdr-downloads") {
                        ViewHeader(
                            eyebrow = stringResource(R.string.library_offline_eyebrow),
                            title = stringResource(R.string.library_section_downloads),
                            // Both halves of the readout describe the rows on screen — a filtered count over
                            // the whole library's byte total would be two different lists in one line.
                            summary = if (downloads.isEmpty()) null else buildString {
                                append(countLabel(visibleDownloads.size, R.string.library_count_song_one, R.string.library_count_song_other))
                                append(" · ").append(bytes)
                                if (onPhone > 0) append(" · ").append(stringResource(R.string.library_on_phone_count, onPhone))
                            },
                            action = if (downloads.isEmpty()) null else {
                                {
                                    Column {
                                        // The whole reason this feature was invisible: nothing ever said where
                                        // a download lives, or that it could live anywhere else.
                                        if (pending > 0) {
                                            Text(
                                                stringResource(R.string.library_downloads_explainer),
                                                style = mr(12, FontWeight.Medium, lineHeight = 17),
                                                color = RizxTheme.colors.muted,
                                                modifier = Modifier.padding(bottom = 12.dp),
                                            )
                                        }
                                        EqualRow {
                                            if (pending > 0) {
                                                EditorialButton(
                                                    stringResource(R.string.library_save_all_to_phone, pending),
                                                    onClick = { confirmSaveAll = visibleDownloads },
                                                    modifier = Modifier.weight(1f),
                                                    icon = Icons.Filled.SaveAlt,
                                                    primary = true,
                                                    dense = true,
                                                )
                                            }
                                            EditorialButton(
                                                stringResource(R.string.library_delete_all_downloads_confirm),
                                                onClick = { confirmDeleteAllDownloads = true },
                                                modifier = Modifier.weight(1f),
                                                icon = Icons.Filled.DeleteOutline,
                                                contentDescription = stringResource(R.string.library_delete_all_downloads_desc),
                                                dense = true,
                                            )
                                        }
                                    }
                                }
                            },
                            search = if (downloads.isNotEmpty()) {
                                { EditorialSearchField(filter, { filter = it }, stringResource(R.string.library_search_downloads)) }
                            } else null,
                        )
                    }
                    when {
                        downloads.isEmpty() -> item(key = "empty") {
                            DownloadsEmpty(onGoToLiked = if (likedSongs.isNotEmpty()) ({ tab = LibraryTab.Liked }) else null)
                        }
                        visibleDownloads.isEmpty() -> item(key = "empty") { NoMatches(filter) }
                        else -> {
                            item(key = "list-top") { ListTop() }
                            downloadRows(
                                visibleDownloads, vm,
                                onDelete = { confirmDeleteDownload = it }, onExport = exportDownload,
                                onPlay = { vm.playDownloads(it, visibleDownloads.map { entry -> entry.track }) },
                            )
                        }
                    }
                }

                LibraryTab.Recent -> {
                    item(key = "hdr-recent") {
                        ViewHeader(
                            eyebrow = stringResource(R.string.library_history_eyebrow),
                            title = stringResource(R.string.library_recent_heading),
                            summary = if (recents.isEmpty()) null else countLabel(visibleRecents.size, R.string.library_count_song_one, R.string.library_count_song_other),
                            action = if (recents.isEmpty()) null else {
                                {
                                    EditorialButton(
                                        stringResource(R.string.action_clear),
                                        onClick = { confirmClear = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        icon = Icons.Filled.DeleteOutline,
                                        contentDescription = stringResource(R.string.library_clear_recent_desc),
                                    )
                                }
                            },
                            search = if (recents.isNotEmpty()) {
                                { EditorialSearchField(filter, { filter = it }, stringResource(R.string.library_search_recent)) }
                            } else null,
                        )
                    }
                    when {
                        recents.isEmpty() -> item(key = "empty") { RecentEmpty() }
                        visibleRecents.isEmpty() -> item(key = "empty") { NoMatches(filter) }
                        else -> {
                            item(key = "list-top") { ListTop() }
                            recentRows(visibleRecents) { vm.playRecent(it, visibleRecents) }
                        }
                    }
                }

                LibraryTab.Local -> {
                    // The on-device player (Songs / Albums / Artists + the audio permission) lives in its
                    // own screen; this tab is its entry point.
                    item(key = "local") {
                        EditorialSurface {
                            SignalEyebrow(stringResource(R.string.library_device_eyebrow))
                            SurfaceTitle(stringResource(R.string.library_local_entry_title), Modifier.padding(top = 6.dp))
                            Text(
                                stringResource(R.string.library_local_entry_body),
                                style = mr(13, FontWeight.Medium, lineHeight = 19),
                                color = RizxTheme.colors.muted,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            EditorialButton(
                                stringResource(R.string.library_open_local_music),
                                onClick = onOpenLocal,
                                modifier = Modifier.padding(top = 18.dp).fillMaxWidth(),
                                primary = true,
                            )
                        }
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

// ---- hero + tabs --------------------------------------------------------------------------------

/** Kicker, display title, a sentence, and the two hero actions side by side over a 2dp rule. */
@Composable
private fun LibraryHero(likedCount: Int, onNew: () -> Unit, onImport: () -> Unit) {
    val c = RizxTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .bottomRule(c.hardLine, Editorial.Frame)
            .padding(bottom = 25.dp),
    ) {
        Kicker(stringResource(R.string.library_kicker, likedCount))
        DisplayTitle(stringResource(R.string.library_hero_title), Modifier.padding(top = 9.dp))
        Lede(stringResource(R.string.library_hero_intro), Modifier.padding(top = 16.dp))
        EqualRow(Modifier.padding(top = 24.dp)) {
            EditorialButton(
                stringResource(R.string.library_new_playlist),
                onClick = onNew,
                modifier = Modifier.weight(1f),
                icon = RizxIcons.Add,
                primary = true,
                dense = true,
            )
            EditorialButton(
                stringResource(R.string.action_import),
                onClick = onImport,
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.AddLink,
                contentDescription = stringResource(R.string.library_import_playlist_desc),
                dense = true,
            )
        }
    }
}

/** The tab strip: outlined pills that bleed to the screen edge and scroll sideways. */
@Composable
private fun LibraryTabs(current: LibraryTab, margin: androidx.compose.ui.unit.Dp, onSelect: (LibraryTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .bleed(margin)
            .horizontalScroll(rememberScrollState())
            .padding(start = margin, end = margin, top = 24.dp, bottom = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LibraryTab.entries.forEach { entry ->
            EditorialTab(stringResource(entry.labelRes), active = current == entry, onClick = { onSelect(entry) })
        }
    }
}

// ---- overview -----------------------------------------------------------------------------------

/** The All tab: one framed surface per category, playlists first because they are what you come for. */
private fun LazyListScope.overview(
    playlists: List<PlaylistSummary>,
    digests: Map<String, PlaylistDigest>,
    liked: List<Track>,
    downloads: List<DownloadedTrack>,
    recents: List<Track>,
    onOpenPlaylist: (String) -> Unit,
    onNewPlaylist: () -> Unit,
    onShow: (LibraryTab) -> Unit,
    onPlayLiked: (Int) -> Unit,
    onPlayDownload: (Int) -> Unit,
    onPlayRecent: (Int) -> Unit,
) {
    item(key = "ov-playlists") {
        EditorialSurface(Modifier.padding(bottom = 14.dp)) {
            SurfaceHeading(
                eyebrow = stringResource(R.string.library_collections_eyebrow, LibraryStats.padded(playlists.size)),
                title = stringResource(R.string.library_your_playlists),
                action = stringResource(R.string.action_see_all),
                onAction = { onShow(LibraryTab.Playlists) },
            )
            Column(Modifier.padding(top = 20.dp).topRule(Editorial.rule)) {
                playlists.take(OVERVIEW_PLAYLISTS).forEach { playlist ->
                    PlaylistRow(playlist, digests[playlist.id], onClick = { onOpenPlaylist(playlist.id) })
                }
                if (playlists.isEmpty()) NewPlaylistRow(onNewPlaylist)
            }
        }
    }

    item(key = "ov-liked") {
        EditorialSurface(Modifier.padding(bottom = 14.dp)) {
            SurfaceHeading(
                eyebrow = stringResource(R.string.library_favorites_eyebrow),
                title = stringResource(R.string.library_songs_you_like),
                action = stringResource(R.string.library_see_all_count, liked.size),
                onAction = { onShow(LibraryTab.Liked) },
            )
            LikedHero(liked, Modifier.padding(top = 20.dp), onPlay = { onPlayLiked(0) })
            Column(Modifier.padding(top = 18.dp).topRule(Editorial.rule)) {
                liked.take(OVERVIEW_SONGS).forEachIndexed { index, track ->
                    // A preview row plays the *whole* liked list, not the three shown: `take` keeps the
                    // indices, and this is a peek at the tab rather than the tab itself.
                    PreviewRow(track, onPlay = { onPlayLiked(index) })
                }
            }
            if (liked.isEmpty()) {
                Text(
                    stringResource(R.string.library_no_liked_body),
                    style = mr(12, FontWeight.Medium, lineHeight = 17),
                    color = RizxTheme.colors.muted,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    if (downloads.isNotEmpty()) {
        item(key = "ov-downloads") {
            EditorialSurface(Modifier.padding(bottom = 14.dp)) {
                SurfaceHeading(
                    eyebrow = stringResource(R.string.library_offline_eyebrow),
                    title = stringResource(R.string.library_section_downloads),
                    action = stringResource(R.string.library_see_all_count, downloads.size),
                    onAction = { onShow(LibraryTab.Downloads) },
                )
                Column(Modifier.padding(top = 20.dp).topRule(Editorial.rule)) {
                    downloads.take(OVERVIEW_SONGS).forEachIndexed { index, entry ->
                        PreviewRow(entry.track, onPlay = { onPlayDownload(index) })
                    }
                }
            }
        }
    }

    item(key = "ov-recent") {
        EditorialSurface(Modifier.padding(bottom = 14.dp)) {
            SurfaceHeading(
                eyebrow = stringResource(R.string.library_history_eyebrow),
                title = stringResource(R.string.library_recent_heading),
                action = if (recents.size > OVERVIEW_SONGS) stringResource(R.string.action_see_all) else null,
                onAction = if (recents.size > OVERVIEW_SONGS) ({ onShow(LibraryTab.Recent) }) else null,
            )
            Column(Modifier.padding(top = 20.dp).topRule(Editorial.rule)) {
                recents.take(OVERVIEW_SONGS).forEachIndexed { index, track ->
                    PreviewRow(track, onPlay = { onPlayRecent(index) })
                }
            }
            if (recents.isEmpty()) {
                Text(
                    stringResource(R.string.library_no_recent_body),
                    style = mr(12, FontWeight.Medium, lineHeight = 17),
                    color = RizxTheme.colors.muted,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/**
 * The ink card that sums up the liked songs: a four-cover collage, the count in display type, the
 * running time and artist count, a red play block — and the big translucent heart the design signs it
 * with. Tapping it plays the list from the top.
 */
@Composable
private fun LikedHero(tracks: List<Track>, modifier: Modifier = Modifier, onPlay: () -> Unit) {
    val c = RizxTheme.colors
    val covers = tracks.take(12).mapNotNull { it.artwork.tileUrl() }.take(4)
    val playDesc = stringResource(R.string.library_play_liked_desc)
    Box(
        modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(c.accent)
            .border(Editorial.Frame, c.hardLine, RectangleShape)
            .clickableScale(scale = 0.99f, enabled = tracks.isNotEmpty(), onClick = onPlay)
            .padding(14.dp),
    ) {
        Text(
            "♥",
            style = sg(150, FontWeight.Medium, 0f),
            color = c.redAccent.copy(alpha = 0.34f),
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 44.dp, y = (-74).dp),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(92.dp).border(1.dp, c.onFill.copy(alpha = 0.4f), RectangleShape)) {
                CollageArt(covers, seed = "liked", modifier = Modifier.fillMaxSize(), initial = "♥")
            }
            Column(Modifier.weight(1f).padding(bottom = 5.dp, end = 36.dp)) {
                CodeLabel(stringResource(R.string.library_liked_archive), color = c.onFill.copy(alpha = 0.55f), size = 10)
                Text(
                    countLabel(tracks.size, R.string.library_count_song_one, R.string.library_count_song_other),
                    style = sg(24, FontWeight.Medium, -0.04f, lineHeight = 26),
                    color = c.onFill,
                    modifier = Modifier.padding(top = 7.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    likedStats(tracks),
                    style = mr(10, FontWeight.Normal),
                    color = c.onFill.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 4.dp, y = 4.dp)
                .size(40.dp)
                .background(c.redAccent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RizxIcons.Play, playDesc, tint = c.onRed, modifier = Modifier.size(20.dp))
        }
    }
}

/** `44 h 07 min · 135 artists` — the two numbers the design prints about a collection of songs. */
@Composable
private fun likedStats(tracks: List<Track>): String =
    LibraryStats.formatLongDuration(LibraryStats.totalMs(tracks)) + " · " +
        countLabel(LibraryStats.distinctArtists(tracks), R.string.library_count_artist_one, R.string.library_count_artist_other)

// ---- category views -----------------------------------------------------------------------------

/**
 * The heading of one category at full width: eyebrow, display title, a summary line, then — stacked
 * on a phone, as the design does below 768px — the tab's own action and its search field.
 */
@Composable
private fun ViewHeader(
    eyebrow: String,
    title: String,
    summary: String?,
    action: (@Composable () -> Unit)? = null,
    search: (@Composable () -> Unit)? = null,
) {
    val c = RizxTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Column(Modifier.fillMaxWidth().bottomRule(c.hardLine, Editorial.Frame).padding(bottom = 22.dp)) {
            SignalEyebrow(eyebrow)
            DisplayTitle(title, Modifier.padding(top = 5.dp), widthFraction = 0.153f, maxSp = 76)
            if (summary != null) {
                Text(summary, style = mr(13, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 13.dp))
            }
            if (action != null) Box(Modifier.padding(top = 20.dp)) { action() }
        }
        if (search != null) Box(Modifier.padding(top = 20.dp)) { search() }
    }
}

/** The 2dp rule a song list starts with. */
@Composable
private fun ListTop() {
    Box(Modifier.fillMaxWidth().height(Editorial.Frame).background(RizxTheme.colors.hardLine))
}

/** The playlist tiles, [columns] to a row, ending with the tile that starts a new one. */
private fun LazyListScope.playlistGrid(
    playlists: List<PlaylistSummary>,
    digests: Map<String, PlaylistDigest>,
    columns: Int,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    val cells: List<PlaylistSummary?> = playlists + listOf<PlaylistSummary?>(null)
    val rows = cells.withIndex().chunked(columns)
    itemsIndexed(rows, key = { _, row -> "grid-" + row.joinToString("|") { it.value?.id ?: "new" } }) { rowIndex, row ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp).staggeredReveal(rowIndex),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            row.forEach { (index, playlist) ->
                if (playlist == null) {
                    NewPlaylistTile(index + 1, Modifier.weight(1f), onNew)
                } else {
                    PlaylistTile(index + 1, playlist, digests[playlist.id], Modifier.weight(1f)) { onOpen(playlist.id) }
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

// Keys are prefixed per section: the same track can sit in both Liked and Recent, and a LazyColumn
// crashes on duplicate keys.

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
            SongRow(index, track, onPlay = { onPlay(index) }) {
                DownloadButton(
                    state = states[track.source.identityKey],
                    onDownload = { vm.downloadTrack(track) },
                    onCancel = { vm.cancelDownload(track.source.identityKey) },
                )
                RizxIconButton(
                    RizxIcons.Favorite,
                    stringResource(R.string.library_remove_from_liked_desc),
                    onClick = { onUnfavorite(track) },
                    size = 44.dp,
                    iconSize = 21.dp,
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
        val onPhone = entry.exportedUri != null
        Box(Modifier.staggeredReveal(index)) {
            SongRow(
                index,
                entry.track,
                onPlay = { onPlay(index) },
                // What the file is and where it lives — in words, under the artist. The action sits here
                // too rather than in the trailing strip, where a label that long would have squeezed the
                // song title down to a few characters.
                meta = {
                    val format = "${entry.container.uppercase()} · ${formatBytes(entry.sizeBytes)}"
                    if (onPhone) {
                        CodeLabel(format, size = 10)
                        Spacer(Modifier.height(3.dp))
                        CodeLabel(stringResource(R.string.library_on_phone), size = 10, color = RizxTheme.colors.redAccent)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CodeLabel(format, size = 10)
                            Text(
                                stringResource(R.string.library_save_to_phone).uppercase(),
                                style = mr(10, FontWeight.SemiBold, 0.06f),
                                color = RizxTheme.colors.redAccent,
                                modifier = Modifier.clickableScale(scale = 0.94f, onClick = { onExport(entry) }).padding(vertical = 4.dp),
                            )
                        }
                    }
                },
            ) {
                // Already published: the row says so, so this is only the way back from having deleted the
                // copy on the phone by hand — small, and never the loudest thing in the row.
                if (onPhone) {
                    RizxIconButton(
                        Icons.Filled.DriveFileMove,
                        stringResource(R.string.library_export_again_desc),
                        onClick = { onExport(entry) },
                        size = 44.dp,
                        iconSize = 20.dp,
                        tint = RizxTheme.colors.text2,
                    )
                }
                RizxIconButton(
                    Icons.Filled.DeleteOutline,
                    stringResource(R.string.library_delete_download_desc),
                    onClick = { onDelete(entry) },
                    size = 44.dp,
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
            SongRow(index, track, onPlay = { onPlay(index) })
        }
    }
}

// ---- rows and tiles -----------------------------------------------------------------------------

/**
 * The numbered song row of a category list: serial, 52dp cover in a hairline, title and artist, and
 * the actions on the right. [meta] is a third line under the artist (downloads use it to say what the
 * file is and where it lives).
 */
@Composable
private fun SongRow(
    index: Int,
    track: Track,
    onPlay: () -> Unit,
    meta: (@Composable () -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay)
            .bottomRule(Editorial.rule)
            .heightIn(min = 72.dp)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(LibraryStats.padded(index + 1), style = mr(12, FontWeight.Medium), color = c.muted, modifier = Modifier.width(24.dp))
        CoverArt(
            tintFor(track.source.id), initial = null, Modifier.size(52.dp),
            imageUrl = track.artwork.tileUrl(), borderColor = c.hardLine,
        )
        Column(Modifier.weight(1f).padding(start = 3.dp)) {
            Text(track.title, style = mr(13, FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                track.artists.joinToString { it.name }.ifEmpty { stringResource(R.string.unknown_artist) },
                style = mr(11, FontWeight.Medium),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            meta?.let {
                Spacer(Modifier.height(3.dp))
                it()
            }
        }
        trailing?.invoke(this)
    }
}

/** A preview row inside an overview surface: 46dp cover, title, artist and the running time. */
@Composable
private fun PreviewRow(track: Track, onPlay: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay)
            .bottomRule(Editorial.rule)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverArt(tintFor(track.source.id), initial = null, Modifier.size(46.dp), imageUrl = track.artwork.tileUrl())
        Column(Modifier.weight(1f)) {
            Text(track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                track.artists.joinToString { it.name }.ifEmpty { stringResource(R.string.unknown_artist) },
                style = mr(11, FontWeight.Medium),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(LibraryStats.clock(track.durationMs), style = mr(11, FontWeight.Medium), color = c.muted)
    }
}

/** A playlist in the overview stack: collage cover, name, `Private · 177 songs`, and an arrow. */
@Composable
private fun PlaylistRow(playlist: PlaylistSummary, digest: PlaylistDigest?, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onClick)
            .bottomRule(Editorial.rule)
            .heightIn(min = 82.dp)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(58.dp).border(1.dp, c.hardLine, RectangleShape)) {
            PlaylistArt(playlist, digest, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = mr(15, FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                playlistSubtitle(playlist),
                style = mr(12, FontWeight.Medium),
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        RowArrow()
    }
}

/** The overview's answer to an empty library: the "new playlist" row, in the stack's own shape. */
@Composable
private fun NewPlaylistRow(onClick: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onClick)
            .bottomRule(Editorial.rule)
            .heightIn(min = 82.dp)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(58.dp).background(c.text.copy(alpha = 0.04f)).border(1.dp, c.hardLine, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RizxIcons.Add, null, tint = c.muted, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.library_new_playlist), style = mr(15, FontWeight.Medium), color = c.text, maxLines = 1)
            Text(stringResource(R.string.library_start_collection), style = mr(12, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 4.dp))
        }
        RowArrow()
    }
}

/** A playlist tile: square art with its red serial, the name and `55 songs · 3 h 11 min`. */
@Composable
private fun PlaylistTile(
    serial: Int,
    playlist: PlaylistSummary,
    digest: PlaylistDigest?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = RizxTheme.colors
    EditorialSurface(modifier, padding = PaddingValues(10.dp), onClick = onClick) {
        Box {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, c.hardLine, RectangleShape)) {
                PlaylistArt(playlist, digest, Modifier.fillMaxSize())
            }
            IndexTag("P${LibraryStats.padded(serial)}", Modifier.padding(7.dp))
        }
        Text(playlist.name, style = mr(14, FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 11.dp))
        Text(
            buildString {
                append(countLabel(playlist.itemCount, R.string.library_count_song_one, R.string.library_count_song_other))
                digest?.takeIf { it.durationMs > 0L }?.let { append(" · ").append(LibraryStats.formatLongDuration(it.durationMs)) }
            },
            style = mr(11, FontWeight.Medium),
            color = c.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The last tile of the grid: a `+` where the art would be, and the next serial. */
@Composable
private fun NewPlaylistTile(serial: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = RizxTheme.colors
    EditorialSurface(modifier, padding = PaddingValues(10.dp), onClick = onClick) {
        Box {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).background(c.text.copy(alpha = 0.04f)).border(1.dp, c.hardLine, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(RizxIcons.Add, null, tint = c.muted, modifier = Modifier.size(58.dp))
            }
            IndexTag("P${LibraryStats.padded(serial)}", Modifier.padding(7.dp))
        }
        Text(stringResource(R.string.library_new_playlist), style = mr(14, FontWeight.Medium), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 11.dp))
        Text(stringResource(R.string.library_start_collection), style = mr(11, FontWeight.Medium), color = c.muted, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * A playlist's art, in the design's order: its own cover when it has one, else a collage of its songs'
 * covers, else the tinted tile with its initial.
 */
@Composable
private fun PlaylistArt(playlist: PlaylistSummary, digest: PlaylistDigest?, modifier: Modifier) {
    val own = playlist.artworkUrl
    if (own != null) {
        CoverArt(tintFor(playlist.id), initial = null, modifier, imageUrl = own, borderWidth = 0.dp)
        return
    }
    val covers = digest?.covers?.mapNotNull { it.tileUrl() }.orEmpty()
    CollageArt(covers, seed = playlist.id, modifier = modifier, initial = playlist.name.take(1).uppercase(), initialSize = 22)
}

/** `Private · 177 songs`, or the description when the playlist has one. */
@Composable
private fun playlistSubtitle(playlist: PlaylistSummary): String {
    val nature = playlist.description?.takeIf { it.isNotBlank() }
        ?: stringResource(if (playlist.isImported) R.string.library_imported else R.string.library_private)
    return nature + " · " + countLabel(playlist.itemCount, R.string.library_count_song_one, R.string.library_count_song_other)
}

/** Localized "N noun(s)" — resolves the right plural resource for [count] and formats it in. */
@Composable
private fun countLabel(count: Int, @StringRes one: Int, @StringRes other: Int): String =
    stringResource(if (count == 1) one else other, count)

// ---- empty states -------------------------------------------------------------------------------

@Composable
private fun NoMatches(query: String) = EmptyBlock(
    title = stringResource(R.string.filter_no_matches_title),
    body = stringResource(R.string.filter_no_matches_body, query),
)

@Composable
private fun LikedEmpty() = EmptyBlock(
    title = stringResource(R.string.library_no_liked_title),
    body = stringResource(R.string.library_no_liked_body),
)

@Composable
private fun RecentEmpty() = EmptyBlock(
    title = stringResource(R.string.library_no_recent_title),
    body = stringResource(R.string.library_no_recent_body),
)

/** The CTA only appears when there's somewhere to send you — an empty Liked tab would be a dead end. */
@Composable
private fun DownloadsEmpty(onGoToLiked: (() -> Unit)?) = EmptyBlock(
    title = stringResource(R.string.library_no_downloads_title),
    body = stringResource(R.string.library_no_downloads_body),
    actionLabel = onGoToLiked?.let { stringResource(R.string.library_go_to_liked) },
    onAction = onGoToLiked,
)

/** The file name behind a SAF [uri], extension dropped — names imports whose format carries none (CSV). */
private fun Context.displayNameOf(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
