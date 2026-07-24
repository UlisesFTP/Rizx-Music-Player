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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.core.formatDuration
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
fun LocalAlbumScreen(albumId: String, onBack: () -> Unit, vm: LocalLibraryViewModel = hiltViewModel()) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (songs.isEmpty()) vm.refresh() }
    val tracks = remember(songs, albumId) {
        songs.filter { it.album?.source?.id == "album:$albumId" }.sortedBy { it.trackNumber ?: Int.MAX_VALUE }
    }
    LocalDetail(
        kind = "ALBUM",
        title = tracks.firstOrNull()?.album?.title ?: "Album",
        subtitle = tracks.firstOrNull()?.artists?.firstOrNull()?.name,
        artworkUrl = tracks.firstOrNull()?.artwork.coverUrl(),
        circle = false,
        tracks = tracks,
        numbered = true,
        onBack = onBack,
        onPlay = { vm.playAlbum(albumId, it) },
    )
}

/** A local artist's tracks (filtered from the in-memory scan). */
@Composable
fun LocalArtistScreen(artistId: String, onBack: () -> Unit, vm: LocalLibraryViewModel = hiltViewModel()) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (songs.isEmpty()) vm.refresh() }
    val tracks = remember(songs, artistId) {
        songs.filter { track -> track.artists.any { it.source?.id == "artist:$artistId" } }
    }
    LocalDetail(
        kind = "ARTIST",
        title = tracks.firstOrNull()?.artists?.firstOrNull()?.name ?: "Artist",
        subtitle = "${tracks.size} ${if (tracks.size == 1) "song" else "songs"}",
        artworkUrl = tracks.firstOrNull()?.artwork.coverUrl(),
        circle = true,
        tracks = tracks,
        numbered = false,
        onBack = onBack,
        onPlay = { vm.playArtist(artistId, it) },
    )
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
) {
    val c = RizxTheme.colors
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RizxIconButton(RizxIcons.Back, "Back", onBack, tint = c.text)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CoverArt(tintFor(title), initial = title.take(1), Modifier.size(84.dp), initialSize = 30, circle = circle, imageUrl = artworkUrl)
            Column(Modifier.weight(1f)) {
                Text(kind, style = mr(11, FontWeight.Bold, 0.2f), color = c.accent)
                Text(title, style = sg(22, FontWeight.Bold, -0.02f), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                subtitle?.let { Text(it, style = mr(13, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        Text("TRACKS", style = code(11, FontWeight.Bold), color = c.muted, modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 2.dp))
        LazyColumn {
            itemsIndexed(tracks, key = { _, t -> "locd-${t.source.id}" }) { index, track ->
                Box(Modifier.staggeredReveal(index.coerceAtMost(12))) {
                    LocalDetailRow(if (numbered) track.trackNumber ?: (index + 1) else index + 1, showNumber = numbered, track = track) { onPlay(index) }
                }
            }
            item { Spacer(Modifier.height(LocalBottomInset.current + 16.dp)) }
        }
    }
}

@Composable
private fun LocalDetailRow(position: Int, showNumber: Boolean, track: Track, onPlay: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay).padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showNumber) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                Text("$position", style = mr(13, FontWeight.Medium), color = c.muted)
            }
        } else {
            CoverArt(tintFor(track.source.id), initial = track.title.take(1), Modifier.size(44.dp), imageUrl = track.artwork.coverUrl())
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artists.joinToString { it.name }.ifEmpty { "Unknown artist" }, style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Text(formatDuration(track.durationMs), style = mr(12, FontWeight.Medium), color = c.muted)
    }
}

private fun tintFor(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % 7
