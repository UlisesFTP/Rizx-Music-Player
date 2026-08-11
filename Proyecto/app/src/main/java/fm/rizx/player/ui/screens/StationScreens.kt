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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.core.formatDuration
import fm.rizx.player.domain.model.MoodStation
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DotMatrixSpinner
import fm.rizx.player.ui.components.InkFrame
import fm.rizx.player.ui.components.MoodGrid
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.station.MoodsUiState
import fm.rizx.player.ui.station.MoodsViewModel
import fm.rizx.player.ui.station.StationUiState
import fm.rizx.player.ui.station.StationViewModel
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.paperElevation
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal

/** Every mood/genre station, behind Home's "See all". Same mosaic as the preview grid. */
@Composable
fun MoodsScreen(
    onBack: () -> Unit,
    onOpenStation: (providerId: String, station: MoodStation) -> Unit,
    vm: MoodsViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        StationHeader(
            eyebrow = stringResource(R.string.moods_eyebrow),
            title = stringResource(R.string.home_moods_heading),
            artworkUrl = null,
            tintKey = "moods",
            onBack = onBack,
            showArtwork = false,
        )
        when (val s = state) {
            MoodsUiState.Loading -> StationCentered { DotMatrixSpinner(color = c.accent, diameter = 34.dp) }
            MoodsUiState.Offline -> StationMessage(stringResource(R.string.detail_offline_message), vm::load)
            MoodsUiState.Empty -> StationMessage(stringResource(R.string.moods_empty), vm::load)
            is MoodsUiState.Error -> StationMessage(s.message, vm::load)
            is MoodsUiState.Content -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
            ) {
                MoodGrid(
                    stations = s.stations,
                    onOpen = onOpenStation,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    // The screen's own header already names them; a second identical heading would
                    // just repeat itself, and there is no "See all" left to offer.
                    heading = stringResource(R.string.moods_all_heading),
                )
                Spacer(Modifier.height(LocalBottomInset.current + 16.dp))
            }
        }
    }
}

/**
 * One station: what it is playing right now, tap any row to start there.
 *
 * Replaces a chip that played blind — you could not see what was in a station before committing to
 * it, and a failed resolve was indistinguishable from a dead tap.
 */
@Composable
fun StationScreen(
    onBack: () -> Unit,
    vm: StationViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    val queueLabel = stringResource(R.string.home_station_of, vm.stationName)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        StationHeader(
            eyebrow = stringResource(R.string.station_eyebrow),
            title = vm.stationName,
            artworkUrl = vm.artworkUrl,
            tintKey = vm.stationName,
            onBack = onBack,
        )
        when (val s = state) {
            StationUiState.Loading -> StationCentered { DotMatrixSpinner(color = c.accent, diameter = 34.dp) }
            StationUiState.Offline -> StationMessage(stringResource(R.string.detail_offline_message), vm::load)
            StationUiState.Empty -> StationMessage(stringResource(R.string.station_empty), vm::load)
            is StationUiState.Error -> StationMessage(s.message, vm::load)
            is StationUiState.Content -> {
                Text(
                    stringResource(R.string.station_playing_now),
                    style = code(11, FontWeight.Bold), color = c.muted,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 2.dp),
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(s.tracks, key = { i, t -> "s-${t.source.identityKey}-$i" }) { index, track ->
                        Box(Modifier.padding(horizontal = 22.dp).staggeredReveal(index)) {
                            StationTrackRow(index + 1, track) { vm.play(index, queueLabel) }
                        }
                    }
                    item { Spacer(Modifier.height(LocalBottomInset.current + 16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun StationHeader(
    eyebrow: String,
    title: String,
    artworkUrl: String?,
    tintKey: String,
    onBack: () -> Unit,
    showArtwork: Boolean = true,
) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RizxIconButton(RizxIcons.Back, stringResource(R.string.detail_back), onBack, background = c.elev, border = c.line, iconSize = 20.dp)
        Column(Modifier.weight(1f)) {
            Text(eyebrow, style = code(11, FontWeight.Bold), color = c.muted)
            Text(title, style = sg(24, FontWeight.Bold, -0.02f), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (showArtwork) {
            CoverArt(
                stationTint(tintKey), initial = null, Modifier.size(52.dp).paperElevation(),
                imageUrl = artworkUrl, borderColor = c.hardLine, borderWidth = InkFrame,
            )
        }
    }
}

@Composable
private fun StationTrackRow(position: Int, track: Track, onPlay: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Text("$position", style = mr(13, FontWeight.Medium), color = c.muted)
        }
        CoverArt(stationTint(track.source.id), initial = null, Modifier.size(46.dp), imageUrl = track.artwork.coverUrl())
        Column(Modifier.weight(1f)) {
            Text(track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                track.artists.joinToString { it.name }.ifEmpty { stringResource(R.string.unknown_artist) },
                style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(formatDuration(track.durationMs), style = mr(12, FontWeight.Medium), color = c.muted)
    }
}

@Composable
private fun StationCentered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun StationMessage(text: String, onRetry: () -> Unit) {
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

private fun stationTint(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % 7
