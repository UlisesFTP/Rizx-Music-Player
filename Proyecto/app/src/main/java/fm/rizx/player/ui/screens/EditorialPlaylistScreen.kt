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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DotMatrixSpinner
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.detail.EditorialPlaylistUiState
import fm.rizx.player.ui.detail.EditorialPlaylistViewModel
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal

/** Detail for a **pre-made / editorial** playlist (Deezer, off the Home feed): tracklist + tap-to-play. */
@Composable
fun EditorialPlaylistScreen(
    onBack: () -> Unit,
    vm: EditorialPlaylistViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 22.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RizxIconButton(RizxIcons.Back, stringResource(R.string.detail_back), onBack, background = c.elev, border = c.line, iconSize = 20.dp)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.detail_playlist_eyebrow), style = code(11, FontWeight.Bold), color = c.muted)
                    Text(vm.playlistName, style = sg(24, FontWeight.Bold, -0.02f), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(RizxIcons.QueueMusic, null, tint = c.accent, modifier = Modifier.size(24.dp))
            }

            when (val s = state) {
                EditorialPlaylistUiState.Loading -> Centered { DotMatrixSpinner(color = c.accent, diameter = 34.dp) }
                EditorialPlaylistUiState.Offline -> Message(stringResource(R.string.detail_offline_message), vm::load)
                is EditorialPlaylistUiState.Error -> Message(s.message, vm::load)
                is EditorialPlaylistUiState.Content -> {
                    Text(
                        if (s.tracks.size == 1) {
                            stringResource(R.string.detail_track_count_caps_one, s.tracks.size)
                        } else {
                            stringResource(R.string.detail_track_count_caps_other, s.tracks.size)
                        },
                        style = code(11, FontWeight.Bold), color = c.muted,
                        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(s.tracks, key = { i, t -> "${t.source.provider}:${t.source.id}:$i" }) { index, track ->
                            Box(Modifier.staggeredReveal(index)) {
                                PlaylistTrackRow(index + 1, track) { vm.play(index) }
                            }
                        }
                        item { Spacer(Modifier.height(LocalBottomInset.current + 16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTrackRow(position: Int, track: Track, onPlay: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier.fillMaxWidth().clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onPlay).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Text("$position", style = mr(13, FontWeight.Medium), color = c.muted)
        }
        CoverArt(tintFor(track.source.id), initial = null, Modifier.size(46.dp), imageUrl = track.artwork.coverUrl())
        Column(Modifier.weight(1f)) {
            Text(track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artists.joinToString { it.name }.ifEmpty { stringResource(R.string.unknown_artist) }, style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatDuration(track.durationMs), style = mr(12, FontWeight.Medium), color = c.muted)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Message(text: String, onRetry: () -> Unit) {
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

private fun tintFor(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % 7
