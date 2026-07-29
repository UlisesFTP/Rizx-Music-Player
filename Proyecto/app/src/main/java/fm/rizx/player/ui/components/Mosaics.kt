package fm.rizx.player.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.cornerBrackets
import fm.rizx.player.ui.theme.dot
import fm.rizx.player.ui.theme.dotGrid
import fm.rizx.player.ui.theme.heroBrush
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.paperElevation
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal
import kotlin.math.roundToInt

/**
 * The Home's **mosaics**: widget-shaped tiles for the mixes Rizx builds itself and for the playlists
 * worth more than a carousel cell. Three styles, all wearing the same [InkFrame] as the rest of the Home:
 *
 * 1. [PickMosaic] — the band across the top: brackets, split text/art, and a numbered rail whose lit
 *    ticks are the mix's own meter.
 * 2. [MixMosaic] + [mosaicWall] — the wall itself, alternating one full-width tile with a pair of
 *    squares, so tiles genuinely occupy different column counts while staying on the Home's 2-up grid.
 * 3. [DiscoverMosaic] — the poster: one recommendation, centred, with the song that earned it.
 *
 * Everything here is presentation only. Titles, captions and labels arrive already localized, and every
 * tile is described by a [MosaicTile], so the wall knows nothing about what a mix or a playlist is.
 */

/** The **black margin**: the ink frame every Home card and mosaic wears. */
val InkFrame = 2.dp

/** Tall enough for four lines of text beside the art, short enough to leave the wall on screen. */
private val PICK_HEIGHT = 188.dp
private val PICK_ART = 150.dp
private val RAIL_WIDTH = 16.dp
private const val RAIL_TICKS = 9

/** A full-width tile ends up almost exactly as tall as a square one is wide — the wall stays even. */
private const val WIDE_ASPECT = 1.9f
private val DISCOVER_HEIGHT = 256.dp

/**
 * One tile on the wall, already localized and already knowing what to do when tapped.
 *
 * [weight] is the meter: `null` draws none (a playlist has no statistics behind it), a value draws how
 * much evidence backs the mix. [covers] feeds the collage — four make a quad, fewer make one big cover.
 */
data class MosaicTile(
    val key: String,
    val label: String,
    val title: String,
    val caption: String? = null,
    val covers: List<String?> = emptyList(),
    val tintKey: String = "",
    val weight: Float? = null,
    val onClick: () -> Unit = {},
)

/**
 * **Style 1** — the pick band. Eyebrow, big title, the statistics line, a red play block, the artwork
 * flush to the right edge, HUD brackets floating outside the frame and a numbered rail beside it.
 *
 * The rail is not decoration: its lit ticks are [weight], read bottom-up, so the strength of the pick is
 * legible without a number.
 */
@Composable
fun PickMosaic(
    eyebrow: String,
    title: String,
    subtitle: String,
    caption: String,
    playLabel: String,
    coverUrl: String?,
    tintKey: String,
    weight: Float,
    modifier: Modifier = Modifier,
    index: Int = 1,
    onClick: () -> Unit = {},
) {
    val c = RizxTheme.colors
    Row(modifier.height(PICK_HEIGHT), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Brackets sit in this padding, outside the card's own border — the framing of ref #1.
        Box(Modifier.weight(1f).fillMaxHeight().cornerBrackets(c.hardLine, len = 13.dp).padding(6.dp)) {
            Row(
                Modifier
                    .fillMaxSize()
                    .paperElevation()
                    .background(c.elev)
                    .border(InkFrame, c.hardLine, RectangleShape)
                    .clickableScale(scale = 0.985f, onClick = onClick),
            ) {
                Column(Modifier.weight(1f).padding(start = 13.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)) {
                    Eyebrow(eyebrow, color = c.redAccent)
                    Text(
                        title,
                        style = sg(21, FontWeight.Bold, -0.02f),
                        color = c.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, style = mr(11, FontWeight.Medium), color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        caption,
                        style = mr(10, FontWeight.Medium),
                        color = c.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 9.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PlayBlock(size = 36.dp, filled = true)
                        Text(playLabel.uppercase(), style = code(11, FontWeight.Bold), color = c.text, maxLines = 1)
                    }
                }
                CoverArt(
                    tintFor(tintKey), initial = title.take(1),
                    Modifier.width(PICK_ART).fillMaxHeight(),
                    initialSize = 44, imageUrl = coverUrl,
                )
            }
        }
        MeterRail(index, weight)
    }
}

