package fm.rizx.player.ui.plugins

import fm.rizx.player.FakeSettingsRepository
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
import fm.rizx.player.domain.model.LyricsVisualQuality
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

    private class NoopPlugins : PluginRepository {
        override suspend fun registry(): List<RegistryPlugin> = emptyList()
        override val installed: Flow<List<InstalledPlugin>> = flowOf(emptyList())
        override suspend fun install(entry: RegistryPlugin): InstalledPlugin = error("not installable in test")
        override suspend fun update(entry: RegistryPlugin): InstalledPlugin = error("not installable in test")
        override suspend fun installFromUrl(url: String): InstalledPlugin = error("not installable in test")
        override fun bundled(): List<fm.rizx.player.domain.plugin.BundledPlugin> = emptyList()
        override suspend fun installBundled(assetName: String): InstalledPlugin = error("not installable in test")
        override suspend fun setEnabled(id: String, enabled: Boolean) {}
        override suspend fun uninstall(id: String) {}
        override suspend fun reloadInstalled() {}
        override val registries: Flow<List<String>> = flowOf(emptyList())
        override suspend fun addRegistry(url: String) {}
        override suspend fun removeRegistry(url: String) {}
    }

    private fun vm(enabled: FakeEnabled = FakeEnabled()) = PluginsViewModel(
        DefaultProviderRegistry().apply { register(FakeMetadataProvider()); register(FakeDash()) },
        FakeSettingsRepository(),
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
