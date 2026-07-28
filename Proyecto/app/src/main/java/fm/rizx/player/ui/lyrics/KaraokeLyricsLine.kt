package fm.rizx.player.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
    val spec = tween<Float>(300, easing = FastOutSlowInEasing)
    val alpha by animateFloatAsState(if (active) 1f else 0.38f, spec, label = "lyricAlpha")
    // Battery saver drops the size change: it is the cheapest thing to lose and the least missed.
    val scale by animateFloatAsState(
        if (!profile.scale || active) 1f else 0.965f,
        spec,
        label = "lyricScale",
    )
    val color by animateColorAsState(
        if (active) c.text else c.text2,
        tween(300, easing = FastOutSlowInEasing),
        label = "lyricColor",
    )

    val content = Modifier
        .fillMaxWidth()
        .clickableScale(scale = 0.985f, onClick = onClick)
        .padding(vertical = 9.dp)
        .graphicsLayer {
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0.5f)
        }

    if (line.text.isBlank()) {
        // An instrumental gap. Marking it keeps the previous line from staying lit through a solo.
        Box(content, contentAlignment = Alignment.CenterStart) {
            CodeLabel("· · ·", color = color, size = 13)
        }
        return
    }

    val style = sg(22, if (active) FontWeight.Bold else FontWeight.Medium, -0.01f, lineHeight = 30)

    when {
        active && profile.sweep -> WordSweepText(
            text = line.text,
            lineIndex = index,
            style = style,
            inactiveColor = c.text2,
            activeColor = c.text,
            glow = profile.glow,
            glowColor = c.redAccent,
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
                    withStyle(SpanStyle(color = c.text2)) { append(rest) }
                },
                style = style,
                modifier = content,
            )
        }

        else -> Text(line.text, style = style, color = color, modifier = content)
    }
}
