package fm.rizx.player.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.core.formatDuration
import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.AlphabetRail
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.FilterEmpty
import fm.rizx.player.ui.components.LosslessTag
import fm.rizx.player.ui.components.RizxChip
import fm.rizx.player.ui.components.RizxFilterField
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.library.CreatePlaylistDialog
import fm.rizx.player.ui.util.ListFilter
import fm.rizx.player.ui.util.rememberRizxHaptics
import fm.rizx.player.ui.local.LocalAlbum
import fm.rizx.player.ui.local.LocalArtist
import fm.rizx.player.ui.local.LocalLibraryViewModel
import fm.rizx.player.ui.local.LocalSort
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal
import kotlinx.coroutines.launch

private enum class LocalView(@StringRes val labelRes: Int) {
    Songs(R.string.local_tab_songs),
    Playlists(R.string.local_tab_playlists),
    Albums(R.string.local_tab_albums),
    Artists(R.string.local_tab_artists),
    Files(R.string.local_tab_files),
}

/** True if READ_MEDIA_AUDIO is granted (minSdk 34 — no version guard needed). */
private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * The on-device music player: the MediaStore scan in Songs / Albums / Artists views, the user's own
 * playlists, and a Files view for anything the scan can't see — audio opened straight from the system
 * file explorer (SAF), which needs **no permission at all**. That is why the permission gate guards only
 * the scan views: a denied permission still leaves a working player.
 *
 * Playback, favorites, queue and recents all reuse the shared pipeline (a local track is just a [Track]
 * with a `"local"` — or `"file"` — source).
 */
