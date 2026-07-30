package fm.rizx.player.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fm.rizx.player.domain.model.RadioMode
import fm.rizx.player.domain.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class SettingsRepositoryTest {

    private fun kotlinx.coroutines.CoroutineScope.newStore(): DataStore<Preferences> {
        val dir = Files.createTempDirectory("rizx-settings").toFile()
        return PreferenceDataStoreFactory.create(scope = this) { java.io.File(dir, "settings.preferences_pb") }
    }

    @Test
    fun `theme mode defaults to system then reads back what was written`() = runTest {
        val repo = SettingsRepositoryImpl(backgroundScope.newStore())

        assertEquals(ThemeMode.SYSTEM, repo.themeMode.first())
        repo.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, repo.themeMode.first())
    }

    @Test
    fun `the radio algorithm defaults to YouTube Music and persists a change`() = runTest {
        val repo = SettingsRepositoryImpl(backgroundScope.newStore())

        assertEquals(RadioMode.YOUTUBE, repo.radioAlgorithm.first())
        repo.setRadioAlgorithm(RadioMode.ARTIST)
        assertEquals(RadioMode.ARTIST, repo.radioAlgorithm.first())
    }

    @Test
    fun `an unrecognised stored algorithm falls back to the default instead of throwing`() = runTest {
        // It is stored by name, so a renamed/removed enum constant must degrade, not crash the app.
        val store = backgroundScope.newStore()
        store.edit { it[stringPreferencesKey("core.recs.radioAlgorithm")] = "SPOTIFY_WRAPPED" }

        assertEquals(RadioMode.YOUTUBE, SettingsRepositoryImpl(store).radioAlgorithm.first())
    }

    @Test
    fun `the automatic equalizer is off until asked for, and does not touch the manual curve`() = runTest {
        val repo = SettingsRepositoryImpl(backgroundScope.newStore())
        repo.setEqualizerBandLevels(listOf(300, -200, 0, 100, 250))

        assertEquals(false, repo.autoEqualizer.first())
        repo.setAutoEqualizer(true)

        assertEquals(true, repo.autoEqualizer.first())
        // The whole point of the handover: the user's own curve is still there to be given back.
        assertEquals(listOf(300, -200, 0, 100, 250), repo.equalizerBandLevels.first())
    }

    @Test
    fun `active provider ids persist and clear`() = runTest {
        val repo = SettingsRepositoryImpl(backgroundScope.newStore())

        assertEquals(null, repo.activeMetadataProviderId.first())
        repo.setActiveMetadataProviderId("fake-metadata")
        assertEquals("fake-metadata", repo.activeMetadataProviderId.first())
        repo.setActiveMetadataProviderId(null)
        assertEquals(null, repo.activeMetadataProviderId.first())
    }

    @Test
    fun `resolver settings default then read back`() = runTest {
        val repo = SettingsRepositoryImpl(backgroundScope.newStore())

        assertEquals(3 * 60 * 60 * 1000L, repo.streamExpiryMs.first()) // 3h default
        assertEquals(2, repo.streamResolutionRetries.first())
        repo.setStreamExpiryMs(60_000L)
        repo.setStreamResolutionRetries(5)
        assertEquals(60_000L, repo.streamExpiryMs.first())
        assertEquals(5, repo.streamResolutionRetries.first())
    }

    @Test
    fun `audio and data toggles use their defaults then persist`() = runTest {
        val repo = SettingsRepositoryImpl(backgroundScope.newStore())

        // Defaults: quality is max (data saver off), gapless on, crossfade + normalize + hi-res off.
        assertEquals(false, repo.dataSaver.first())
        assertEquals(false, repo.crossfade.first())
        assertEquals(true, repo.gapless.first())
        assertEquals(false, repo.normalizeVolume.first())
        assertEquals(false, repo.hiResOutput.first())

        repo.setDataSaver(true)
        repo.setCrossfade(true)
        repo.setGapless(false)
        repo.setNormalizeVolume(true)
        repo.setHiResOutput(true)

        assertEquals(true, repo.dataSaver.first())
        assertEquals(true, repo.crossfade.first())
        assertEquals(false, repo.gapless.first())
        assertEquals(true, repo.normalizeVolume.first())
        assertEquals(true, repo.hiResOutput.first())
    }
}
