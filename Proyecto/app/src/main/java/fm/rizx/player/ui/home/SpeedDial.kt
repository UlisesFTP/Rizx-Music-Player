package fm.rizx.player.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.domain.model.Track
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.InkFrame
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.components.tileUrl
import fm.rizx.player.ui.components.tintFor
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.paperElevation

/** One cell of the speed-dial grid: a song from the listener's own history, or the dice. */
sealed interface SpeedCell {
    data class Song(val track: Track) : SpeedCell
    data object Dice : SpeedCell
}

/**
 * The grid's pages. The dice is always the very last cell, and past one page **only whole pages
 * exist**: a short trailing page would change the pager's height mid-swipe and shove the entire feed
 * below it. So a thin history makes one page exactly as tall as it needs, and a deep one makes full
 * 3×3 pages, trimming the oldest few songs rather than shipping a ragged final page.
 */
fun speedDialPages(tracks: List<Track>, perPage: Int = SPEED_DIAL_PER_PAGE): List<List<SpeedCell>> {
    if (tracks.isEmpty() || perPage <= 0) return emptyList()
    val songs = if (tracks.size + 1 <= perPage) tracks.size else ((tracks.size + 1) / perPage) * perPage - 1
    val cells = tracks.take(songs).map<Track, SpeedCell> { SpeedCell.Song(it) } + SpeedCell.Dice
    return cells.chunked(perPage)
}

const val SPEED_DIAL_PER_PAGE = 9

private const val COLUMNS = 3

/**
 * "Continue listening" as a speed dial: the carousel's twelve covers become a swipeable 3×3 wall of
 * them — denser, thumb-sized, and ending on a dice that plays something of yours at random. The data
 * contract is untouched: only songs this listener actually played, newest first.
 */
@Composable
fun SpeedDial(
    tracks: List<Track>,
    onPlay: (Track) -> Unit,
    onSurprise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = remember(tracks) { speedDialPages(tracks) }
    if (pages.isEmpty()) return
    val pagerState = rememberPagerState { pages.size }
    Column(modifier) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 22.dp),
            pageSpacing = 14.dp,
        ) { pageIndex ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pages[pageIndex].chunked(COLUMNS).forEach { rowCells ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowCells.forEach { cell ->
                            Box(Modifier.weight(1f)) {
                                when (cell) {
                                    is SpeedCell.Song -> SpeedTile(cell.track) { onPlay(cell.track) }
                                    SpeedCell.Dice -> DiceTile(onSurprise)
                                }
                            }
                        }
                        // A short row (single-page mode only) keeps the three-column geometry.
                        repeat(COLUMNS - rowCells.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (pages.size > 1) {
            PageMarks(
                current = pagerState.currentPage,
                count = pages.size,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun SpeedTile(track: Track, onPlay: () -> Unit) {
    val c = RizxTheme.colors
    Column(Modifier.clickableScale(scale = 0.96f, onClick = onPlay)) {
        CoverArt(
            tintFor(track.source.id),
            initial = null,
            Modifier.fillMaxWidth().aspectRatio(1f).paperElevation(),
            imageUrl = track.artwork.tileUrl(),
            borderColor = c.hardLine,
            borderWidth = InkFrame,
        )
        Text(
            track.title,
            style = mr(11, FontWeight.SemiBold),
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** The wildcard: same block as a cover, wearing a die face instead of art. */
@Composable
private fun DiceTile(onClick: () -> Unit) {
    val c = RizxTheme.colors
    Column(Modifier.clickableScale(scale = 0.96f, onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .paperElevation()
                .background(c.elev)
                .border(InkFrame, c.hardLine, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            DiceFace(c.text, Modifier.fillMaxSize(0.4f))
        }
        Text(
            stringResource(R.string.home_surprise),
            style = mr(11, FontWeight.SemiBold),
            color = c.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** A five face — square pips, because everything round here is an accident. */
@Composable
private fun DiceFace(pip: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = size.minDimension * 0.24f
        val span = size.minDimension - side
        listOf(0f to 0f, 1f to 0f, 0.5f to 0.5f, 0f to 1f, 1f to 1f).forEach { (fx, fy) ->
            drawRect(pip, topLeft = Offset(fx * span, fy * span), size = Size(side, side))
        }
    }
}

/** Filled square = the page you're on. Squares, not dots — see [DiceFace]. */
@Composable
private fun PageMarks(current: Int, count: Int, modifier: Modifier = Modifier) {
    val c = RizxTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            Box(Modifier.size(6.dp).background(if (index == current) c.text else c.line2))
        }
    }
}