@Composable
fun LocalLibraryScreen(
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddNext: (Track) -> Unit,
    vm: LocalLibraryViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var granted by remember { mutableStateOf(hasAudioPermission(context)) }
    // True once Android has stopped offering the dialog (declined twice). From then on only Settings works.
    var blocked by remember { mutableStateOf(false) }
    // Survives rotation so a config change can't re-prompt on top of an open dialog.
    var asked by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        // `shouldShowRequestPermissionRationale` is false both before the first ask and after the
        // permanent denial; checking it here — right after a refusal — separates the two.
        blocked = !ok && activity?.let { !it.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_AUDIO) } == true
    }

    // Opening this screen *is* the request: the user came here to see their own music, so making them
    // tap a second button first is a step with no decision in it.
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.READ_MEDIA_AUDIO)
        }
    }

    // Granting from Settings happens outside the app, so re-check when we come back rather than leaving
    // the gate up over a permission the user just allowed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !granted && hasAudioPermission(context)) {
                granted = true
                blocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(granted) { if (granted) vm.refresh() }

    // Live while the screen is: copy files over USB and the list follows, no manual rescan.
    DisposableEffect(granted) {
        val unregister = if (granted) vm.startObserving() else ({})
        onDispose { unregister() }
    }

    // SAF — no permission involved. Multi-select for files; a whole tree for folders.
    val openFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.openAndPlayFiles(uris.map { it.toString() })
    }
    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.openAndPlayFolder(it.toString()) }
    }

    val songs by vm.sortedSongs.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val playlists by vm.ownPlaylists.collectAsStateWithLifecycle()
    val opened by vm.openedRecent.collectAsStateWithLifecycle()
    val likedKeys by vm.likedKeys.collectAsStateWithLifecycle()
    val badges by vm.losslessBadges.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val folderSkipped by vm.folderSkipped.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(LocalView.Songs) }
    // One filter, and it belongs to the view you are on: switching views clears it rather than carrying a
    // song query over to albums, where it would silently hide most of the grid.
    var filter by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    // A device scan is the one list nobody curated — file names, WhatsApp audio, half-tagged albums — so it
    // is the list most in need of narrowing, and it is all already in memory (see [ListFilter]).
    val visibleSongs = remember(songs, filter) { songs.filter { ListFilter.matchesTrack(filter, it) } }
    val visibleAlbums = remember(albums, filter) { albums.filter { ListFilter.matches(filter, it.title, it.artist) } }
    val visibleArtists = remember(artists, filter) { artists.filter { ListFilter.matches(filter, it.name) } }
    val visiblePlaylists = remember(playlists, filter) { playlists.filter { ListFilter.matches(filter, it.name, it.description) } }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RizxIconButton(RizxIcons.Back, stringResource(R.string.local_back), onBack, tint = c.text)
            Text(stringResource(R.string.local_title), style = sg(24, FontWeight.Bold, -0.02f), color = c.text)
            Spacer(Modifier.weight(1f))
            // The explorer, one tap away from anywhere in the local player — "whenever you want".
            RizxIconButton(
                Icons.Outlined.FolderOpen,
                stringResource(R.string.local_open_shortcut),
                onClick = { openFiles.launch(arrayOf("audio/*")) },
                tint = c.text,
            )
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LocalView.entries.forEach { v ->
                RizxChip(stringResource(v.labelRes), active = view == v, onClick = { view = v; filter = "" })
            }
        }

        // Only once the view has something to narrow — a bar over an empty scan is a control with no
        // list under it. Files filters its recents; Playlists its list.
        val hasContent = when (view) {
            LocalView.Songs -> songs.isNotEmpty()
            LocalView.Albums -> albums.isNotEmpty()
            LocalView.Artists -> artists.isNotEmpty()
            LocalView.Playlists -> playlists.isNotEmpty()
            LocalView.Files -> false // its two actions are the content; recents are few
        }
        if (hasContent) RizxFilterField(filter, { filter = it }, Modifier.padding(top = 10.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The gate stands only in front of the scan views: playlists and picked files owe MediaStore
            // nothing, so a denied permission must not brick them.
            val needsScan = view == LocalView.Songs || view == LocalView.Albums || view == LocalView.Artists
            if (needsScan && !granted) {
                PermissionGate(
                    blocked = blocked,
                    onGrant = {
                        // Once Android has stopped showing the dialog, launching it again does nothing at
                        // all — the only route left is the app's settings page.
                        if (blocked) context.openAppSettings() else launcher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                    },
                )
            } else {
                when (view) {
                    LocalView.Songs -> SongsView(
                        songs = visibleSongs,
                        query = filter,
                        sort = sort,
                        stats = stats,
                        likedKeys = likedKeys,
                        badges = badges,
                        onSetSort = vm::setSort,
                        onPlay = { vm.playAll(it, visibleSongs) },
                        onShuffle = { vm.shuffleAll(visibleSongs) },
                        onToggleLike = vm::toggleFavorite,
                        onAddToPlaylist = onAddToPlaylist,
                        onAddToQueue = onAddToQueue,
                        onAddNext = onAddNext,
                    )
                    LocalView.Playlists -> PlaylistsView(
                        playlists = visiblePlaylists,
                        query = filter,
                        onOpen = onOpenPlaylist,
                        onCreate = { creating = true },
                    )
                    LocalView.Albums -> AlbumsGrid(visibleAlbums, filter, onOpenAlbum)
                    LocalView.Artists -> ArtistsGrid(visibleArtists, filter, onOpenArtist)
                    LocalView.Files -> FilesView(
                        opened = opened,
                        likedKeys = likedKeys,
                        folderSkipped = folderSkipped,
                        onOpenFiles = { openFiles.launch(arrayOf("audio/*")) },
                        onOpenFolder = { openFolder.launch(null) },
                        onPlay = vm::playOpened,
                        onToggleLike = vm::toggleFavorite,
                        onAddToPlaylist = onAddToPlaylist,
                        onAddToQueue = onAddToQueue,
                        onAddNext = onAddNext,
                        onForget = vm::forgetOpened,
                        onDismissNotice = vm::dismissFolderNotice,
                    )
                }
            }
        }
    }

    if (creating) {
        CreatePlaylistDialog(onCreate = vm::createPlaylist, onDismiss = { creating = false })
    }
}

// ---- Songs ----

