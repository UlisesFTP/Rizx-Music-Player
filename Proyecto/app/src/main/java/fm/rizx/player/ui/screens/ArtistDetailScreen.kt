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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.core.formatDuration
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.Artist
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DotMatrixSpinner
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.detail.ArtistDetailViewModel
import fm.rizx.player.ui.detail.ArtistUiState
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.cornerBrackets
import fm.rizx.player.ui.theme.hatch
import fm.rizx.player.ui.theme.heroBrush
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal

@Composable
fun ArtistDetailScreen(
    onBack: () -> Unit,
    onOpenAlbum: (ProviderRef) -> Unit,
    vm: ArtistDetailViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            ArtistUiState.Loading -> ArtistSpinner()
            ArtistUiState.Offline -> ArtistMessage("You're offline. Connect and try again.", vm::load)
            is ArtistUiState.Error -> ArtistMessage(s.message, vm::load)
            is ArtistUiState.Content -> ArtistContent(s.artist, onOpenAlbum, vm::play)
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            RizxIconButton(
                RizxIcons.Back, "Back", onBack,
                background = if (c.isDark) Color(0xFF0A0A0B).copy(alpha = 0.5f) else Color(0xFFF3F0E9).copy(alpha = 0.58f),
                border = if (c.isDark) Color.White.copy(alpha = 0.14f) else Color(0xFF221F1A).copy(alpha = 0.18f),
                tint = c.text,
            )
        }
    }
}

@Composable
private fun ArtistContent(artist: Artist, onOpenAlbum: (ProviderRef) -> Unit, onPlay: (Int) -> Unit) {
    val c = RizxTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(300.dp).clipToBounds().background(heroBrush(c)).hatch(c.hatch)) {
            val cover = artist.artwork.coverUrl()
            if (cover != null) {
                coil.compose.AsyncImage(
                    model = cover, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Text(artist.name.take(1).uppercase(), style = sg(150, FontWeight.Bold), color = c.heroLetter, modifier = Modifier.align(Alignment.Center))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.0f to Color.Transparent, 0.6f to c.bg.copy(alpha = 0.55f), 1.0f to c.bg)))
            // HUD corner-bracket frame over the hero (industrial spec-sheet chrome).
            Box(Modifier.matchParentSize().cornerBrackets(c.hardLine, len = 12.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 22.dp, end = 22.dp, bottom = 16.dp)) {
                Text("ARTIST", style = mr(11, FontWeight.Bold, 0.2f), color = c.accent)
                Text(artist.name, style = sg(32, FontWeight.Bold, -0.02f), color = c.heroText, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                artist.followers?.let {
                    Text("${formatFollowers(it)} followers", style = mr(13, FontWeight.Medium), color = c.heroSub, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        if (artist.topTracks.isNotEmpty()) {
            Text("TOP TRACKS", style = code(11, FontWeight.Bold), color = c.muted, modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 2.dp))
            artist.topTracks.forEachIndexed { index, track -> Box(Modifier.staggeredReveal(index)) { ArtistTrackRow(index + 1, track, onPlay) } }
        }

        if (artist.albums.isNotEmpty()) {
            Text("ALBUMS", style = code(11, FontWeight.Bold), color = c.muted, modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 8.dp))
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(artist.albums) { index, album -> Box(Modifier.staggeredReveal(index)) { AlbumCard(album, onOpenAlbum) } }
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun ArtistTrackRow(position: Int, track: Track, onPlay: (Int) -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover) { onPlay(position - 1) }.padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Text("$position", style = mr(13, FontWeight.Medium), color = c.muted)
        }
        CoverArt(tintFor(track.source.id), initial = null, Modifier.size(46.dp), imageUrl = track.artwork.coverUrl())
        Text(track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(formatDuration(track.durationMs), style = mr(12, FontWeight.Medium), color = c.muted)
    }
}

@Composable
private fun AlbumCard(album: AlbumRef, onOpenAlbum: (ProviderRef) -> Unit) {
    val c = RizxTheme.colors
    Column(
        Modifier.width(140.dp).clickableScale(scale = 0.97f) { onOpenAlbum(album.source) },
    ) {
        CoverArt(tintFor(album.source.id), initial = album.title.take(1), Modifier.size(140.dp), initialSize = 34, imageUrl = album.artwork.coverUrl())
        Text(album.title, style = mr(13, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun formatFollowers(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

private fun tintFor(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % 7

@Composable
private fun ArtistSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DotMatrixSpinner(color = RizxTheme.colors.accent, diameter = 34.dp)
    }
}

@Composable
private fun ArtistMessage(text: String, onRetry: () -> Unit) {
    val c = RizxTheme.colors
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = mr(14, FontWeight.Medium), color = c.muted, textAlign = TextAlign.Center)
            Text(
                "Retry", style = sg(14, FontWeight.Bold), color = c.onFill,
                modifier = Modifier.padding(top = 16.dp).background(c.fill).clickableScale(scale = 0.94f, onClick = onRetry).padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}
