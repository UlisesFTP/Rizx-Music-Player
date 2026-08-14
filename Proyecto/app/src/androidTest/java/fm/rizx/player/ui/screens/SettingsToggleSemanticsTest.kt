package fm.rizx.player.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fm.rizx.player.ui.theme.RizxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a screen reader is told about a settings switch.
 *
 * These exist because the bug they pin down was invisible to every other check available: the rows
 * looked right, behaved right, and passed the whole JVM suite while announcing themselves to TalkBack
 * as **anonymous** controls — a node with the correct on/off state and no name at all, the title
 * sitting beside it as a separate node. It was found by dumping the accessibility tree by hand; it
 * should have been found here, in seconds, which is why the tests are being written now.
 *
 * The assertion that matters is [assertOneNamedSwitch]: *one* node, named, with a state. Two of the
 * three shapes tried during the fix produced a node that was correctly stated and nameless, and
 * `assertIsOn`/`assertIsOff` alone would have passed on all of them.
 */
@RunWith(AndroidJUnit4::class)
class SettingsToggleSemanticsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun assertOneNamedSwitch(name: String, on: Boolean) {
        // Exactly one toggleable node in the whole row: the knob must not be a second, nameless stop.
        val toggleable = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
        assertEquals(1, toggleable.fetchSemanticsNodes().size)
        // Reachable *by its name*, which is the part that was broken.
        val node = rule.onNodeWithContentDescription(name, substring = true)
        if (on) node.assertIsOn() else node.assertIsOff()
    }

    @Test
    fun a_plain_toggle_row_is_one_switch_named_after_its_title() {
        rule.setContent { RizxTheme(darkTheme = false) { ToggleRow("Crossfade", checked = false) {} } }

        assertOneNamedSwitch("Crossfade", on = false)
    }

    @Test
    fun the_switch_reports_its_state() {
        rule.setContent { RizxTheme(darkTheme = false) { ToggleRow("Gapless playback", checked = true) {} } }

        assertOneNamedSwitch("Gapless playback", on = true)
    }

    @Test
    fun the_row_is_still_operable_after_its_semantics_were_cleared() {
        // `clearAndSetSemantics` takes the row's own click with it, so the action is re-declared in the
        // block. If that ever gets dropped the switch reads perfectly and refuses to be switched.
        var toggled = 0
        rule.setContent {
            RizxTheme(darkTheme = false) { ToggleRow("Normalize volume", checked = false) { toggled++ } }
        }

        rule.onNodeWithContentDescription("Normalize volume", substring = true).performClick()

        assertEquals(1, toggled)
    }

    @Test
    fun a_captioned_row_speaks_its_caption_too() {
        // The caption is the only sentence that says what the switch does. Clearing the row's
        // descendants would drop it — sighted readers would keep it and screen-reader users would not.
        rule.setContent {
            RizxTheme(darkTheme = false) {
                ToggleRowDetail(
                    title = "Automatic equalizer",
                    caption = "A curve per song, from its genre and the recording itself.",
                    checked = false,
                ) {}
            }
        }

        assertOneNamedSwitch("Automatic equalizer", on = false)
        rule.onNodeWithContentDescription("A curve per song", substring = true).assertIsOff()
    }
}
