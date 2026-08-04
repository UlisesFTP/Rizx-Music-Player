package fm.rizx.player.ui.util

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Semantic, Nothing-OS-crisp haptics over the platform view's `performHapticFeedback`. This path
 * needs **no `VIBRATE` permission**, respects the user's system touch-feedback setting, and on
 * API-34 hardware these constants are backed by rich vibrator primitive compositions. Several of the
 * semantic constants are newer than minSdk 26 (CONFIRM/REJECT are 30, SEGMENT_TICK/TOGGLE_* are 34) —
 * they're inlined ints, so an old device wouldn't crash but would silently *skip* the feedback; the
 * per-method fallbacks below keep every gesture tactile all the way down. Obtain one per screen with
 * [rememberRizxHaptics]; the single central caller is `Modifier.clickableScale` (so every tap ticks),
 * with the stronger variants fired explicitly at key moments.
 */
class RizxHaptics(private val view: View) {

    private fun perform(constant: Int) {
        // Ignore the per-view opt-in (our haptics are intentional) but still honour the system setting.
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }

    /** Light tap — the default press feedback used app-wide. */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** Discrete step — tab switch, chip select, and each waveform bar crossed while scrubbing. */
    fun select() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
        else HapticFeedbackConstants.CLOCK_TICK,
    )

    /** Positive confirm — play, like, install success. */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    /** Heavier — long-press, drag-to-reorder pickup. */
    fun heavy() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** Negative — failed action / error. */
    fun error() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS,
    )

    /** Switch flip, matching direction. */
    fun toggle(on: Boolean) = perform(
        when {
            Build.VERSION.SDK_INT >= 34 ->
                if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            else -> HapticFeedbackConstants.CLOCK_TICK
        },
    )
}

@Composable
fun rememberRizxHaptics(): RizxHaptics {
    val view = LocalView.current
    return remember(view) { RizxHaptics(view) }
}
