package fm.rizx.player.ui.plugins

import fm.rizx.player.FakeSettingsRepository
import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.plugin.BundledPlugin
import fm.rizx.player.domain.plugin.InstalledPlugin
import fm.rizx.player.domain.plugin.PluginRepository
import fm.rizx.player.domain.plugin.RegistryPlugin
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.usecase.ProviderHealthProbe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * What the Store tab is allowed to say about an install.
 *
 * All of these come from one report: *"it says Install failed even though the plugin installed"*. There
 * were three separate ways to reach that screen, and each gets a test here:
 *
 * 1. Nothing ever cleared the message, so one failed attempt captioned every later success.
 * 2. `runCatching` swallows `CancellationException`, so leaving the screen mid-install "failed".
 * 3. The verdict came from the exception alone, never from whether the plugin was actually there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginInstallReportingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeEnabled : EnabledProviderStore {
        override fun isEnabled(id: String): Flow<Boolean> = flowOf(true)
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun snapshot(ids: Collection<String>) = ids.associateWith { true }
    }

    private val entry = RegistryPlugin(id = "nuclear-plugin-discogs", repo = "nukeop/x", name = "Discogs")

    private class FakePlugins(
        private val bundledEntries: List<BundledPlugin> = emptyList(),
    ) : PluginRepository {
        val installedState = MutableStateFlow<List<InstalledPlugin>>(emptyList())
        var registryEntries: List<RegistryPlugin> = emptyList()

        /** What the next install does. Returning normally installs; throwing does not. */
        var onInstall: suspend (String) -> Unit = {}

        override suspend fun registry(): List<RegistryPlugin> = registryEntries
        override val installed: Flow<List<InstalledPlugin>> = installedState
        override val registries: Flow<List<String>> = flowOf(emptyList())

        override suspend fun install(entry: RegistryPlugin): InstalledPlugin {
            onInstall(entry.id)
            return record(entry.id)
        }

        override suspend fun update(entry: RegistryPlugin): InstalledPlugin {
            onInstall(entry.id)
            return record(entry.id)
        }

        override suspend fun installBundled(assetName: String): InstalledPlugin {
            onInstall(assetName)
            return record(bundledEntries.first { it.assetName == assetName }.id)
        }

        override suspend fun installFromUrl(url: String): InstalledPlugin {
            onInstall(url)
            return record("sideloaded")
        }

        /** Persisting is the step that makes a plugin "installed" — the same one the real repository
         *  performs last, after the runtime has loaded it. */
        fun record(id: String): InstalledPlugin {
            val plugin = InstalledPlugin(id = id, version = "1", name = id, dir = "/d", entryPath = "src/index")
            installedState.value = installedState.value.filterNot { it.id == id } + plugin
            return plugin
        }

        override fun bundled(): List<BundledPlugin> = bundledEntries
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun uninstall(id: String) = Unit
        override suspend fun reloadInstalled() = Unit
        override suspend fun addRegistry(url: String) = Unit
        override suspend fun removeRegistry(url: String) = Unit
    }

    private fun vm(plugins: FakePlugins) = PluginsViewModel(
        DefaultProviderRegistry(),
        FakeSettingsRepository(),
        FakeEnabled(),
        ProviderHealthProbe(),
        plugins,
    )

    @Test
    fun `a failed install says so`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val plugins = FakePlugins().apply {
            registryEntries = listOf(entry)
            onInstall = { error("boom") }
        }
        val vm = vm(plugins)
        advanceUntilIdle()

        vm.install(entry.id)
        advanceUntilIdle()

        assertNotNull(vm.state.value.storeError)
    }

    @Test
    fun `a later success clears the earlier failure`() = runTest(mainDispatcherRule.dispatcher.scheduler) {
        val plugins = FakePlugins().apply {
            registryEntries = listOf(entry)
            onInstall = { error("boom") }
        }
        val vm = vm(plugins)
        advanceUntilIdle()
        vm.install(entry.id)
        advanceUntilIdle()
        assertNotNull(vm.state.value.storeError)

        plugins.onInstall = {}
        vm.install(entry.id)
        advanceUntilIdle()

        assertNull("a retry that worked must not keep the old message", vm.state.value.storeError)
    }

    @Test
    fun `a failure that nonetheless installed is not reported as a failure`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val plugins = FakePlugins().apply {
                registryEntries = listOf(entry)
                // Persisted, then something later threw — the exact shape of "it installed and it says
                // it failed". The screen has to believe the list, not the exception.
                onInstall = { id -> record(id); error("late failure") }
            }
            val vm = vm(plugins)
            advanceUntilIdle()

            vm.install(entry.id)
            advanceUntilIdle()

            assertNull(vm.state.value.storeError)
            assertEquals(StoreStatus.INSTALLED, vm.state.value.store.single().status)
        }

    @Test
    fun `a bundled install is checked against the manifest id, not the file name`() =
        runTest(mainDispatcherRule.dispatcher.scheduler) {
            val bundled = BundledPlugin(
                assetName = "rizx-lossless.zip",
                id = "rizx-community-flac",
                name = "Community FLAC",
                description = "",
                category = "lossless",
            )
            val plugins = FakePlugins(listOf(bundled)).apply {
                onInstall = { record("rizx-community-flac"); error("late failure") }
            }
            val vm = vm(plugins)
            advanceUntilIdle()

            vm.installBundled("rizx-lossless.zip")
            advanceUntilIdle()

            assertNull(vm.state.value.storeError)
            assertEquals(StoreStatus.INSTALLED, vm.state.value.bundled.single().status)
        }
}