/**
 * **Style 2** — one wall tile: collage artwork under a scrim, the kind label plated top-left, the title
 * over the art, and a play block beside the meter at the foot (ref #2).
 *
 * [wide] switches between the full-width and the single-column shape; both are the same composition, so
 * a wall of them reads as one system.
 */
@Composable
fun MixMosaic(tile: MosaicTile, wide: Boolean, modifier: Modifier = Modifier) {
    val c = RizxTheme.colors
    Box(
        modifier
            .aspectRatio(if (wide) WIDE_ASPECT else 1f)
            .paperElevation()
            .border(InkFrame, c.hardLine, RectangleShape)
            .clickableScale(scale = 0.98f, onClick = tile.onClick),
    ) {
        Collage(tile.covers, tile.tintKey, tile.title, Modifier.matchParentSize())
        // The art carries the text, so it needs a floor to sit on whatever the cover happens to be.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.80f))),
        )
        Text(
            tile.label.uppercase(),
            style = code(9, FontWeight.Bold),
            color = c.onRed,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(9.dp)
                .background(c.redAccent)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 11.dp, end = 11.dp, bottom = 10.dp),
        ) {
            Text(
                tile.title,
                style = sg(if (wide) 19 else 15, FontWeight.Bold, -0.02f),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (tile.caption != null) {
                Text(
                    tile.caption,
                    style = mr(10, FontWeight.Medium),
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                Modifier.padding(top = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                PlayBlock(size = if (wide) 32.dp else 28.dp, filled = false)
                if (tile.weight != null) Meter(tile.weight, Modifier.weight(1f))
            }
        }
    }
}

/**
 * **Style 3** — the poster: one recommendation, its artwork centred, and the reason it is here.
 *
 * The reason line is the point. A recommendation with no "because" behind it is indistinguishable from an
 * advert, so this card is only drawn when the seed that earned the song is known.
 */
@Composable
fun DiscoverMosaic(
    eyebrow: String,
    title: String,
    artist: String,
    reason: String,
    coverUrl: String?,
    tintKey: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val c = RizxTheme.colors
    Column(
        modifier
            .height(DISCOVER_HEIGHT)
            .paperElevation()
            .background(heroBrush(c))
            .dotGrid(c.dot, spacing = 15.dp)
            .border(InkFrame, c.hardLine, RectangleShape)
            .clickableScale(scale = 0.985f, onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Eyebrow(eyebrow, color = c.redAccent)
                Text(
                    title,
                    style = sg(20, FontWeight.Bold, -0.02f),
                    color = c.heroText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(artist, style = mr(11, FontWeight.Medium), color = c.heroSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayBlock(size = 38.dp, filled = true)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
            CoverArt(
                tintFor(tintKey), initial = title.take(1),
                Modifier.fillMaxHeight().aspectRatio(1f).paperElevation().border(InkFrame, c.hardLine, RectangleShape),
                initialSize = 42, imageUrl = coverUrl,
            )
        }
        Text(
            reason,
            style = mr(10, FontWeight.Medium),
            color = c.heroSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * [DiscoverMosaic]'s reserved slot, for while the recommendation that fills it is still being fetched.
 *
 * Lives here so it shares the card's height **by construction** — a hand-tuned dp in the screen would
 * drift the moment the card's padding changed, and the whole point of the slot is that the poster lands
 * without moving a pixel of what is below it.
 */
@Composable
fun DiscoverMosaicSkeleton(modifier: Modifier = Modifier) {
    val c = RizxTheme.colors
    val alpha by rememberInfiniteTransition(label = "discoverSkeleton").animateFloat(
        initialValue = 0.30f,
        targetValue = 0.70f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "discoverAlpha",
    )
    Column(
        modifier
            .height(DISCOVER_HEIGHT)
            .background(c.elev)
            .dotGrid(c.dot, spacing = 15.dp)
            .border(InkFrame, c.hardLine, RectangleShape)
            .padding(14.dp),
    ) {
        Bar(0.42f, 11.dp, alpha)
        Spacer(Modifier.height(7.dp))
        Bar(0.62f, 17.dp, alpha)
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .background(c.line2.copy(alpha = alpha * 0.5f))
                    .border(InkFrame, c.hardLine, RectangleShape),
            )
        }
        Bar(0.34f, 9.dp, alpha)
    }
}

@Composable
private fun Bar(widthFraction: Float, height: Dp, alpha: Float) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .background(RizxTheme.colors.line2.copy(alpha = alpha)),
    )
}

/**
 * The wall: one full-width tile, then a pair of single-column ones, repeating.
 *
 * A fixed rhythm rather than a masonry algorithm, because the point is that tiles occupy *different*
 * column counts without the wall losing its grid — a full-width tile is almost exactly as tall as a
 * square one, so consecutive rows still line up.
 */
fun LazyListScope.mosaicWall(tiles: List<MosaicTile>) {
    if (tiles.isEmpty()) return
    itemsIndexed(tiles.rhythm(), key = { _, row -> "mosaic-${row.tiles.first().key}" }) { index, row ->
        Row(
            Modifier
                .fillMaxWidth()
                .staggeredReveal(index)
                .padding(start = 22.dp, end = 22.dp, top = 7.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (row.wide) {
                MixMosaic(row.tiles.first(), wide = true, Modifier.fillMaxWidth())
            } else {
                row.tiles.forEach { tile -> MixMosaic(tile, wide = false, Modifier.weight(1f)) }
                // Keep a lone trailing tile at half width instead of letting it stretch across the row.
                if (row.tiles.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private class MosaicRow(val tiles: List<MosaicTile>, val wide: Boolean)

/** Rows of one, then two, then one… — see [mosaicWall]. */
private fun List<MosaicTile>.rhythm(): List<MosaicRow> {
    val rows = mutableListOf<MosaicRow>()
    var i = 0
    while (i < size) {
        rows += MosaicRow(listOf(this[i]), wide = true)
        i++
        if (i < size) {
            rows += MosaicRow(subList(i, minOf(i + 2, size)).toList(), wide = false)
            i += 2
        }
    }
    return rows
}

// ---- Parts ------------------------------------------------------------------------------------

/**
 * Up to four covers as one image: a 2×2 quad when there are four — the widget look — and a single cover
 * otherwise. Never a 2-up split, which reads as a layout accident rather than a collage.
 */
@Composable
private fun Collage(covers: List<String?>, tintKey: String, title: String, modifier: Modifier) {
    val urls = covers.filterNotNull().distinct()
    if (urls.size >= 4) {
        Column(modifier) {
            repeat(2) { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    repeat(2) { col ->
                        val i = row * 2 + col
                        CoverArt(
                            tintFor("$tintKey-$i"), initial = null,
                            Modifier.weight(1f).fillMaxHeight(), imageUrl = urls[i],
                        )
                    }
                }
            }
        }
    } else {
        CoverArt(tintFor(tintKey), initial = title.take(1), modifier, initialSize = 46, imageUrl = urls.firstOrNull())
    }
}

/** The play affordance: a red block on paper, or an outlined plate over artwork. */
@Composable
private fun PlayBlock(size: Dp, filled: Boolean) {
    val c = RizxTheme.colors
    Box(
        Modifier
            .size(size)
            .background(if (filled) c.redAccent else Color.Black.copy(alpha = 0.42f))
            .border(1.dp, if (filled) Color.Transparent else Color.White.copy(alpha = 0.85f), RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            RizxIcons.Play,
            contentDescription = null,
            tint = if (filled) c.onRed else Color.White,
            modifier = Modifier.size(size * 0.44f),
        )
    }
}

/** The evidence meter: a red bar over a dim track, `0f..1f`. */
@Composable
private fun Meter(weight: Float, modifier: Modifier = Modifier) {
    val c = RizxTheme.colors
    Box(modifier.height(3.dp).background(Color.White.copy(alpha = 0.30f))) {
        Box(
            Modifier
                .fillMaxWidth(weight.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(c.redAccent),
        )
    }
}

/**
 * The numbered rail beside [PickMosaic]: a dot-matrix index over a tick scale whose bottom ticks light up
 * red in proportion to [weight] — the same meter as [Meter], stood on its end.
 */
@Composable
private fun MeterRail(index: Int, weight: Float) {
    val c = RizxTheme.colors
    val lit = (RAIL_TICKS * weight.coerceIn(0f, 1f)).roundToInt()
    Column(
        Modifier.width(RAIL_WIDTH).fillMaxHeight().padding(top = 6.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (index < 10) "0$index" else index.toString(),
            style = dot(13, FontWeight.Bold),
            color = c.text2,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        repeat(RAIL_TICKS) { i ->
            val fromBottom = RAIL_TICKS - 1 - i
            Box(
                Modifier
                    .padding(vertical = 2.dp)
                    .size(if (fromBottom < lit) 4.dp else 3.dp)
                    .background(if (fromBottom < lit) c.redAccent else c.dot),
            )
        }
    }
}
