package fm.rizx.player.ui.player

import app.cash.turbine.test
import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.domain.model.ThemeMode
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Minimal [SettingsRepository] backing only the theme (the rest return inert defaults). */
    private class FakeSettings : SettingsRepository {
        val mode = MutableStateFlow(ThemeMode.SYSTEM)
        override val themeMode: Flow<ThemeMode> = mode
        override suspend fun setThemeMode(mode: ThemeMode) { this.mode.value = mode }
        override val activeMetadataProviderId: Flow<String?> = flowOf(null)
        override suspend fun setActiveMetadataProviderId(id: String?) {}
        override val activeStreamingProviderId: Flow<String?> = flowOf(null)
        override suspend fun setActiveStreamingProviderId(id: String?) {}
        override val streamExpiryMs: Flow<Long> = flowOf(0L)
        override suspend fun setStreamExpiryMs(ms: Long) {}
        override val streamResolutionRetries: Flow<Int> = flowOf(0)
        override suspend fun setStreamResolutionRetries(retries: Int) {}
        override val equalizerEnabled: Flow<Boolean> = flowOf(false)
        override suspend fun setEqualizerEnabled(enabled: Boolean) {}
        override val equalizerBandLevels: Flow<List<Int>> = flowOf(emptyList())
        override suspend fun setEqualizerBandLevels(levels: List<Int>) {}
        override val dataSaver: Flow<Boolean> = flowOf(false)
        override suspend fun setDataSaver(enabled: Boolean) {}
        override val crossfade: Flow<Boolean> = flowOf(false)
        override suspend fun setCrossfade(enabled: Boolean) {}
        override val gapless: Flow<Boolean> = flowOf(true)
        override suspend fun setGapless(enabled: Boolean) {}
        override val normalizeVolume: Flow<Boolean> = flowOf(false)
        override suspend fun setNormalizeVolume(enabled: Boolean) {}
        override val hiResOutput: Flow<Boolean> = flowOf(false)
        override suspend fun setHiResOutput(enabled: Boolean) {}
        override val canvasEnabled: Flow<Boolean> = flowOf(false)
        override suspend fun setCanvasEnabled(enabled: Boolean) {}
        override val syncedLyricsMode: Flow<Boolean> = flowOf(true)
        override suspend fun setSyncedLyricsMode(enabled: Boolean) {}
        override val audioCacheBytes: Flow<Long> = flowOf(0L)
        override suspend fun setAudioCacheBytes(bytes: Long) {}
    }

    private lateinit var settings: FakeSettings
    private lateinit var vm: PlayerViewModel

    @Before
    fun setup() {
        settings = FakeSettings()
        vm = PlayerViewModel(settings)
    }

    // --- Synchronous state/setters: `_state` updates are immediate, no time advancement needed. ---

    @Test
    fun `starts playing, theme follows the system by default`() {
        assertTrue(vm.state.value.isPlaying)
        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)
    }

    @Test
    fun `togglePlay flips playback`() {
        assertTrue(vm.state.value.isPlaying)
        vm.togglePlay()
        assertFalse(vm.state.value.isPlaying)
        vm.togglePlay()
        assertTrue(vm.state.value.isPlaying)
    }

    @Test
    fun `setThemeMode persists and mirrors the stored theme`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            advanceUntilIdle() // let the initial themeMode collection settle (SYSTEM)
            assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)

            vm.setThemeMode(ThemeMode.DARK)
            advanceUntilIdle()

            assertEquals(ThemeMode.DARK, vm.themeMode.value)
            assertEquals(ThemeMode.DARK, settings.mode.value) // persisted
        }

    @Test
    fun `toggleLikeNp flips the like flag`() {
        val before = vm.state.value.likedNp
        vm.toggleLikeNp()
        assertEquals(!before, vm.state.value.likedNp)
    }

    @Test
    fun `seekTo clamps to 0_1 range`() {
        vm.seekTo(2f)
        assertEquals(1f, vm.state.value.progress, 0f)
        vm.seekTo(-1f)
        assertEquals(0f, vm.state.value.progress, 0f)
        vm.seekTo(0.5f)
        assertEquals(0.5f, vm.state.value.progress, 0f)
    }

    // --- The subscription-gated ticker only runs while `state` is observed. Shared scheduler
    //     (mainDispatcherRule.dispatcher.scheduler) lets us advance its virtual time precisely. ---

    @Test
    fun `progress advances about 1Hz while observed and playing`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            vm.state.test {
                val start = awaitItem().progress // initial 0.42
                advanceTimeBy(1_001)
                runCurrent()
                assertTrue("progress should advance after ~1s", awaitItem().progress > start)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `progress does not advance while paused`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            vm.togglePlay() // pause
            vm.state.test {
                val start = awaitItem().progress
                advanceTimeBy(3_001)
                runCurrent()
                expectNoEvents() // paused -> ticker makes no state change
                assertEquals(start, vm.state.value.progress, 0f)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
