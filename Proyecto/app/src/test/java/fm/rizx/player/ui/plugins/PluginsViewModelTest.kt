package fm.rizx.player.ui.plugins

import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.data.provider.FakeMetadataProvider
import fm.rizx.player.domain.model.DashboardCapability
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.DashboardProvider
import fm.rizx.player.domain.plugin.InstalledPlugin
import fm.rizx.player.domain.plugin.PluginRepository
import fm.rizx.player.domain.plugin.RegistryPlugin
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.model.RadioMode
import fm.rizx.player.domain.model.ThemeMode
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.usecase.ProviderHealthProbe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeDash : DashboardProvider {
        override val id = "dash"
        override val kind = ProviderKind.DASHBOARD
        override val name = "Dash"
        override val dashboardCapabilities = setOf(DashboardCapability.TOP_TRACKS)
        override suspend fun topTracks(limit: Int): List<Track> = emptyList()
    }

    private class FakeEnabled : EnabledProviderStore {
        val map = mutableMapOf<String, Boolean>()
        override fun isEnabled(id: String): Flow<Boolean> = flowOf(map[id] ?: true)
        override suspend fun setEnabled(id: String, enabled: Boolean) { map[id] = enabled }
        override suspend fun snapshot(ids: Collection<String>) = ids.associateWith { map[it] ?: true }
    }

    private class NoopSettings : SettingsRepository {
        override val themeMode: Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override val activeMetadataProviderId: Flow<String?> = flowOf(null)
        override suspend fun setActiveMetadataProviderId(id: String?) {}
        override val activeStreamingProviderId: Flow<String?> = flowOf(null)
        override suspend fun setActiveStreamingProviderId(id: String?) {}
        override val activeLyricsProviderId: Flow<String?> = flowOf(null)
        override suspend fun setActiveLyricsProviderId(id: String?) {}
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
        override val recsRegionalConsent: Flow<Boolean?> = flowOf(null)
        override suspend fun setRecsRegionalConsent(consented: Boolean) {}
        override val radioAlgorithm: Flow<RadioMode> = flowOf(RadioMode.YOUTUBE)
        override suspend fun setRadioAlgorithm(mode: RadioMode) {}
    }

    private class NoopPlugins : PluginRepository {
        override suspend fun registry(): List<RegistryPlugin> = emptyList()
        override val installed: Flow<List<InstalledPlugin>> = flowOf(emptyList())
        override suspend fun install(entry: RegistryPlugin): InstalledPlugin = error("not installable in test")
        override suspend fun setEnabled(id: String, enabled: Boolean) {}
        override suspend fun uninstall(id: String) {}
        override suspend fun reloadInstalled() {}
    }

    private fun vm(enabled: FakeEnabled = FakeEnabled()) = PluginsViewModel(
        DefaultProviderRegistry().apply { register(FakeMetadataProvider()); register(FakeDash()) },
        NoopSettings(),
        enabled,
        ProviderHealthProbe(),
        NoopPlugins(),
    )

    @Test
    fun `builds rows with version, active radio for single-active and toggle for fan-out`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val vm = vm()
        advanceUntilIdle()

        val rows = vm.state.value.rows
        val meta = rows.first { it.kind == ProviderKind.METADATA }
        val dash = rows.first { it.kind == ProviderKind.DASHBOARD }

        assertTrue(meta.singleActive)
        assertTrue(meta.active)          // first-wins active
        assertFalse(dash.singleActive)   // fan-out → toggle
        assertTrue(dash.enabled)
        assertEquals("1.0", meta.version)
    }

    @Test
    fun `disabling a fan-out provider persists and reflects in state`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val enabled = FakeEnabled()
        val vm = vm(enabled)
        advanceUntilIdle()

        vm.setEnabled("dash", false)
        advanceUntilIdle()

        assertEquals(false, enabled.map["dash"])
        assertFalse(vm.state.value.rows.first { it.id == "dash" }.enabled)
    }
}
