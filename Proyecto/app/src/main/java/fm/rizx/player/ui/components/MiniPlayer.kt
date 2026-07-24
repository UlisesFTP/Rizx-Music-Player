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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.core.formatDuration
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.RizxWidth
import fm.rizx.player.ui.theme.brutalShadow
import fm.rizx.player.ui.theme.rizxWidth
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
        // Live "elapsed / duration" readout (mono numerals) — **dropped on narrow screens**. The row's
        // fixed parts (artwork, like, play) already eat ~190dp, so on a compact width this readout left
        // the title barely 60dp and the marquee just scrolled fragments of a word past. The scrubber
        // underneath already shows position, so this is the cheapest thing in the row to give up.
        if (rizxWidth() != RizxWidth.Compact) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 8.dp, end = 2.dp),
            ) {
                Text(formatDuration(positionMs), style = mr(10, FontWeight.SemiBold), color = c.text2, maxLines = 1)
                Text(formatDuration(durationMs), style = mr(10, FontWeight.Medium), color = c.muted, maxLines = 1)
            }
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
            val dot = 9.dp.toPx()
            val lineH = 3.dp.toPx()
            // Sit the line a little above the bottom so the marker straddles it (the line runs through
            // the marker's vertical middle) while the marker's lower half stays fully visible.
            val lineCenterY = size.height - dot / 2f - 2.dp.toPx()
            val lineTop = lineCenterY - lineH / 2f
            drawRect(color = c.hardLine, topLeft = Offset(0f, lineTop), size = Size(size.width, lineH)) // faint track
            drawRect(color = c.redAccent, topLeft = Offset(0f, lineTop), size = Size(size.width * shown, lineH)) // fill

            // Square position marker centered on the line, clamped so it stays fully visible at either end.
            val cx = (size.width * shown).coerceIn(dot / 2f, size.width - dot / 2f)
            val corner = Offset(cx - dot / 2f, lineCenterY - dot / 2f)
            drawRect(color = c.redAccent, topLeft = corner, size = Size(dot, dot))
            drawRect(color = c.hardLine, topLeft = corner, size = Size(dot, dot), style = Stroke(width = 1.dp.toPx()))
        }
    }
}
