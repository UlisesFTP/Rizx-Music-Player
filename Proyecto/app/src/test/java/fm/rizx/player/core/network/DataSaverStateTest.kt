package fm.rizx.player.core.network

import fm.rizx.player.FakeSettingsRepository
import fm.rizx.player.dataSaverState
import fm.rizx.player.domain.model.AudioQualityMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two questions that were one flag, and the hotspot that fell through the gap between them.
 *
 * `saving` is about **quality** and applies on any connection — the switch used to require `isCellular`,
 * which meant it did nothing on home Wi-Fi that might well be metered. `blocksBulkTransfer` is about
 * **large deliberate downloads** and additionally requires the link to cost money, because downloading on
 * Wi-Fi is how someone avoids spending mobile data later.
 */
class DataSaverStateTest {

    @Test
    fun `neither switch means no saving`() = runTest {
        assertFalse(dataSaverState().saving.first())
    }

    @Test
    fun `the app's own switch saves on any connection, Wi-Fi included`() = runTest {
        // The whole point of the change: it used to be inert until you left the house.
        val settings = FakeSettingsRepository().apply { dataSaverFlow.value = true }

        assertTrue(dataSaverState(settings, unmetered = true).saving.first())
        assertTrue(dataSaverState(settings, unmetered = false).saving.first())
    }

    @Test
    fun `Android's own Data Saver counts, with Rizx's switch untouched`() = runTest {
        val state = dataSaverState(systemSaver = true)

        assertTrue(state.saving.first())
    }

    @Test
    fun `an unavailable system service is not mistaken for save-nothing`() = runTest {
        // The guard matters: a ConnectivityManager that cannot be read must fall back to the app's own
        // switch rather than throwing on the way to a decision playback is waiting for.
        val settings = FakeSettingsRepository().apply { dataSaverFlow.value = true }

        assertTrue(dataSaverState(settings).saving.first())
    }

    @Test
    fun `a download on Wi-Fi is allowed even while saving`() = runTest {
        // Downloading on Wi-Fi is not waste, it is the thing that prevents waste.
        val settings = FakeSettingsRepository().apply { dataSaverFlow.value = true }

        assertFalse(dataSaverState(settings, unmetered = true).blocksBulkTransfer())
    }

    @Test
    fun `a download on mobile data is held while saving`() = runTest {
        val settings = FakeSettingsRepository().apply { dataSaverFlow.value = true }

        assertTrue(dataSaverState(settings, unmetered = false).blocksBulkTransfer())
    }

    @Test
    fun `a hotspot is metered Wi-Fi, and no longer slips through`() = runTest {
        // The bug this class exists to close. A phone hotspot reports Wi-Fi transport while billing
        // somebody's data plan, so every rule keyed on `isCellular` quietly switched itself off.
        val settings = FakeSettingsRepository().apply { dataSaverFlow.value = true }
        val hotspot = dataSaverState(settings, unmetered = false)

        assertTrue("a metered link is a metered link, whatever the radio says", hotspot.blocksBulkTransfer())
    }

    @Test
    fun `a download is allowed on mobile data when nothing is saving`() = runTest {
        assertFalse(dataSaverState(unmetered = false).blocksBulkTransfer())
    }

    @Test
    fun `the effective quality mode is standard while saving, and the stored one is untouched`() = runTest {
        // The promise: turning data saving off gives the choice straight back. Overwriting the stored
        // preference would have left the user on Standard for good, with nothing to tell them.
        val settings = FakeSettingsRepository().apply {
            audioQualityModeFlow.value = AudioQualityMode.LOSSLESS_PREFERRED
            dataSaverFlow.value = true
        }
        val state = dataSaverState(settings)

        assertEquals(AudioQualityMode.STANDARD, state.effectiveQualityMode())
        assertEquals(
            "the saved choice must survive",
            AudioQualityMode.LOSSLESS_PREFERRED,
            settings.audioQualityModeFlow.value,
        )

        settings.dataSaverFlow.value = false
        assertEquals(AudioQualityMode.LOSSLESS_PREFERRED, state.effectiveQualityMode())
    }

    @Test
    fun `best-available is left alone when nothing is saving`() = runTest {
        val settings = FakeSettingsRepository().apply {
            audioQualityModeFlow.value = AudioQualityMode.BEST_AVAILABLE
        }

        assertEquals(AudioQualityMode.BEST_AVAILABLE, dataSaverState(settings).effectiveQualityMode())
    }

    @Test
    fun `savingNow agrees with the flow`() = runTest {
        // The synchronous read exists for the audio sink and the loader thread; if it ever disagreed with
        // the flow the two halves of the app would behave differently on the same setting.
        val settings = FakeSettingsRepository().apply { dataSaverFlow.value = true }
        val state = dataSaverState(settings)

        assertEquals(state.saving.first(), state.savingNow())
    }
}
