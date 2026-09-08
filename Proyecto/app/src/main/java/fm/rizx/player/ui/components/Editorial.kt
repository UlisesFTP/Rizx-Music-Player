package fm.rizx.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fm.rizx.player.R
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.brutalShadow
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.util.LibraryStats

/**
 * The **editorial** vocabulary of the feed design (the web's `feed.css`), in the app's own type and
 * palette: a kicker over a giant display title, surfaces framed by a 2dp ink line and a hard offset
 * shadow, eyebrows that start with a red signal dot, red index tags, and the ink card that carries
 * cream text. The Library, Settings and Equalizer screens are built from these; nothing here knows
 * what a playlist or a setting is.
 *
 * Measurements are the design's phone (≤767px) values, in dp. Colours go through [RizxTheme] so the
 * Ivory theme gets the same inversion the web's dark remap does: "ink" is the theme's primary fill.
 */
object Editorial {
    /** The 2px ink frame every surface, field and tile wears. */
    val Frame: Dp = 2.dp

    /** The 1px rule between rows (`--line-strong`). */
    val Hairline: Dp = 1.dp

    /** The page's side margin at phone width; the tab strip bleeds through it. */
    val PageMargin: Dp = 16.dp

    /** A surface's background: the bright paper at 72 %, so the blueprint grid shows through faintly. */
    val surface: Color
        @Composable @ReadOnlyComposable get() = RizxTheme.colors.elev.copy(alpha = 0.72f)

    /**
     * The hard offset shadow under a surface. Ink at 13 % on paper; on the black theme a black shadow
     * would vanish, so the red accent takes the job — which is what the web's dark remap does too.
     */
    val shadow: Color
        @Composable @ReadOnlyComposable get() = RizxTheme.colors.let {
            if (it.isDark) it.redAccent.copy(alpha = 0.16f) else it.shadowHard.copy(alpha = 0.13f)
        }

    /** The row rule. */
    val rule: Color
        @Composable @ReadOnlyComposable get() = RizxTheme.colors.line2
}

/** Widens a full-width child by [horizontal] on each side, so a scrolling strip reaches the screen edge. */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = (horizontal * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) {
        placeable.place(-horizontal.roundToPx(), 0)
    }
}

/** A rule along the bottom edge — the `border-bottom` of a header or a row. */
fun Modifier.bottomRule(color: Color, thickness: Dp = Editorial.Hairline): Modifier = drawBehind {
    val t = thickness.toPx()
    drawRect(color, topLeft = Offset(0f, size.height - t), size = androidx.compose.ui.geometry.Size(size.width, t))
}

/** A rule along the top edge. */
fun Modifier.topRule(color: Color, thickness: Dp = Editorial.Hairline): Modifier = drawBehind {
    drawRect(color, size = androidx.compose.ui.geometry.Size(size.width, thickness.toPx()))
}

/** The page kicker: `PERSONAL COLLECTION · 229 SONGS`. Mono, tracked, uppercase. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = RizxTheme.colors.text) {
    Text(text.uppercase(), style = code(11, FontWeight.Medium, 0.13f), color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** An eyebrow that starts with the red signal dot: `■ FAVORITES`. */
@Composable
fun SignalEyebrow(text: String, modifier: Modifier = Modifier, color: Color = RizxTheme.colors.text) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(RizxTheme.colors.redAccent))
        Text(text.uppercase(), style = code(11, FontWeight.Medium, 0.13f), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The giant display title (`Settings.`, `Your library.`, `Playlists`).
 *
 * Sized like the design's `17vw`: a fraction of the width it is given, capped. Then measured, and
 * stepped down until it fits on one line — "A tua biblioteca." would otherwise break mid-word on a
 * narrow phone. Below [minSp] it stops shrinking and wraps, which is what the web does at that point.
 */
@Composable
fun DisplayTitle(
    text: String,
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.185f,
    maxSp: Int = 96,
    minSp: Int = 36,
    color: Color = RizxTheme.colors.text,
    tracking: Float = -0.065f,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth
        val ceiling = (maxWidth.value * widthFraction).toInt().coerceIn(minSp, maxSp)
        val size = remember(text, widthPx, ceiling, minSp, tracking) {
            var sp = ceiling
            while (sp > minSp) {
                val layout = measurer.measure(
                    text = text,
                    style = sg(sp, FontWeight.Medium, tracking),
                    constraints = Constraints(maxWidth = widthPx),
                )
                if (layout.lineCount == 1 && !layout.didOverflowWidth) break
                sp -= 2
            }
            sp
        }
        Text(
            text,
            style = sg(size, FontWeight.Medium, tracking, lineHeight = (size * 0.94f).toInt()),
            color = color,
        )
    }
}

