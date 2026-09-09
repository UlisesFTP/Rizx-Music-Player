package fm.rizx.player.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fm.rizx.player.domain.lyrics.LyricsTimeline
import fm.rizx.player.domain.lyrics.sungWordCountAt
import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.ui.components.CodeLabel
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.sg

/**
 * One row of the karaoke list.
 *
 * Only the active line is expensive. Everything else is a plain `Text` in exactly the same style, so a
 * line becoming active changes its colours and not its metrics — the text must not shift by a pixel when
 * the renderer swaps underneath it.
 *
 * Three ways a line can be lit, in descending cost:
 *  - **sweep** — the letter-by-letter fill, for word- and line-timed lyrics alike (see [WordSweepText]).
 *  - **word step** — the battery-saver path: the old two-tone split at a word boundary, no frame loop
 *    worth the name, no halo.
 *  - **flat** — every other line, dimmed.
 */
@Composable
fun KaraokeLyricsLine(
    line: LyricLine,
    index: Int,
    active: Boolean,
    profile: LyricsRenderProfile,
    timeline: LyricsTimeline,
    clock: LyricsClock,
    offsetMs: Long,
    onClick: () -> Unit,
) {
    val c = RizxTheme.colors
    val idleColor = c.text.copy(alpha = 0.26f)
    val activePendingColor = c.text.copy(alpha = 0.34f)
    val activeOffset by animateDpAsState(
        targetValue = if (active && profile.activeLineMotion) 7.dp else 0.dp,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "lyricActiveOffset",
    )
    val color by animateColorAsState(
        if (active) c.text else idleColor,
        tween(180, easing = FastOutSlowInEasing),
        label = "lyricColor",
    )

    val content = Modifier
        .fillMaxWidth()
        .clickableScale(scale = 0.985f, onClick = onClick)
        .padding(vertical = 14.dp)
        // Placement-only, like CSS translateX: line wrapping never changes when a row becomes active.
        .offset(x = activeOffset)

    if (line.text.isBlank()) {
        // An instrumental gap. Marking it keeps the previous line from staying lit through a solo.
        Box(content, contentAlignment = Alignment.CenterStart) {
            CodeLabel("· · ·", color = color, size = 13)
        }
        return
    }

    val widthDp = LocalConfiguration.current.screenWidthDp
    val lyricSize = when {
        widthDp <= 360 -> 26
        widthDp < 600 -> 28
        else -> 30
    }
    val lineHeight = when (lyricSize) { 26 -> 32; 28 -> 34; else -> 37 }
    val style = sg(lyricSize, if (active) FontWeight.SemiBold else FontWeight.Medium, -0.03f, lineHeight)

    when {
        active && profile.sweep -> WordSweepText(
            text = line.text,
            lineIndex = index,
            style = style,
            inactiveColor = activePendingColor,
            activeColor = c.text,
            bloom = profile.bloom,
            bloomColor = c.redAccent,
            // Invoked during the draw phase: this is where the per-frame clock is read, and reading it
            // here is what keeps the sweep off the recomposition path entirely.
            sweep = { timeline.stateAt(clock.frameMs, offsetMs) },
            modifier = content,
        )

        active && line.words.isNotEmpty() -> {
            // Battery saver: whole words, so the clock only has to be read a handful of times a second.
            val sungWords = line.sungWordCountAt(clock.frameMs, offsetMs)
            Text(
                buildAnnotatedString {
                    val sung = line.words.take(sungWords).joinToString(separator = "") { it.text }
                    val rest = line.words.drop(sungWords).joinToString(separator = "") { it.text }
                    withStyle(SpanStyle(color = c.text)) { append(sung) }
                    withStyle(SpanStyle(color = activePendingColor)) { append(rest) }
                },
                style = style,
                modifier = content,
            )
        }

        else -> Text(line.text, style = style, color = color, modifier = content)
    }
}
