package fm.rizx.player.data.plugin

import android.util.Log
import fm.rizx.player.data.plugin.install.PluginInstaller
import fm.rizx.player.data.plugin.install.PluginRegistryClient
import fm.rizx.player.domain.plugin.InstalledPlugin
import fm.rizx.player.domain.plugin.PluginRepository
import fm.rizx.player.domain.plugin.RegistryPlugin
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Orchestrates the plugin lifecycle (ADR 0014): registry → download/extract ([PluginInstaller]) →
 * transpile+run ([JsPluginRuntime]) → persist ([InstalledPluginStore]). A failing plugin is isolated —
 * [reloadInstalled] wraps each load so one broken plugin can never block startup or the others, and then
 * re-applies the user's persisted active provider selection (plugins load *after* the registry's startup
 * reconcile, so a plugin chosen as the active metadata/streaming provider is restored here).
 */
class PluginRepositoryImpl(
    private val registryClient: PluginRegistryClient,
    private val installer: PluginInstaller,
    private val store: InstalledPluginStore,
    private val runtime: JsPluginRuntime,
    private val providerRegistry: ProviderRegistry,
    private val settings: SettingsRepository,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val nowIso: () -> String = { Instant.now().toString() },
) : PluginRepository {

    override suspend fun registry(): List<RegistryPlugin> = registryClient.fetch()

    override val installed: Flow<List<InstalledPlugin>> = store.installed

    override suspend fun install(entry: RegistryPlugin): InstalledPlugin {
        val extracted = installer.install(entry.id, entry.repo, entry.downloadUrl)
        runtime.loadTsPluginModules(entry.id, extracted.tsFiles, extracted.entryPath)
        val plugin = InstalledPlugin(
            id = entry.id,
            version = extracted.manifest.version,
            name = extracted.manifest.displayName.ifBlank { extracted.manifest.name },
            description = entry.description,
            author = extracted.manifest.author.ifBlank { entry.author },
            category = extracted.manifest.category.ifBlank { entry.category },
            dir = extracted.dir.absolutePath,
            entryPath = extracted.entryPath,
            enabled = true,
            installedAtIso = nowIso(),
        )
        store.upsert(plugin)
        return plugin
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        store.setEnabled(id, enabled)
        val plugin = store.snapshot().firstOrNull { it.id == id } ?: return
        if (enabled) {
            if (!runtime.isLoaded(id)) loadFromDisk(plugin)
        } else {
            runtime.unregisterPlugin(id)
        }
    }

    override suspend fun uninstall(id: String) {
        runtime.unregisterPlugin(id)
        store.snapshot().firstOrNull { it.id == id }?.let { File(it.dir).deleteRecursively() }
        store.remove(id)
    }

    override suspend fun reloadInstalled() {
        for (plugin in store.snapshot()) {
            if (!plugin.enabled || runtime.isLoaded(plugin.id)) continue
            runCatching { loadFromDisk(plugin) }
                .onFailure { Log.w("JsPlugin", "reload of '${plugin.id}' failed: ${it.message}") }
        }
        reapplyPersistedActive()
    }

    /**
     * Re-applies the persisted active metadata/streaming selection now that plugin providers are
     * registered. The registry's startup reconcile ran before plugins loaded, so a plugin chosen as the
     * active provider fell back to a native default; this restores the user's choice. A no-op when the
     * selection is null or already applied; on the main thread since the registry is single-threaded.
     */
    private suspend fun reapplyPersistedActive() = withContext(mainDispatcher) {
        settings.activeMetadataProviderId.first()?.let { id ->
            runCatching { providerRegistry.setActive(ProviderKind.METADATA, id) }
        }
        settings.activeStreamingProviderId.first()?.let { id ->
            runCatching { providerRegistry.setActive(ProviderKind.STREAMING, id) }
        }
    }

    private suspend fun loadFromDisk(plugin: InstalledPlugin) {
        val files = installer.readSources(File(plugin.dir))
        runtime.loadTsPluginModules(plugin.id, files, plugin.entryPath)
    }
}