@Composable
private fun SongsView(
    songs: List<Track>,
    query: String,
    sort: LocalSort,
    stats: fm.rizx.player.ui.local.LocalStats,
    likedKeys: Set<String>,
    badges: Map<String, String>,
    onSetSort: (LocalSort) -> Unit,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onToggleLike: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddNext: (Track) -> Unit,
) {
    val c = RizxTheme.colors
    if (songs.isEmpty()) {
        if (query.isBlank()) {
            EmptyLocal(stringResource(R.string.local_no_music_title), stringResource(R.string.local_no_music_body))
        } else {
            FilterEmpty(query)
        }
        return
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberRizxHaptics()

    Column(Modifier.fillMaxSize()) {
        // The header owns the whole-list actions and the honest inventory line: how many, how long, how heavy.
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.local_stats_songs, stats.songCount),
                    style = mr(12, FontWeight.SemiBold), color = c.text2,
                )
                Text(
                    "${formatTotalDuration(stats.totalDurationMs)} · ${formatBytes(stats.totalSizeBytes)}",
                    style = code(10, FontWeight.Medium), color = c.muted,
                )
            }
            SortSelector(sort, onSetSort)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HeaderAction(stringResource(R.string.local_play_all), filled = true) { onPlay(0) }
            HeaderAction(stringResource(R.string.local_shuffle), filled = false, onClick = onShuffle)
        }

        Box(Modifier.fillMaxSize()) {
            // The rail owns the right edge while it is shown, so the rows must stop before it — otherwise
            // every row's ⋮ sits *under* the touch column and taps jump the list instead of opening menus.
            val railShown = sort == LocalSort.TITLE
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = 4.dp,
                    bottom = LocalBottomInset.current + 16.dp,
                    end = if (railShown) 24.dp else 0.dp,
                ),
            ) {
                // itemsIndexed, not `songs.indexOf(track)`: the index is what plays, and a list scanned off a
                // phone has repeated file names — indexOf would answer with the first of them, the wrong song.
                itemsIndexed(songs, key = { _, track -> "loc-${track.source.id}" }) { index, track ->
                    LocalTrackRow(
                        track = track,
                        liked = track.source.identityKey in likedKeys,
                        badge = badges[track.source.identityKey],
                        onPlay = { onPlay(index) },
                        onToggleLike = { onToggleLike(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onAddToQueue = { onAddToQueue(track) },
                        onAddNext = { onAddNext(track) },
                    )
                }
            }
            // The rail only when it can honestly help: alphabetical order, and enough initials to jump between.
            if (railShown) {
                val letters = remember(songs) { songs.map { initialOf(it.title) }.distinct() }
                val firstIndexByLetter = remember(songs) {
                    buildMap { songs.forEachIndexed { i, t -> putIfAbsent(initialOf(t.title), i) } }
                }
                AlphabetRail(
                    letters = letters,
                    onPick = { letter ->
                        firstIndexByLetter[letter]?.let { scope.launch { listState.scrollToItem(it) } }
                    },
                    haptics = haptics,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/** '#' for digits and symbols, the uppercased letter otherwise — the classic bucket rule. */
private fun initialOf(title: String): Char {
    val first = title.firstOrNull { !it.isWhitespace() } ?: '#'
    return if (first.isLetter()) first.uppercaseChar() else '#'
}

@Composable
private fun HeaderAction(label: String, filled: Boolean, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Text(
        label,
        style = code(11, FontWeight.Bold),
        color = if (filled) c.onFill else c.text,
        modifier = Modifier
            .background(if (filled) c.fill else c.elev)
            .clickableScale(scale = 0.95f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun SortSelector(sort: LocalSort, onSetSort: (LocalSort) -> Unit) {
    val c = RizxTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            "${stringResource(R.string.local_sort_label).uppercase()}: ${stringResource(sortLabel(sort)).uppercase()}",
            style = code(10, FontWeight.Bold),
            color = c.text2,
            modifier = Modifier.clickableScale(scale = 0.95f) { open = true }.padding(6.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LocalSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(sortLabel(option)),
                            style = mr(13, FontWeight.Medium),
                            color = if (option == sort) c.accent else c.text,
                        )
                    },
                    onClick = { onSetSort(option); open = false },
                )
            }
        }
    }
}

@StringRes
private fun sortLabel(sort: LocalSort): Int = when (sort) {
    LocalSort.TITLE -> R.string.local_sort_title
    LocalSort.RECENT -> R.string.local_sort_recent
    LocalSort.ARTIST -> R.string.local_sort_artist
    LocalSort.DURATION -> R.string.local_sort_duration
}

/** "3h 12m" / "42m" for the header's inventory line. */
private fun formatTotalDuration(ms: Long): String {
    val minutes = ms / 60_000
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
    else -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
}

/**
 * One local song row, shared by the Songs list, the Files recents and (via import) the detail screens:
 * cover, title/artist, the lossless badge when the file has earned one, the heart, and the ⋮ that makes
 * local music a first-class citizen of playlists and the queue.
 */
@Composable
internal fun LocalTrackRow(
    track: Track,
    liked: Boolean,
    badge: String?,
    onPlay: () -> Unit,
    onToggleLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddNext: () -> Unit,
    onForget: (() -> Unit)? = null,
) {
    val c = RizxTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        CoverArt(tintFor(track.source.id), initial = track.title.take(1), Modifier.size(46.dp), imageUrl = track.artwork.coverUrl())
        Column(Modifier.weight(1f)) {
            Text(track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (badge != null) LosslessTag(badge, Modifier.padding(end = 6.dp))
                Text(
                    track.artists.joinToString { it.name }.ifEmpty { stringResource(R.string.unknown_artist) },
                    style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(formatDuration(track.durationMs), style = mr(12, FontWeight.Medium), color = c.muted)
        Icon(
            if (liked) RizxIcons.Favorite else RizxIcons.FavoriteBorder,
            stringResource(if (liked) R.string.local_unlike else R.string.local_like),
            tint = if (liked) c.redAccent else c.text2,
            modifier = Modifier.size(21.dp).clickableScale(scale = 0.84f, onClick = onToggleLike),
        )
        Box {
            Icon(
                Icons.Filled.MoreVert,
                stringResource(R.string.local_more_for),
                tint = c.text2,
                modifier = Modifier.size(22.dp).clickableScale(scale = 0.86f) { menuOpen = true },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.local_play_next), style = mr(14, FontWeight.Medium), color = c.text) },
                    onClick = { onAddNext(); menuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.local_add_to_queue), style = mr(14, FontWeight.Medium), color = c.text) },
                    onClick = { onAddToQueue(); menuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.local_add_to_playlist), style = mr(14, FontWeight.Medium), color = c.text) },
                    onClick = { onAddToPlaylist(); menuOpen = false },
                )
                if (onForget != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.local_forget_file), style = mr(14, FontWeight.Medium), color = c.muted) },
                        onClick = { onForget(); menuOpen = false },
                    )
                }
            }
        }
    }
}

