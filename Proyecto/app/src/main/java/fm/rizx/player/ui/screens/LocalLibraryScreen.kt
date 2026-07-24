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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.RizxChip
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.local.LocalAlbum
import fm.rizx.player.ui.local.LocalArtist
import fm.rizx.player.ui.local.LocalLibraryViewModel
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal

private enum class LocalView(@StringRes val labelRes: Int) {
    Songs(R.string.local_tab_songs),
    Albums(R.string.local_tab_albums),
    Artists(R.string.local_tab_artists),
}

/** True if READ_MEDIA_AUDIO is granted (minSdk 34 — no version guard needed). */
private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * The on-device music player: everything scanned from `MediaStore`, in Songs / Albums / Artists views.
 * Playback, favorites, queue and recents all reuse the shared pipeline (a local track is just a [Track]
 * with a `"local"` source). Requests `READ_MEDIA_AUDIO` contextually; denial shows a re-prompt, never a crash.
 */
@Composable
fun LocalLibraryScreen(
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
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

    val songs by vm.songs.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(LocalView.Songs) }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RizxIconButton(RizxIcons.Back, stringResource(R.string.local_back), onBack, tint = c.text)
            Text(stringResource(R.string.local_title), style = sg(24, FontWeight.Bold, -0.02f), color = c.text)
        }

        if (granted) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LocalView.entries.forEach { v -> RizxChip(stringResource(v.labelRes), active = view == v, onClick = { view = v }) }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!granted) {
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
                    LocalView.Songs -> SongsList(songs, onPlay = vm::playAll)
                    LocalView.Albums -> AlbumsGrid(albums, onOpenAlbum)
                    LocalView.Artists -> ArtistsGrid(artists, onOpenArtist)
                }
            }
        }
    }
}

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
private fun SongsList(songs: List<Track>, onPlay: (Int) -> Unit) {
    if (songs.isEmpty()) {
        EmptyLocal(stringResource(R.string.local_no_music_title), stringResource(R.string.local_no_music_body))
        return
    }
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomInset.current + 16.dp)) {
        items(songs, key = { "loc-${it.source.id}" }) { track ->
            LocalSongRow(track) { onPlay(songs.indexOf(track)) }
        }
    }
}

@Composable
private fun LocalSongRow(track: Track, onPlay: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        CoverArt(tintFor(track.source.id), initial = track.title.take(1), Modifier.size(46.dp), imageUrl = track.artwork.coverUrl())
        Column(Modifier.weight(1f)) {
            Text(track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artists.joinToString { it.name }.ifEmpty { stringResource(R.string.unknown_artist) }, style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Text(formatDuration(track.durationMs), style = mr(12, FontWeight.Medium), color = c.muted)
    }
}

@Composable
private fun AlbumsGrid(albums: List<LocalAlbum>, onOpen: (String) -> Unit) {
    if (albums.isEmpty()) {
        EmptyLocal(stringResource(R.string.local_no_albums_title), stringResource(R.string.local_no_albums_body))
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
private fun LocalAlbumCard(album: LocalAlbum, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Column(Modifier.clickableScale(scale = 0.98f, onClick = onClick)) {
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
private fun ArtistsGrid(artists: List<LocalArtist>, onOpen: (String) -> Unit) {
    if (artists.isEmpty()) {
        EmptyLocal(stringResource(R.string.local_no_artists_title), stringResource(R.string.local_no_artists_body))
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
private fun EmptyLocal(title: String, body: String) {
    val c = RizxTheme.colors
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = sg(18, FontWeight.Bold), color = c.text)
            Text(body, style = mr(13, FontWeight.Medium), color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun tintFor(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % 7
