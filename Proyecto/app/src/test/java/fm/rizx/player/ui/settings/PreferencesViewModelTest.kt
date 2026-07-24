package fm.rizx.player.ui.settings

import app.cash.turbine.test
import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.core.cache.CacheManager
import fm.rizx.player.domain.playback.AudioEffectsController
import fm.rizx.player.domain.model.ThemeMode
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.playback.AudioOutputCapabilities
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = FakeSettings()
    private val effects = mockk<AudioEffectsController>(relaxed = true)
    private val cache = mockk<CacheManager> {
        every { diskSizeLabel() } returns "12.0 MB"
        coEvery { clear() } returns Unit
    }
    private val audioOutput = mockk<AudioOutputCapabilities> {
        every { describe() } returns "48 kHz output"
    }

    private fun vm() = PreferencesViewModel(settings, effects, cache, audioOutput)

    @Test
    fun `toggling data saver persists and re-emits`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vm()
        vm.dataSaver.test {
            assertEquals(false, awaitItem()) // default = max quality
            vm.setDataSaver(true)
            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun `gapless defaults on and toggles off`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vm()
        vm.gapless.test {
            assertEquals(true, awaitItem()) // default on = seamless
            vm.setGapless(false)
            assertEquals(false, awaitItem())
        }
    }

    @Test
    fun `normalize forwards to the live audio effect`() {
        vm().setNormalize(true)
        verify { effects.setNormalizeVolume(true) }
    }

    @Test
    fun `hi-res persists, re-emits, and exposes the DAC capability label`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val vm = vm()
            assertEquals("48 kHz output", vm.audioOutputLabel.value)
            vm.hiRes.test {
                assertEquals(false, awaitItem()) // opt-in, off by default
                vm.setHiRes(true)
                assertEquals(true, awaitItem())
            }
        }

    @Test
    fun `clear cache clears the image cache and refreshes the size`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            every { cache.diskSizeLabel() } returnsMany listOf("12.0 MB", "0 B")
            val vm = vm()
            assertEquals("12.0 MB", vm.cacheSize.value)

            vm.clearCache()
            advanceUntilIdle()

            coVerify { cache.clear() }
            assertEquals("0 B", vm.cacheSize.value)
        }

    /** Backs only the four Settings toggles with live flows; everything else is inert. */
    private class FakeSettings : SettingsRepository {
        val dataSaverFlow = MutableStateFlow(false)
        val crossfadeFlow = MutableStateFlow(false)
        val gaplessFlow = MutableStateFlow(true)
        val normalizeFlow = MutableStateFlow(false)

        override val dataSaver = dataSaverFlow
        override suspend fun setDataSaver(enabled: Boolean) { dataSaverFlow.value = enabled }
        override val crossfade = crossfadeFlow
        override suspend fun setCrossfade(enabled: Boolean) { crossfadeFlow.value = enabled }
        override val gapless = gaplessFlow
        override suspend fun setGapless(enabled: Boolean) { gaplessFlow.value = enabled }
        override val normalizeVolume = normalizeFlow
        override suspend fun setNormalizeVolume(enabled: Boolean) { normalizeFlow.value = enabled }
        val hiResFlow = MutableStateFlow(false)
        override val hiResOutput = hiResFlow
        override suspend fun setHiResOutput(enabled: Boolean) { hiResFlow.value = enabled }
        private val canvasFlow = MutableStateFlow(false)
        override val canvasEnabled = canvasFlow
        override suspend fun setCanvasEnabled(enabled: Boolean) { canvasFlow.value = enabled }
        private val syncedLyricsFlow = MutableStateFlow(true)
        override val syncedLyricsMode = syncedLyricsFlow
        override suspend fun setSyncedLyricsMode(enabled: Boolean) { syncedLyricsFlow.value = enabled }
        val audioCacheFlow = MutableStateFlow(512L * 1024 * 1024)
        override val audioCacheBytes = audioCacheFlow
        override suspend fun setAudioCacheBytes(bytes: Long) { audioCacheFlow.value = bytes }

        override val themeMode = flowOf(ThemeMode.SYSTEM)
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override val activeMetadataProviderId = flowOf<String?>(null)
        override suspend fun setActiveMetadataProviderId(id: String?) {}
        override val activeStreamingProviderId = flowOf<String?>(null)
        override suspend fun setActiveStreamingProviderId(id: String?) {}
        override val streamExpiryMs = flowOf(0L)
        override suspend fun setStreamExpiryMs(ms: Long) {}
        override val streamResolutionRetries = flowOf(0)
        override suspend fun setStreamResolutionRetries(retries: Int) {}
        override val equalizerEnabled = flowOf(false)
        override suspend fun setEqualizerEnabled(enabled: Boolean) {}
        override val equalizerBandLevels = flowOf(emptyList<Int>())
        override suspend fun setEqualizerBandLevels(levels: List<Int>) {}
    }
}
