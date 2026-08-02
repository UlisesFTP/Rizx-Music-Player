package fm.rizx.player.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.util.RizxHaptics

/**
 * The classic local-player fast-scroll rail: a thin touch column of the letters the list actually has.
 * Tap or drag lands on a letter; the caller scrolls its list. THE interaction for a thousand-file scan —
 * flinging through "S" one screen at a time is not one.
 *
 * Only the letters present are drawn (a rail full of dead letters teaches the finger nothing), and each
 * crossing ticks the same haptic the waveform scrubber uses, so it *feels* like the same instrument.
 */
@Composable
fun AlphabetRail(
    letters: List<Char>,
    onPick: (Char) -> Unit,
    haptics: RizxHaptics,
    modifier: Modifier = Modifier,
) {
    if (letters.size < MIN_LETTERS) return
    val c = RizxTheme.colors
    var active by remember { mutableStateOf<Char?>(null) }

    fun pickAt(y: Float, height: Int) {
        if (height <= 0) return
        val index = ((y / height) * letters.size).toInt().coerceIn(0, letters.lastIndex)
        val letter = letters[index]
        if (letter != active) {
            active = letter
            haptics.select()
            onPick(letter)
        }
    }

    Column(
        modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .pointerInput(letters) {
                detectTapGestures(onPress = { offset -> pickAt(offset.y, size.height); active = null })
            }
            .pointerInput(letters) {
                detectDragGestures(
                    onDragEnd = { active = null },
                    onDragCancel = { active = null },
                ) { change, _ -> pickAt(change.position.y, size.height) }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            Text(
                letter.toString(),
                style = code(9, FontWeight.Bold, 0f),
                color = if (letter == active) c.redAccent else c.muted,
            )
        }
    }
}

private val RAIL_WIDTH = 22.dp

/** Below this many distinct initials the list is short enough that the rail is clutter, not help. */
private const val MIN_LETTERS = 5
