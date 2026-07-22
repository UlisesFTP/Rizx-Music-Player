package fm.rizx.player.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.coverTint
import fm.rizx.player.ui.theme.hatch
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.util.rememberRizxHaptics

/**
 * Tap handler with the design's tactile "scale down on press" feedback and no ripple
 * (`style-active="transform:scale(.9)"`).
 *
 * Pass [pressColor] (e.g. `c.rowHover`) to also fade a subtle background fill in/out while
 * pressed — the on-theme replacement for a ripple on full-width rows. [pressShape] clips it.
 */
@Composable
fun Modifier.clickableScale(
    scale: Float = 0.9f,
    enabled: Boolean = true,
    pressColor: Color? = null,
    pressShape: Shape = RectangleShape,
    haptic: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && enabled) scale else 1f, label = "pressScale")
    val fill by animateFloatAsState(if (pressed && enabled) 1f else 0f, label = "pressFill")
    val haptics = rememberRizxHaptics()
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .then(
            if (pressColor != null)
                Modifier.background(pressColor.copy(alpha = pressColor.alpha * fill), pressShape)
            else Modifier,
        )
        .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
            if (haptic) haptics.tick()
            onClick()
        }
}

/** Square (or circular) icon button with a fixed touch target. */
@Composable
fun RizxIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    tint: Color = RizxTheme.colors.text,
    background: Color = Color.Transparent,
    border: Color = Color.Transparent,
    shape: Shape = RectangleShape,
) {
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(background)
            .then(if (border != Color.Transparent) Modifier.border(1.dp, border, shape) else Modifier)
            .clickableScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/**
 * Stable per-entity tint index for [CoverArt]'s gradient fallback — derive it from a stable key (a
 * `ProviderRef.id`, a playlist id) so the same entity always gets the same hue.
 */
fun tintFor(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % 7

/**
 * A **labelled** action: icon plus a short word, sharp-cornered.
 *
 * Exists because an unlabelled glyph in a crowded header makes the user guess — the same reasoning already
 * written into [DownloadAllButton]. Prefer this over a bare [RizxIconButton] whenever an action isn't
 * self-evident from its icon alone (creating, importing, exporting…).
 *
 * [prominent] fills it as the primary call to action; otherwise it reads as an outlined secondary action.
 * The 10.dp vertical padding around a 15.dp glyph keeps the touch target comfortable without the 48.dp
 * square an icon button needs.
 */
@Composable
fun RizxActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    prominent: Boolean = false,
) {
    val c = RizxTheme.colors
    Row(
        modifier
            .clip(RectangleShape)
            .background(if (prominent) c.fill else c.elev)
            .then(if (prominent) Modifier else Modifier.border(1.dp, c.hardLine, RectangleShape))
            .clickableScale(scale = 0.95f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        val tint = if (prominent) c.onFill else c.text
        Icon(icon, contentDescription ?: label, tint = tint, modifier = Modifier.size(15.dp))
        Text(label.uppercase(), style = code(11, FontWeight.Bold), color = tint, maxLines = 1)
    }
}

/** Filter pill (sharp-cornered) — active fills with the accent, inactive is an outlined chip. */
@Composable
fun RizxChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = RizxTheme.colors
    Box(
        modifier
            .clip(RectangleShape)
            .background(if (active) c.redAccent else c.elev)
            .then(if (active) Modifier else Modifier.border(1.dp, c.hardLine, RectangleShape))
            .clickableScale(scale = 0.95f, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
    ) {
        Text(
            label.uppercase(),
            style = code(12, FontWeight.Bold),
            color = if (active) c.onRed else c.muted,
            maxLines = 1,
        )
    }
}

/**
 * Recurring square/circular cover: per-index tint + diagonal hatch + optional initial & corner label.
 * When [imageUrl] is non-null, the real cover art is loaded (Coil) over the gradient, which stays as the
 * loading/error placeholder.
 */
@Composable
fun CoverArt(
    tintIndex: Int,
    initial: String?,
    modifier: Modifier = Modifier,
    initialSize: Int = 52,
    label: String? = null,
    circle: Boolean = false,
    imageUrl: String? = null,
) {
    val c = RizxTheme.colors
    val shape = if (circle) CircleShape else RectangleShape
    Box(
        modifier
            .clip(shape)
            .background(coverTint(tintIndex, c.isDark))
            .hatch(c.hatch)
            .border(1.dp, c.line, shape),
    ) {
        if (imageUrl != null) {
            coil.compose.AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(shape),
            )
        }
        if (initial != null && imageUrl == null) {
            Text(
                initial,
                style = sg(initialSize, FontWeight.Bold),
                color = c.coverInitial,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (label != null) {
            Text(
                label.uppercase(),
                color = c.artLabel,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 9.sp,
                    letterSpacing = 0.1.em,
                ),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 9.dp, bottom = 8.dp),
            )
        }
    }
}

/** A section header row ("Made for you" + "See all"). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = RizxTheme.colors
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Solid RED section marker (ref #2 UESC squares) before every section title.
        Row(Modifier.padding(end = 10.dp)) {
            Box(Modifier.size(8.dp).background(c.redAccent))
        }
        Text(title, style = sg(19, FontWeight.Bold, -0.01f), color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) {
            Text(
                action.uppercase(),
                style = code(11, FontWeight.Bold),
                color = c.text2,
                modifier = Modifier.clickableScale(scale = 0.94f, onClick = onAction),
            )
        }
    }
}

/** The animated equalizer "now playing" indicator (bars scaling from the bottom). */
@Composable
fun Equalizer(
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 4,
    height: Dp = 16.dp,
) {
    val transition = rememberInfiniteTransition(label = "eq")
    val startDelays = listOf(0, 400, 200, 600)
    Row(
        modifier.height(height),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(barCount) { i ->
            val scale by transition.animateFloat(
                initialValue = 0.26f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(450, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(startDelays[i % startDelays.size]),
                ),
                label = "bar$i",
            )
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(color),
            )
        }
    }
}
