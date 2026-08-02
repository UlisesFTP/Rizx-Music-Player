package fm.rizx.player.ui.settings

import app.cash.turbine.test
import fm.rizx.player.FakeCanvasRepository
import fm.rizx.player.FakeLosslessIndexSource
import fm.rizx.player.dataSaverState
import fm.rizx.player.FakeSettingsRepository
import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.core.cache.CacheManager
import fm.rizx.player.core.region.RegionResolver
import fm.rizx.player.data.local.settings.SettingsRepositoryImpl
import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.AudioQualityMode
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.AudioEffectsController
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.provider.ProviderKind
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

    private val settings = FakeSettingsRepository()
    private val effects = mockk<AudioEffectsController>(relaxed = true)
    private val cache = mockk<CacheManager> {
        every { diskSizeLabel() } returns "12.0 MB"
        coEvery { clear() } returns Unit
    }
    private val audioOutput = mockk<AudioOutputCapabilities> {
        every { describe() } returns "48 kHz output"
    }

    private fun vm() = PreferencesViewModel(
        settings, effects, cache, audioOutput, RegionResolver(listOf { "mx" }), DefaultProviderRegistry(),
        FakeCanvasRepository(), FakeLosslessIndexSource(), dataSaverState(settings),
    )

    @Test
    fun `feed provider defaults to Deezer and persists a pick`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vm()
        vm.feedProvider.test {
            assertEquals(SettingsRepositoryImpl.DEFAULT_FEED_PROVIDER, awaitItem())
            vm.setFeedProvider(SettingsRepositoryImpl.FEED_PROVIDER_ALL)
            assertEquals(SettingsRepositoryImpl.FEED_PROVIDER_ALL, awaitItem())
        }
    }

    @Test
    fun `feed sources come from the registry, so a plugin dashboard is selectable`() {
        val registry = DefaultProviderRegistry().apply { register(FakeDash()) }
        val vm = PreferencesViewModel(
            settings, effects, cache, audioOutput, RegionResolver(listOf { "mx" }), registry,
            FakeCanvasRepository(), FakeLosslessIndexSource(), dataSaverState(settings),
        )
        assertEquals(listOf("dash" to "Dash"), vm.feedSources.value.map { it.id to it.name })
    }

    @Test
    fun `a dashboard registered after this screen opened appears once the picker refreshes`() {
        // The real sequence: Settings → Plugins → install → back. This ViewModel outlives that trip.
        val registry = DefaultProviderRegistry()
        val vm = PreferencesViewModel(
            settings, effects, cache, audioOutput, RegionResolver(listOf { "mx" }), registry,
            FakeCanvasRepository(), FakeLosslessIndexSource(), dataSaverState(settings),
        )
        assertEquals(emptyList<String>(), vm.feedSources.value.map { it.id })

        registry.register(FakeDash())
        vm.refreshFeedSources()

        assertEquals(listOf("dash"), vm.feedSources.value.map { it.id })
    }

    /** Stands in for any dashboard provider — including one a JS plugin registered. */
    private class FakeDash : DashboardProvider {
        override val id = "dash"
        override val kind = ProviderKind.DASHBOARD
        override val name = "Dash"
        override val dashboardCapabilities = setOf(DashboardCapability.TOP_TRACKS)
        override suspend fun topTracks(limit: Int) = emptyList<Track>()
    }

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
    fun `the audio quality mode persists, re-emits, and exposes the DAC capability label`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val vm = vm()
            assertEquals("48 kHz output", vm.audioOutputLabel.value)
            vm.audioQuality.test {
                assertEquals(AudioQualityMode.STANDARD, awaitItem()) // opt-in, conservative by default
                vm.setAudioQuality(AudioQualityMode.BEST_AVAILABLE)
                assertEquals(AudioQualityMode.BEST_AVAILABLE, awaitItem())
            }
        }

    @Test
    fun `the lossless sub-settings default to wifi-only, no download, readout on`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val vm = vm()
            assertEquals(true, vm.losslessWifiOnly.value)
            assertEquals(false, vm.losslessDownload.value)
            assertEquals(true, vm.showTechnicalFormat.value)

            vm.losslessDownload.test {
                assertEquals(false, awaitItem())
                vm.setLosslessDownload(true)
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

}
