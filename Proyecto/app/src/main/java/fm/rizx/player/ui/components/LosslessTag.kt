package fm.rizx.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code

/**
 * Content identity → codec, for the songs known to play losslessly, so the rows can say so.
 *
 * A `CompositionLocal` for the same reason artwork's budget is one: it is one app-wide fact read by rows
 * in half a dozen screens, and threading it through every list's parameters would be noise. Provided once
 * near the root from `NowPlayingFormat.losslessKeys`.
 *
 * Not `static`, unlike the artwork budget: this one changes while the user watches — a song resolves and
 * its row picks up the tag — and a static local would recompose the entire tree under the provider each
 * time. The tracking form only wakes the rows that actually read it.
 */
val LocalLosslessCodecs = compositionLocalOf { emptyMap<String, String>() }

/**
 * The small mark that says a song is playing at full resolution — `FLAC`, or whatever the codec is.
 *
 * The codec rather than the word "lossless" because it is shorter, more specific, and already the
 * vocabulary of the readout under the player. It is drawn as a filled block rather than an outline so it
 * reads as a stamp at 8sp; at that size an outlined chip loses its border to antialiasing.
 *
 * Shown wherever the fact is *known* — never as a prediction. See `NowPlayingFormat.losslessKeys`.
 */
@Composable
fun LosslessTag(label: String, modifier: Modifier = Modifier) {
    val c = RizxTheme.colors
    Text(
        label.uppercase(),
        style = code(8, FontWeight.Bold, 0.08f),
        color = c.onFill,
        maxLines = 1,
        modifier = modifier
            .background(c.fill, RectangleShape)
            .border(1.dp, c.hardLine, RectangleShape)
            .padding(horizontal = 3.dp, vertical = 1.dp),
    )
}
