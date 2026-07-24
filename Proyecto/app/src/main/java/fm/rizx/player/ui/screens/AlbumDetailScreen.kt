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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.core.formatDuration
import fm.rizx.player.domain.model.Album
import fm.rizx.player.domain.model.DownloadState
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DotMatrixSpinner
import fm.rizx.player.ui.components.DownloadAllButton
import fm.rizx.player.ui.components.DownloadButton
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.detail.AlbumDetailViewModel
import fm.rizx.player.ui.detail.AlbumUiState
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.cornerBrackets
import fm.rizx.player.ui.theme.hatch
import fm.rizx.player.ui.theme.heroBrush
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal

@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit,
    onOpenArtist: (ProviderRef) -> Unit,
    vm: AlbumDetailViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    val downloadStates by vm.downloadStates.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            AlbumUiState.Loading -> CenteredSpinner()
            AlbumUiState.Offline -> DetailMessage(stringResource(R.string.detail_offline_message), onRetry = vm::load)
            is AlbumUiState.Error -> DetailMessage(s.message, onRetry = vm::load)
            is AlbumUiState.Content -> AlbumContent(s.album, onBack, onOpenArtist, vm, downloadStates)
        }
        // Back button floats over every state so the user is never stuck.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            RizxIconButton(
                RizxIcons.Back, stringResource(R.string.detail_back), onBack,
                background = if (c.isDark) Color(0xFF0A0A0B).copy(alpha = 0.5f) else Color(0xFFF3F0E9).copy(alpha = 0.58f),
                border = if (c.isDark) Color.White.copy(alpha = 0.14f) else Color(0xFF221F1A).copy(alpha = 0.18f),
                tint = c.text,
            )
        }
    }
}

@Composable
private fun AlbumContent(
    album: Album,
    onBack: () -> Unit,
    onOpenArtist: (ProviderRef) -> Unit,
    vm: AlbumDetailViewModel,
    downloadStates: Map<String, DownloadState>,
) {
    val c = RizxTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(300.dp).clipToBounds().background(heroBrush(c)).hatch(c.hatch)) {
            val cover = album.artwork.coverUrl()
            if (cover != null) {
                coil.compose.AsyncImage(
                    model = cover, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Text(album.title.take(1).uppercase(), style = sg(150, FontWeight.Bold), color = c.heroLetter, modifier = Modifier.align(Alignment.Center))
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.0f to Color.Transparent, 0.6f to c.bg.copy(alpha = 0.55f), 1.0f to c.bg),
                ),
            )
            // HUD corner-bracket frame over the hero (industrial spec-sheet chrome).
            Box(Modifier.matchParentSize().cornerBrackets(c.hardLine, len = 12.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 22.dp, end = 22.dp, bottom = 16.dp)) {
                Text(stringResource(R.string.detail_album_eyebrow), style = mr(11, FontWeight.Bold, 0.2f), color = c.accent)
                Text(album.title, style = sg(30, FontWeight.Bold, -0.02f), color = c.heroText, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                Text(subtitle(album), style = mr(13, FontWeight.Medium), color = c.heroSub, modifier = Modifier.padding(top = 6.dp))
            }
        }

        album.artists.firstOrNull()?.let { artist ->
            Text(
                stringResource(R.string.detail_more_by, artist.name),
                style = mr(13, FontWeight.SemiBold), color = c.accent,
                modifier = Modifier.clickableScale(scale = 0.98f) { onOpenArtist(artist.source) }.padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.detail_tracks_heading), style = code(11, FontWeight.Bold), color = c.muted, modifier = Modifier.weight(1f))
            DownloadAllButton(album.tracks, downloadStates, onDownloadAll = vm::downloadAll)
        }
        album.tracks.forEachIndexed { index, track ->
            Box(Modifier.staggeredReveal(index)) {
                TrackRow(index + 1, track, vm::play) {
                    DownloadButton(
                        state = downloadStates[track.source.identityKey],
                        onDownload = { vm.downloadTrack(track) },
                        onCancel = { vm.cancelDownload(track.source.identityKey) },
                    )
                }
            }
        }
        Spacer(Modifier.height(LocalBottomInset.current + 16.dp))
    }
}

@Composable
private fun TrackRow(position: Int, track: Track, onPlay: (Int) -> Unit, trailing: @Composable () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover) { onPlay(position - 1) }.padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Text("$position", style = mr(13, FontWeight.Medium), color = c.muted)
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artists.joinToString { it.name }.ifEmpty { stringResource(R.string.unknown_artist) }, style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Text(formatDuration(track.durationMs), style = mr(12, FontWeight.Medium), color = c.muted)
        trailing()
    }
}

@Composable
private fun subtitle(album: Album): String {
    val count = album.totalTracks ?: album.tracks.size
    val trackWord = if (count == 1) stringResource(R.string.detail_track_word_one) else stringResource(R.string.detail_track_word_other)
    val minutesUnit = stringResource(R.string.detail_minutes_unit)
    return buildList {
        album.artists.firstOrNull()?.name?.let { add(it) }
        album.year?.let { add(it.toString()) }
        add("$count $trackWord")
        album.durationMs?.let { add("${it / 60000} $minutesUnit") }
    }.joinToString(" · ")
}




@Composable
private fun CenteredSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DotMatrixSpinner(color = RizxTheme.colors.accent, diameter = 34.dp)
    }
}

@Composable
private fun DetailMessage(text: String, onRetry: () -> Unit) {
    val c = RizxTheme.colors
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = mr(14, FontWeight.Medium), color = c.muted, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.action_retry), style = sg(14, FontWeight.Bold), color = c.onFill,
                modifier = Modifier.padding(top = 16.dp).background(c.fill).clickableScale(scale = 0.94f, onClick = onRetry).padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}
