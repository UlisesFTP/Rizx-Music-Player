package fm.rizx.player.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which code paths a cold start and a Home scroll actually execute, so they can be AOT-compiled
 * on the user's device instead of interpreted on first run.
 *
 * The journey deliberately stops at the Home: that is what every launch pays for, and it is the screen
 * the owner reported as slow. It does **not** start playback — the profile should describe the launch
 * path, and pulling audio would make the run depend on the network.
 *
 * Run with `./gradlew :app:generateReleaseBaselineProfile`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        val targetPackage = InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: error("targetAppId not passed — run this through the baselineprofile Gradle plugin")

        rule.collect(packageName = targetPackage) {
            pressHome()
            startActivityAndWait()

            // The splash dissolves, then the Home renders (from its disk cache on any run but the first).
            device.wait(Until.hasObject(By.scrollable(true)), HOME_TIMEOUT_MS)
            device.waitForIdle()

            device.findObject(By.scrollable(true))?.let { feed ->
                // Without a margin the fling starts on the system gesture strip and is swallowed.
                feed.setGestureMargin(device.displayWidth / GESTURE_MARGIN_FRACTION)
                repeat(SCROLLS) {
                    feed.fling(Direction.DOWN)
                    device.waitForIdle()
                }
                feed.fling(Direction.UP)
                device.waitForIdle()
            }
        }
    }

    private companion object {
        const val HOME_TIMEOUT_MS = 20_000L
        const val GESTURE_MARGIN_FRACTION = 5
        const val SCROLLS = 3
    }
}
