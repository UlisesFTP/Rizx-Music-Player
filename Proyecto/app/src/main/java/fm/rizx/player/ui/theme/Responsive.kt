package fm.rizx.player.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Screen-size buckets, following Material's window size classes.
 *
 * Measured in **dp, not pixels**, so it reflects what the user actually did: a phone set to a larger
 * display size reports fewer dp and lands in [Compact] even on a high-resolution panel. That is the case
 * this exists for — a 1220px-wide device can behave like a narrow one.
 */
enum class RizxWidth { Compact, Medium, Expanded }

@Composable
@ReadOnlyComposable
fun rizxWidth(): RizxWidth {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 400 -> RizxWidth.Compact
        widthDp < 600 -> RizxWidth.Medium
        else -> RizxWidth.Expanded
    }
}

/** True on tablets and unfolded foldables, where a full-bleed list would run absurdly wide. */
@Composable
@ReadOnlyComposable
fun isTablet(): Boolean = rizxWidth() == RizxWidth.Expanded

/** Side padding for a screen's content. Narrow screens buy back the margin they can't spare. */
@Composable
@ReadOnlyComposable
fun pagePadding(): Dp = when (rizxWidth()) {
    RizxWidth.Compact -> 16.dp
    RizxWidth.Medium -> 22.dp
    RizxWidth.Expanded -> 32.dp
}

/**
 * How much space the floating mini-player + bottom nav occupy, so scrollable content can end above them
 * instead of underneath.
 *
 * Provided by `RizxApp` from the **measured** height of that chrome. It was previously a hard-coded
 * guess repeated in a dozen screens — 120.dp against a stack that is really ~180dp with the navigation
 * bar, which is exactly why the last row or two of every list sat behind the mini-player. Measuring also
 * keeps it right when the chrome grows: bigger system font, three-button navigation, no mini-player.
 */
val LocalBottomInset = compositionLocalOf { 120.dp }

/**
 * Caps how far the system font scale can stretch text inside a fixed-height container.
 *
 * The mini-player and the bottom nav are laid out to a fixed height; at the 1.3–1.5× that Xiaomi and
 * Samsung ship as a display option, their labels overflow and buttons get pushed off the edge. Body
 * content elsewhere still scales fully — this is deliberately scoped to the chrome, where a clipped
 * control is worse than slightly smaller text.
 */
@Composable
fun ClampedFontScale(max: Float = 1.15f, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= max) {
        content()
    } else {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalDensity provides Density(density.density, max),
            content = content,
        )
    }
}

/**
 * Centres a screen's content and stops it stretching past a comfortable reading width on tablets.
 *
 * A phone is unaffected: [maxWidth] is far wider than any phone, so this is a no-op there.
 */
@Composable
fun ResponsiveContent(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 720.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit,
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = verticalArrangement,
    ) {
        Column(Modifier.widthIn(max = maxWidth).fillMaxWidth()) { content() }
    }
}
