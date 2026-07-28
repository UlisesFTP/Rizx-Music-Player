package fm.rizx.player.ui.lyrics

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.LyricWord
import fm.rizx.player.domain.model.LyricsVisualQuality
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.ui.theme.RizxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The karaoke view is the one screen whose correctness is a question of *timing* — which line is active,
 * where the sweep has got to, whether the list followed. None of that can be asserted on the JVM, so it
 * is asserted here, against real composition, real layout and a real frame clock.
 *
 * Deliberately built on `ComponentActivity` rather than the app's own — no Hilt, no navigation, no
 * playback service. The composable takes a `StateFlow<PlaybackState>`, so driving it is a matter of
 * writing to that flow, which is exactly what the engine does in production.
 *
 * **Two things had to be pinned down for these to be tests rather than dice.**
 *
 * `mainClock.autoAdvance = false`: the sweep runs a `withFrameMillis` loop, so Compose is *never* idle
 * while a timed lyric is playing and anything that waits for idleness times out. Freezing the clock and
 * stepping it by hand is the documented way to test a continuous animation, and it makes each step
 * observable besides.
 *
 * `sampledAtElapsedMs = 0`: that switches off extrapolation, so the clock reports exactly the position
 * written to the flow instead of that position plus however long the test itself took. The
 * extrapolation maths has its own JVM coverage in `SmoothPositionTest`.
 */
@RunWith(AndroidJUnit4::class)
class KaraokeLyricsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Twenty lines, four seconds apart, each with two timed words. */
    private val lines = (0 until 20).map { i ->
        val start = i * 4_000L
        LyricLine(
            timeMs = start,
            text = "line $i here",
            words = listOf(
                LyricWord(start, start + 2_000, "line "),
                LyricWord(start + 2_000, start + 4_000, "$i here"),
            ),
            endMs = start + 4_000,
        )
    }

    private val playback = MutableStateFlow(PlaybackState())

    @Before
    fun freezeTheFrameClock() {
        rule.mainClock.autoAdvance = false
    }

    private fun playing(positionMs: Long) = PlaybackState(
        currentQueueItemId = "q0",
        positionMs = positionMs,
        durationMs = 80_000,
        isPlaying = true,
    )

    /** Let the effects run, the loop tick and any scroll animation finish. */
    private fun settle(ms: Long = 1_500) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun start(
        quality: LyricsVisualQuality = LyricsVisualQuality.HIGH,
        offsetMs: Long = 0L,
        onSeekMs: (Long) -> Unit = {},
        wrapper: @Composable (@Composable () -> Unit) -> Unit = { it() },
    ) {
        rule.setContent {
            wrapper {
                RizxTheme(darkTheme = true) {
                    KaraokeLyricsList(
                        lines = lines,
                        offsetMs = offsetMs,
                        playback = playback,
                        quality = quality,
                        onSeekMs = onSeekMs,
                    )
                }
            }
        }
        settle()
    }

    @Test
    fun theListFollowsTheClockToTheActiveLine() {
        start()
        playback.value = playing(0)
        settle()
        rule.onNodeWithText("line 0 here").assertIsDisplayed()

        // Line 10 starts at 40 s. Position alone never recomposes the list — the active index is derived
        // from it — so this exercises clock → derived index → auto-scroll, end to end.
        playback.value = playing(41_000)
        settle()
        rule.onNodeWithText("line 10 here").assertIsDisplayed()
    }

    @Test
    fun seekingBackwardsBringsTheEarlierLineBack() {
        start()
        playback.value = playing(41_000)
        settle()

        playback.value = playing(4_500)
        settle()
        rule.onNodeWithText("line 1 here").assertIsDisplayed()
    }

    @Test
    fun tappingALineSeeksToItsTimestampPlusTheOffset() {
        var seekedTo = -1L
        start(offsetMs = 750L, onSeekMs = { seekedTo = it })
        playback.value = playing(0)
        settle()

        rule.onNodeWithText("line 2 here").performClick()
        settle()

        // The timeline subtracts the offset from the audio position; tap-to-seek adds it back.
        assertEquals(8_750L, seekedTo)
    }

    @Test
    fun aPausedSongHoldsItsLineInsteadOfDrifting() {
        start()
        // A real sample stamp this time: paused playback must ignore it rather than extrapolate from it.
        playback.value = playing(20_500).copy(
            isPlaying = false,
            sampledAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        settle()
        rule.onNodeWithText("line 5 here").assertIsDisplayed()

        settle(5_000)
        rule.onNodeWithText("line 5 here").assertIsDisplayed()
    }

    @Test
    fun theBatterySaverProfileStillShowsTheRightLine() {
        start(quality = LyricsVisualQuality.BATTERY_SAVER)
        playback.value = playing(12_500)
        settle()
        rule.onNodeWithText("line 3 here").assertIsDisplayed()
    }

    @Test
    fun aLargeFontScaleDoesNotBreakTheSweep() {
        start { inner ->
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = 1.5f),
                content = inner,
            )
        }
        playback.value = playing(8_500)
        settle()
        rule.onNodeWithText("line 2 here").assertIsDisplayed()
    }

    @Test
    fun switchingSongsRapidlyDoesNotStrandTheClock() {
        start()
        // Each new id resets the clock's monotonic guard; the guard must not then pin the position to a
        // previous song's high-water mark, which would freeze the sweep on whatever plays next.
        repeat(6) { i ->
            playback.value = playing((i * 7_000).toLong()).copy(currentQueueItemId = "q$i")
            settle(200)
        }
        playback.value = playing(0).copy(currentQueueItemId = "final")
        settle()
        rule.onNodeWithText("line 0 here").assertIsDisplayed()
    }
}