/** A section title inside a surface or a card header (`Your playlists`, `Sound`, `Bands`). */
@Composable
fun SurfaceTitle(text: String, modifier: Modifier = Modifier, size: Int = 26, color: Color = RizxTheme.colors.text) {
    Text(
        text,
        style = sg(size, FontWeight.Medium, -0.04f, lineHeight = size),
        color = color,
        modifier = modifier,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The sentence under a display title. */
@Composable
fun Lede(text: String, modifier: Modifier = Modifier, color: Color = RizxTheme.colors.text2) {
    Text(text, style = mr(14, FontWeight.Medium, lineHeight = 21), color = color, modifier = modifier)
}

/**
 * A framed surface: 2dp ink border, bright paper, hard offset shadow. The shadow is drawn inside this
 * composable's own bounds (it reserves the offset), so a column of surfaces needs no extra spacing.
 */
@Composable
fun EditorialSurface(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
    shadowOffset: Dp = 5.dp,
    background: Color = Editorial.surface,
    /** Makes the whole surface a target — a tile that opens something. */
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = RizxTheme.colors
    Box(modifier.padding(end = shadowOffset, bottom = shadowOffset)) {
        Column(
            Modifier
                .fillMaxWidth()
                .brutalShadow(Editorial.shadow, offset = shadowOffset)
                .background(background)
                .border(Editorial.Frame, c.hardLine, RectangleShape)
                .then(if (onClick != null) Modifier.clickableScale(scale = 0.98f, onClick = onClick) else Modifier)
                .padding(padding),
            content = content,
        )
    }
}

/**
 * The compact heading of a surface: eyebrow over a title, with an optional text action on the right
 * (`SEE ALL →`). Bottom-aligned like the design's `align-items: flex-end`.
 */
@Composable
fun SurfaceHeading(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    titleSize: Int = 19,
) {
    val c = RizxTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            SignalEyebrow(eyebrow)
            Text(
                title,
                style = sg(titleSize, FontWeight.Bold, -0.01f, lineHeight = titleSize),
                color = c.text,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (action != null && onAction != null) {
            Text(
                "${action.uppercase()} →",
                style = code(12, FontWeight.Medium, 0.02f),
                color = c.text2,
                textAlign = TextAlign.End,
                maxLines = 2,
                modifier = Modifier
                    .widthIn(max = 100.dp)
                    .clickableScale(scale = 0.94f, onClick = onAction)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * The design's search field: 2dp frame, bright paper, a hard shadow that turns red with the frame while
 * the field is in use. Filtering is live; the IME's search key only puts the keyboard away.
 */
@Composable
fun EditorialSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = 54.dp,
    onSubmit: (() -> Unit)? = null,
) {
    val c = RizxTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val active = focused || query.isNotEmpty()
    val shadow = if (active) c.redAccent.copy(alpha = 0.15f) else Editorial.shadow
    Box(modifier.fillMaxWidth().padding(end = 5.dp, bottom = 5.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .brutalShadow(shadow, offset = 5.dp)
                .background(c.elev)
                .border(Editorial.Frame, if (active) c.redAccent else c.hardLine, RectangleShape)
                .padding(start = 15.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(RizxIcons.Search, null, tint = c.text, modifier = Modifier.size(22.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                singleLine = true,
                textStyle = mr(14, FontWeight.Medium).copy(color = c.text),
                cursorBrush = SolidColor(c.redAccent),
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    onSubmit?.invoke()
                    keyboard?.hide()
                }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(hint, style = mr(14, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Box(
                    Modifier.size(40.dp).clickableScale(scale = 0.86f) { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(RizxIcons.Close, stringResource(R.string.action_clear), tint = c.text2, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * The design's button: outlined in ink, or — [primary] — filled red. Mono uppercase, tracked, with an
 * optional 18dp glyph before the label.
 */
@Composable
fun EditorialButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
    /** Two of these side by side on a phone: tighter padding and tracking, as the design's hero does. */
    dense: Boolean = false,
) {
    val c = RizxTheme.colors
    val fill = if (primary) c.redAccent else Color.Transparent
    val edge = if (primary) c.redAccent else c.hardLine
    val ink = if (primary) c.onRed else c.text
    Row(
        modifier
            .heightIn(min = if (primary) 52.dp else 46.dp)
            .clip(RectangleShape)
            .background(fill)
            .border(Editorial.Frame, edge, RectangleShape)
            .clickableScale(scale = 0.96f, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (dense) 8.dp else 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (dense) 7.dp else 9.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription ?: label, tint = ink.copy(alpha = if (enabled) 1f else 0.5f), modifier = Modifier.size(18.dp))
        Text(
            label.uppercase(),
            style = code(if (dense) 11 else 12, FontWeight.SemiBold, if (dense) 0.04f else 0.09f),
            color = ink.copy(alpha = if (enabled) 1f else 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A row of segments where exactly one is chosen (`SYSTEM · LIGHT · DARK`). The chosen one is an ink
 * block; the others are outlined.
 */
@Composable
fun <T> SegmentedTabs(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = RizxTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val active = option == selected
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(RectangleShape)
                    .background(if (active) c.accent else Color.Transparent)
                    .border(Editorial.Frame, c.hardLine, RectangleShape)
                    .clickableScale(scale = 0.95f, onClick = { onSelect(option) })
                    .padding(horizontal = 11.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option).uppercase(), style = code(10, FontWeight.Medium, 0.1f), color = if (active) c.onFill else c.text, maxLines = 1)
            }
        }
    }
}

/** A select-looking control: the current value and a chevron in a 2dp frame; tapping opens a picker. */
@Composable
fun EditorialSelect(value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        modifier
            // Language names can be much wider than the setting title. The selector is used in a
            // stacked phone row, so bound it to the available reading column instead of letting its
            // intrinsic text width squeeze the title out of a side-by-side layout.
            .widthIn(max = 280.dp)
            .heightIn(min = 44.dp)
            .clip(RectangleShape)
            .background(c.elev)
            .border(Editorial.Frame, c.hardLine, RectangleShape)
            .clickableScale(scale = 0.97f, onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            value,
            style = mr(12, FontWeight.Medium),
            color = c.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(RizxIcons.ChevronDown, null, tint = c.text, modifier = Modifier.size(16.dp))
    }
}

/** The red serial on a tile: `P01`. */
@Composable
fun IndexTag(text: String, modifier: Modifier = Modifier) {
    val c = RizxTheme.colors
    Text(
        text,
        style = code(10, FontWeight.Medium, 0.02f),
        color = c.onRed,
        modifier = modifier.background(c.redAccent).padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

/** The `→` that ends a row that goes somewhere. */
@Composable
fun RowArrow(modifier: Modifier = Modifier, color: Color = RizxTheme.colors.text, size: Int = 22) {
    Text("→", style = sg(size, FontWeight.Medium, 0f), color = color, modifier = modifier)
}

/**
 * A 2×2 collage of covers. Fewer than four cycle; none at all draws the tinted fallback with an
 * optional [initial]. The quadrants are seamless — the frame, if any, belongs to the caller.
 */
@Composable
fun CollageArt(
    covers: List<String>,
    seed: String,
    modifier: Modifier = Modifier,
    initial: String? = null,
    initialSize: Int = 24,
) {
    val tiles = LibraryStats.collage(covers)
    if (tiles.isEmpty()) {
        CoverArt(tintFor(seed), initial = initial, modifier = modifier, initialSize = initialSize, borderColor = Color.Transparent, borderWidth = 0.dp)
        return
    }
    Column(modifier) {
        for (row in 0 until 2) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 2) {
                    val index = row * 2 + col
                    CoverArt(
                        tintFor("$seed$index"),
                        initial = null,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        imageUrl = tiles[index],
                        borderColor = Color.Transparent,
                        borderWidth = 0.dp,
                    )
                }
            }
        }
    }
}

/** A square version of [CollageArt] for grids. */
@Composable
fun SquareCollage(covers: List<String>, seed: String, modifier: Modifier = Modifier, initial: String? = null) {
    CollageArt(covers, seed, modifier.aspectRatio(1f), initial = initial)
}

/**
 * The design's empty state (`.search-no-results`): a framed block with a red `00`, a title and a line
 * of body, and — when there is somewhere to go — one primary action.
 */
@Composable
fun EmptyBlock(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    serial: String = "00",
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = RizxTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.elev.copy(alpha = 0.7f))
            .border(Editorial.Frame, c.hardLine, RectangleShape)
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(serial, style = code(40, FontWeight.Medium, 0f), color = c.redAccent)
        Text(title, style = sg(22, FontWeight.Medium, -0.02f), color = c.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        Text(body, style = mr(13, FontWeight.Medium, lineHeight = 19), color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp))
        if (actionLabel != null && onAction != null) {
            EditorialButton(actionLabel, onAction, Modifier.padding(top = 20.dp), primary = true)
        }
    }
}

/** Fills the row with the children spaced by [gap], each taking an equal share — two hero buttons side by side. */
@Composable
fun EqualRow(modifier: Modifier = Modifier, gap: Dp = 10.dp, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), content = content)
}
