package fm.rizx.player.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.ui.theme.RizxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickPickGridTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun tracks(n: Int) = List(n) {
        Track(title = "Song $it", source = ProviderRef("deezer", "$it"))
    }

    @Test
    fun grid_exposes_six_playable_named_targets() {
        var played = ""
        rule.setContent {
            RizxTheme(darkTheme = false) {
                QuickPickGrid(
                    tracks = tracks(7),
                    currentSource = ProviderRef("deezer", "0"),
                    isPlaying = false,
                    motionEnabled = false,
                    onPlay = { played = it.title },
                )
            }
        }

        rule.onNodeWithText("Song 0").assertIsDisplayed()
        rule.onNodeWithText("Song 5").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Song 6").fetchSemanticsNodes().isEmpty())
        val current = rule.onNodeWithContentDescription("Song 0", substring = true)
        assertEquals(true, current.fetchSemanticsNode().config[SemanticsProperties.Selected])
        current
            .assertHasClickAction()
            .performClick()
        assertEquals("Song 0", played)
    }
}
