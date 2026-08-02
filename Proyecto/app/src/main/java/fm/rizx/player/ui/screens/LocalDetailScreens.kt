package fm.rizx.player.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.local.LocalLibraryViewModel
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal

/** A local album's tracks (filtered from the in-memory scan), in track-number order. */
@Composable
fun LocalAlbumScreen(
    albumId: String,
    onBack: () -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddNext: (Track) -> Unit,
    vm: LocalLibraryViewModel = hiltViewModel(),
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (songs.isEmpty()) vm.refresh() }
    val tracks = remember(songs, albumId) {
        songs.filter { it.album?.source?.id == "album:$albumId" }.sortedBy { it.trackNumber ?: Int.MAX_VALUE }
    }
    LocalDetail(
        kind = stringResource(R.string.local_kind_album),
        title = tracks.firstOrNull()?.album?.title ?: stringResource(R.string.local_untitled_album),
        subtitle = tracks.firstOrNull()?.artists?.firstOrNull()?.name,
        artworkUrl = tracks.firstOrNull()?.artwork.coverUrl(),
        circle = false,
        tracks = tracks,
        numbered = true,
        onBack = onBack,
        onPlay = { vm.playAlbum(albumId, it) },
        onShuffle = { vm.shuffleAlbum(albumId) },
        vm = vm,
        onAddToPlaylist = onAddToPlaylist,
        onAddToQueue = onAddToQueue,
        onAddNext = onAddNext,
    )
}

/** A local artist's tracks, with their albums on a shelf above — the scan already knows the grouping. */
@Composable
fun LocalArtistScreen(
    artistId: String,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddNext: (Track) -> Unit,
    vm: LocalLibraryViewModel = hiltViewModel(),
) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (songs.isEmpty()) vm.refresh() }
    val tracks = remember(songs, artistId) {
        songs.filter { track -> track.artists.any { it.source?.id == "artist:$artistId" } }
    }
    val albums = remember(songs, artistId) { vm.artistAlbums(artistId) }
    LocalDetail(
        kind = stringResource(R.string.local_kind_artist),
        title = tracks.firstOrNull()?.artists?.firstOrNull()?.name ?: stringResource(R.string.local_untitled_artist),
        subtitle = stringResource(
            if (tracks.size == 1) R.string.local_count_song_one else R.string.local_count_song_other,
            tracks.size,
        ),
        artworkUrl = tracks.firstOrNull()?.artwork.coverUrl(),
        circle = true,
        tracks = tracks,
        numbered = false,
        onBack = onBack,
        onPlay = { vm.playArtist(artistId, it) },
        onShuffle = { vm.shuffleArtist(artistId) },
        vm = vm,
        onAddToPlaylist = onAddToPlaylist,
        onAddToQueue = onAddToQueue,
        onAddNext = onAddNext,
        albumsShelf = albums.takeIf { it.size > 1 }?.let { shelf ->
            { AlbumsShelf(shelf, onOpenAlbum) }
        },
    )
}

@Composable
private fun AlbumsShelf(albums: List<fm.rizx.player.ui.local.LocalAlbum>, onOpen: (String) -> Unit) {
    val c = RizxTheme.colors
    Text(
        stringResource(R.string.local_albums_heading).uppercase(),
        style = code(11, FontWeight.Bold), color = c.muted,
        modifier = Modifier.padding(start = 22.dp, top = 10.dp, bottom = 6.dp),
    )
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            LocalAlbumCard(album, modifier = Modifier.width(120.dp)) { onOpen(album.id) }
        }
    }
}

@Composable
private fun LocalDetail(
    kind: String,
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    circle: Boolean,
    tracks: List<Track>,
    numbered: Boolean,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    vm: LocalLibraryViewModel,
    onAddToPlaylist: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddNext: (Track) -> Unit,
    albumsShelf: (@Composable () -> Unit)? = null,
) {
    val c = RizxTheme.colors
    val likedKeys by vm.likedKeys.collectAsStateWithLifecycle()
    val badges by vm.losslessBadges.collectAsStateWithLifecycle()
    val totalMs = remember(tracks) { tracks.sumOf { it.durationMs ?: 0L } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RizxIconButton(RizxIcons.Back, stringResource(R.string.local_back), onBack, tint = c.text)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CoverArt(tintForDetail(title), initial = title.take(1), Modifier.size(84.dp), initialSize = 30, circle = circle, imageUrl = artworkUrl)
            Column(Modifier.weight(1f)) {
                Text(kind, style = mr(11, FontWeight.Bold, 0.2f), color = c.accent)
                Text(title, style = sg(22, FontWeight.Bold, -0.02f), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                val line = listOfNotNull(subtitle, detailDuration(totalMs)).joinToString(" · ")
                if (line.isNotBlank()) {
                    Text(line, style = mr(13, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        // The whole-list actions the old detail never had: start it, or roll the dice on it.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DetailAction(stringResource(R.string.local_play_all), filled = true) { onPlay(0) }
            DetailAction(stringResource(R.string.local_shuffle), filled = false, onClick = onShuffle)
        }
        LazyColumn {
            albumsShelf?.let { shelf -> item { shelf() } }
            item {
                Text(
                    stringResource(R.string.local_tracks_heading),
                    style = code(11, FontWeight.Bold), color = c.muted,
                    modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 2.dp),
                )
            }
            itemsIndexed(tracks, key = { _, t -> "locd-${t.source.id}" }) { index, track ->
                Box(Modifier.staggeredReveal(index.coerceAtMost(12)).padding(horizontal = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (numbered) {
                            Box(Modifier.width(34.dp).padding(start = 14.dp), contentAlignment = Alignment.Center) {
                                Text("${track.trackNumber ?: index + 1}", style = mr(13, FontWeight.Medium), color = c.muted)
                            }
                        } else {
                            Spacer(Modifier.width(14.dp))
                        }
                        Box(Modifier.weight(1f)) {
                            LocalTrackRow(
                                track = track,
                                liked = track.source.identityKey in likedKeys,
                                badge = badges[track.source.identityKey],
                                onPlay = { onPlay(index) },
                                onToggleLike = { vm.toggleFavorite(track) },
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onAddToQueue = { onAddToQueue(track) },
                                onAddNext = { onAddNext(track) },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(LocalBottomInset.current + 16.dp)) }
        }
    }
}

@Composable
private fun DetailAction(label: String, filled: Boolean, onClick: () -> Unit) {
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

/** "37 min" / "1 h 12 min" — and seconds below a minute, so a short album never reads "0 min". */
private fun detailDuration(ms: Long): String? {
    if (ms <= 0) return null
    val minutes = ms / 60_000
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours} h ${minutes % 60} min"
        minutes > 0 -> "$minutes min"
        else -> "${ms / 1000} s"
    }
}

private fun tintForDetail(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % 7