// ---- Playlists (the user's own) ----

@Composable
private fun PlaylistsView(
    playlists: List<PlaylistSummary>,
    query: String,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val c = RizxTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
            HeaderAction(stringResource(R.string.library_new_playlist).uppercase(), filled = true, onClick = onCreate)
        }
        if (playlists.isEmpty()) {
            if (query.isBlank()) {
                EmptyLocal(stringResource(R.string.local_no_playlists_title), stringResource(R.string.local_no_playlists_body))
            } else {
                FilterEmpty(query)
            }
            return
        }
        LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = LocalBottomInset.current + 16.dp)) {
            itemsIndexed(playlists, key = { _, p -> "locpl-${p.id}" }) { _, playlist ->
                Row(
                    Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover) { onOpen(playlist.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    CoverArt(tintFor(playlist.id), initial = playlist.name.take(1).uppercase(), Modifier.size(46.dp), imageUrl = playlist.artworkUrl)
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(
                                if (playlist.itemCount == 1) R.string.local_count_song_one else R.string.local_count_song_other,
                                playlist.itemCount,
                            ),
                            style = mr(12, FontWeight.Medium), color = c.muted,
                        )
                    }
                }
            }
        }
    }
}

// ---- Files (the explorer) ----

@Composable
private fun FilesView(
    opened: List<Track>,
    likedKeys: Set<String>,
    folderSkipped: Int,
    onOpenFiles: () -> Unit,
    onOpenFolder: () -> Unit,
    onPlay: (Int) -> Unit,
    onToggleLike: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddNext: (Track) -> Unit,
    onForget: (Track) -> Unit,
    onDismissNotice: () -> Unit,
) {
    val c = RizxTheme.colors
    LazyColumn(contentPadding = PaddingValues(top = 10.dp, bottom = LocalBottomInset.current + 16.dp)) {
        item {
            ExplorerAction(
                title = stringResource(R.string.local_open_files_title),
                caption = stringResource(R.string.local_open_files_caption),
                onClick = onOpenFiles,
            )
        }
        item {
            ExplorerAction(
                title = stringResource(R.string.local_open_folder_title),
                caption = stringResource(R.string.local_open_folder_caption),
                onClick = onOpenFolder,
            )
        }
        if (folderSkipped > 0) {
            item {
                Text(
                    stringResource(R.string.local_folder_skipped, folderSkipped),
                    style = mr(12, FontWeight.Medium), color = c.muted,
                    modifier = Modifier.clickableScale(scale = 0.98f, onClick = onDismissNotice).padding(vertical = 8.dp),
                )
            }
        }
        if (opened.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.FolderOpen, null, tint = c.muted, modifier = Modifier.size(40.dp))
                    Text(stringResource(R.string.local_no_opened_title), style = sg(16, FontWeight.Bold), color = c.text, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        stringResource(R.string.local_no_opened_body),
                        style = mr(12, FontWeight.Medium), color = c.muted, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                    )
                }
            }
        } else {
            item {
                Text(
                    stringResource(R.string.local_recently_opened).uppercase(),
                    style = code(11, FontWeight.Bold), color = c.muted,
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                )
            }
            itemsIndexed(opened, key = { _, t -> "locf-${t.source.id}" }) { index, track ->
                LocalTrackRow(
                    track = track,
                    liked = track.source.identityKey in likedKeys,
                    badge = null, // a picked file's mime is provider-reported; the play readout is the honest place
                    onPlay = { onPlay(index) },
                    onToggleLike = { onToggleLike(track) },
                    onAddToPlaylist = { onAddToPlaylist(track) },
                    onAddToQueue = { onAddToQueue(track) },
                    onAddNext = { onAddNext(track) },
                    onForget = { onForget(track) },
                )
            }
        }
    }
}

