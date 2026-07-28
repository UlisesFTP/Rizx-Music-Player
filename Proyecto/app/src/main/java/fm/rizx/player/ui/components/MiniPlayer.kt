package fm.rizx.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.core.formatDuration
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.brutalShadow
import fm.rizx.player.ui.theme.hatch
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.placeholderBrush

/** Floating "now playing" bar. Tapping the body opens the full Now Playing screen. */
@Composable
fun MiniPlayer(
    title: String,
    artist: String,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onLike: () -> Unit,
    modifier: Modifier = Modifier,
    artworkUrl: String? = null,
    progress: Float = 0f,
    loading: Boolean = false,
    liked: Boolean = false,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    onSeek: (Float) -> Unit = {},
) {
    val c = RizxTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .brutalShadow(c.shadowHard, offset = 4.dp)
            .clip(RectangleShape)
            .background(c.navBg)
            .border(1.5.dp, c.hardLine, RectangleShape),
    ) {
      Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RectangleShape)
                .background(placeholderBrush(c))
                .hatch(c.hatch),
        ) {
            if (artworkUrl != null) {
                coil.compose.AsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.matchParentSize().clip(RectangleShape),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            // Marquee: a long title scrolls leftward continuously so it can be read in the tight bar.
            Text(
                title,
                style = mr(13, FontWeight.SemiBold),
                color = c.text,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )
            Text(artist, style = mr(11, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Live "elapsed over duration" readout (mono numerals), on every width.
        //
        // It used to be dropped below 400dp to give the title more room, but the owner wants the time
        // back — knowing how far into a song you are is worth more than the extra characters of title,
        // and the title marquees anyway. Stacked rather than side by side, and tight against the like
        // button, so it costs ~34dp instead of the ~70dp an inline "0:32 / 2:44" would.
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(start = 6.dp),
        ) {
            Text(formatDuration(positionMs), style = mr(10, FontWeight.SemiBold), color = c.text2, maxLines = 1)
            Text(formatDuration(durationMs), style = mr(10, FontWeight.Medium), color = c.muted, maxLines = 1)
        }
        RizxIconButton(
            icon = if (liked) RizxIcons.Favorite else RizxIcons.FavoriteBorder,
            contentDescription = if (liked) stringResource(R.string.player_remove_from_liked) else stringResource(R.string.player_like),
            onClick = onLike,
            size = 44.dp,
            iconSize = 21.dp,
            tint = if (liked) c.redAccent else c.muted,
        )
        Box(
            Modifier
                .size(40.dp)
                .clip(RectangleShape)
                .background(c.fill)
                .clickableScale(scale = 0.92f, enabled = !loading, onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                DotMatrixSpinner(color = c.onFill, diameter = 22.dp)
            } else {
                Icon(
                    if (isPlaying) RizxIcons.Pause else RizxIcons.Play,
                    contentDescription = if (isPlaying) stringResource(R.string.action_pause) else stringResource(R.string.action_play),
                    tint = c.onFill,
                    modifier = Modifier.size(23.dp),
                )
            }
        }
      }
        // Interactive scrubber pinned to the bottom edge — tap or drag to seek (rewind/fast-forward).
        MiniSeekBar(
            progress = progress,
            onSeek = onSeek,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }
}

/**
 * The mini-player's bottom scrubber: a slim track + red fill along the whole bottom edge, tappable and
 * horizontally draggable to seek. A small square red marker sits on the line at the current position so
 * it's easy to see where the song is and to grab it while scrubbing. While dragging, the fill + marker
 * follow the finger immediately (local [drag] override) so it feels responsive before the real position
 * round-trips back through the player.
 */
@Composable
private fun MiniSeekBar(progress: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    val c = RizxTheme.colors
    var drag by remember { mutableStateOf<Float?>(null) }
    Box(
        modifier
            .height(18.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ ->
                    val f = (change.position.x / size.width).coerceIn(0f, 1f)
                    drag = f
                    onSeek(f)
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shown = (drag ?: progress).coerceIn(0f, 1f)
        Canvas(Modifier.fillMaxWidth().height(13.dp)) {
            drawSeekLine(
                progress = shown,
                trackColor = c.hardLine,
                fillColor = c.redAccent,
                markerBorderColor = c.hardLine,
                // Pushed below centre so the marker straddles the line while its lower half stays
                // fully visible against the bar's bottom edge.
                lineCenterY = size.height - SEEK_MARKER.toPx() / 2f - 2.dp.toPx(),
            )
        }
    }
}
