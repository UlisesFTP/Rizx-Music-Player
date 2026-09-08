package fm.rizx.player.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fm.rizx.player.domain.model.AudioQualityMode
import fm.rizx.player.domain.model.CanvasNetworkPolicy
import fm.rizx.player.domain.model.CanvasQuality
import fm.rizx.player.domain.model.LyricsVisualQuality
import fm.rizx.player.domain.model.DownloadFormat
import fm.rizx.player.domain.model.PlayerLayout
import fm.rizx.player.domain.model.RadioMode
import fm.rizx.player.domain.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `the player layout defaults to classic and persists a change`() = runTest {
        // Classic is the arrangement every existing install already knows — an update must not silently
        // move their like and add-to-playlist buttons.
        val repo = SettingsRepositoryImpl(backgroundScope.newStore())

        assertEquals(PlayerLayout.CLASSIC, repo.playerLayout.first())
        repo.setPlayerLayout(PlayerLayout.COMPACT)
        assertEquals(PlayerLayout.COMPACT, repo.playerLayout.first())
    }

    @Test
    fun `an unrecognised stored player layout falls back to classic`() = runTest {
        val store = backgroundScope.newStore()
        store.edit { it[stringPreferencesKey("core.ui.playerLayout")] = "CAROUSEL" }

        assertEquals(PlayerLayout.CLASSIC, SettingsRepositoryImpl(store).playerLayout.first())
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

        // Defaults (the owner's, 2026-09-08): data saver off, crossfade + gapless on, normalize off,
        // audio quality at the best available.
        assertEquals(false, repo.dataSaver.first())
        assertEquals(true, repo.crossfade.first())
        assertEquals(true, repo.gapless.first())
        assertEquals(false, repo.normalizeVolume.first())
        assertEquals(AudioQualityMode.BEST_AVAILABLE, repo.audioQualityMode.first())

        repo.setDataSaver(true)
        repo.setCrossfade(false)
        repo.setGapless(false)
        repo.setNormalizeVolume(true)
        repo.setAudioQualityMode(AudioQualityMode.STANDARD)

        assertEquals(true, repo.dataSaver.first())
        assertEquals(false, repo.crossfade.first())
        assertEquals(false, repo.gapless.first())
        assertEquals(true, repo.normalizeVolume.first())
        assertEquals(AudioQualityMode.STANDARD, repo.audioQualityMode.first())
    }

    @Test
    fun `animated covers default to on, over any network, from Apple and TIDAL but not YouTube`() = runTest {
        val repo = SettingsRepositoryImpl(backgroundScope.newStore())

        assertEquals(true, repo.canvasEnabled.first())
        assertEquals(CanvasQuality.AUTO, repo.canvasQuality.first())
        assertEquals(CanvasNetworkPolicy.ANY, repo.canvasNetworkPolicy.first())
        assertEquals(true, repo.canvasAppleEnabled.first())
        assertEquals(true, repo.canvasTidalEnabled.first())
        assertEquals(false, repo.canvasYoutubeEnabled.first())
        assertEquals(LyricsVisualQuality.AUTOMATIC, repo.lyricsVisualQuality.first())
        // The Home feed blends every source by default, as the web does.
        assertEquals(SettingsRepositoryImpl.FEED_PROVIDER_ALL, repo.feedProvider.first())

        repo.setCanvasYoutubeEnabled(true)
        repo.setCanvasNetworkPolicy(CanvasNetworkPolicy.UNMETERED_ONLY)
        assertEquals(true, repo.canvasYoutubeEnabled.first())
        assertEquals(CanvasNetworkPolicy.UNMETERED_ONLY, repo.canvasNetworkPolicy.first())
    }

    @Test
    fun `lossless settings default to wifi-only, original downloads, technical readout on`() = runTest {
        val repo = SettingsRepositoryImpl(backgroundScope.newStore())

        assertEquals(true, repo.losslessWifiOnly.first())
        assertEquals(DownloadFormat.ORIGINAL, repo.downloadFormat.first())
        assertEquals(true, repo.showTechnicalFormat.first())

        repo.setLosslessWifiOnly(false)
        repo.setDownloadFormat(DownloadFormat.OPUS)
        repo.setShowTechnicalFormat(false)

        assertEquals(false, repo.losslessWifiOnly.first())
        assertEquals(DownloadFormat.OPUS, repo.downloadFormat.first())
        assertEquals(false, repo.showTechnicalFormat.first())
    }

    @Test
    fun `the old download-FLAC switch migrates into the format, and a chosen format wins over it`() = runTest {
        val store = backgroundScope.newStore()
        val repo = SettingsRepositoryImpl(store)

        // Legacy install: the boolean is set, the format key does not exist yet.
        store.edit { it[booleanPreferencesKey("core.audio.losslessDownload")] = true }
        assertEquals(DownloadFormat.FLAC, repo.downloadFormat.first())

        // The user then picks something explicitly — the stored format must win from here on.
        repo.setDownloadFormat(DownloadFormat.MP3)
        assertEquals(DownloadFormat.MP3, repo.downloadFormat.first())
    }

    @Test
    fun `a stored lossless mode survives, and the legacy hi-res boolean migrates to best-available`() = runTest {
        // The migration exists because hiResOutput meant "best compressed", not "fetch from a community
        // index" — so it must land on BEST_AVAILABLE and never carry someone into LOSSLESS_PREFERRED.
        val store = backgroundScope.newStore()
        store.edit { it[booleanPreferencesKey("core.audio.hiResOutput")] = true }
        val repo = SettingsRepositoryImpl(store)
        assertEquals(AudioQualityMode.BEST_AVAILABLE, repo.audioQualityMode.first())

        // Once a mode has been chosen explicitly it wins over the legacy flag, in both directions.
        repo.setAudioQualityMode(AudioQualityMode.LOSSLESS_PREFERRED)
        assertEquals(AudioQualityMode.LOSSLESS_PREFERRED, SettingsRepositoryImpl(store).audioQualityMode.first())
        repo.setAudioQualityMode(AudioQualityMode.STANDARD)
        assertEquals(AudioQualityMode.STANDARD, SettingsRepositoryImpl(store).audioQualityMode.first())
        // And a legacy flag that was explicitly off still means Standard, even though a fresh install
        // now starts at Best available.
        val legacyOff = backgroundScope.newStore()
        legacyOff.edit { it[booleanPreferencesKey("core.audio.hiResOutput")] = false }
        assertEquals(AudioQualityMode.STANDARD, SettingsRepositoryImpl(legacyOff).audioQualityMode.first())
    }

    @Test
    fun `saving downloads to the phone starts unanswered and remembers both answers`() = runTest {
        val store = backgroundScope.newStore()
        val repo = SettingsRepositoryImpl(store)

        // Null, not false: "never asked" is what makes the first download put the question, and a stored
        // false would silence it forever.
        assertNull(repo.saveDownloadsToPhone.first())

        repo.setSaveDownloadsToPhone(true)
        assertEquals(true, SettingsRepositoryImpl(store).saveDownloadsToPhone.first())
        repo.setSaveDownloadsToPhone(false)
        assertEquals(false, SettingsRepositoryImpl(store).saveDownloadsToPhone.first())
    }
}