@Composable
private fun ExplorerAction(title: String, caption: String, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.elev)
            .clickableScale(scale = 0.98f, pressColor = c.rowHover, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .padding(bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.FolderOpen, null, tint = c.text, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = mr(14, FontWeight.SemiBold), color = c.text)
            Text(caption, style = mr(11, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 1.dp))
        }
    }
    Spacer(Modifier.padding(top = 8.dp))
}

// ---- shared bits (gate, grids, empty) ----

@Composable
private fun PermissionGate(blocked: Boolean, onGrant: () -> Unit) {
    val c = RizxTheme.colors
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.LibraryMusic, null, tint = c.muted, modifier = Modifier.size(44.dp))
            Text(stringResource(R.string.local_permission_title), style = sg(18, FontWeight.Bold), color = c.text, modifier = Modifier.padding(top = 14.dp))
            Text(
                stringResource(
                    if (blocked) R.string.local_permission_body_blocked else R.string.local_permission_body_default,
                ),
                style = mr(13, FontWeight.Medium), color = c.muted, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp),
            )
            // The label used c.onFill — the colour for text *on* a filled button — with no fill behind it,
            // so on the light theme it was pale ink on pale paper and read as disabled, or as nothing at all.
            Text(
                stringResource(if (blocked) R.string.local_permission_cta_blocked else R.string.local_permission_cta_default),
                style = sg(14, FontWeight.Bold), color = c.onFill,
                modifier = Modifier.padding(top = 18.dp)
                    .background(c.fill)
                    .clickableScale(scale = 0.94f, onClick = onGrant)
                    .padding(horizontal = 24.dp, vertical = 11.dp),
            )
        }
    }
}

/** Unwraps the Activity from Compose's context, which is a ContextWrapper chain, not the Activity itself. */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Opens this app's system settings page — the only way back once the permission dialog is exhausted. */
private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun AlbumsGrid(albums: List<LocalAlbum>, query: String, onOpen: (String) -> Unit) {
    if (albums.isEmpty()) {
        if (query.isBlank()) {
            EmptyLocal(stringResource(R.string.local_no_albums_title), stringResource(R.string.local_no_albums_body))
        } else {
            FilterEmpty(query)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(top = 10.dp, bottom = LocalBottomInset.current + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            Box(Modifier.staggeredReveal(albums.indexOf(album).coerceAtMost(8))) {
                LocalAlbumCard(album) { onOpen(album.id) }
            }
        }
    }
}

@Composable
internal fun LocalAlbumCard(album: LocalAlbum, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Column(modifier.clickableScale(scale = 0.98f, onClick = onClick)) {
        CoverArt(tintFor(album.id), initial = album.title.take(1), Modifier.fillMaxWidth().aspectRatio(1f), initialSize = 40, imageUrl = album.artworkUrl)
        Text(album.title, style = mr(13, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        Text(
            album.artist.ifEmpty {
                stringResource(
                    if (album.trackCount == 1) R.string.local_track_count_one else R.string.local_track_count_other,
                    album.trackCount,
                )
            },
            style = mr(11, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
private fun ArtistsGrid(artists: List<LocalArtist>, query: String, onOpen: (String) -> Unit) {
    if (artists.isEmpty()) {
        if (query.isBlank()) {
            EmptyLocal(stringResource(R.string.local_no_artists_title), stringResource(R.string.local_no_artists_body))
        } else {
            FilterEmpty(query)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(top = 10.dp, bottom = LocalBottomInset.current + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(artists, key = { it.id }) { artist ->
            LocalArtistCard(artist) { onOpen(artist.id) }
        }
    }
}

@Composable
private fun LocalArtistCard(artist: LocalArtist, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Column(
        Modifier.clickableScale(scale = 0.98f, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverArt(tintFor(artist.id), initial = artist.name.take(1), Modifier.size(96.dp), initialSize = 34, circle = true)
        Text(artist.name, style = mr(13, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Text(
            stringResource(
                if (artist.trackCount == 1) R.string.local_count_song_one else R.string.local_count_song_other,
                artist.trackCount,
            ),
            style = mr(11, FontWeight.Medium), color = c.muted, modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
internal fun EmptyLocal(title: String, body: String) {
    val c = RizxTheme.colors
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = sg(18, FontWeight.Bold), color = c.text)
            Text(body, style = mr(13, FontWeight.Medium), color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun tintFor(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % 7
