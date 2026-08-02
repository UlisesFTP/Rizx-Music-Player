package fm.rizx.player.data.plugin

import android.util.Log
import fm.rizx.player.data.plugin.install.PluginInstaller
import fm.rizx.player.data.plugin.install.PluginRegistryClient
import fm.rizx.player.domain.plugin.BundledPlugin
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
    private val kv: PluginKvStore? = null,
    /** Archives shipped in the APK. Null in tests and in a build that carries none. */
    private val bundledPlugins: fm.rizx.player.data.plugin.install.BundledPlugins? = null,
) : PluginRepository {

    init {
        // The runtime detects the misbehaviour; this side makes it stick: providers unregistered
        // (runtime already did), plugin disabled with the failure on record. Re-enabling clears it.
        runtime.onQuarantine = { id, lastError ->
            runCatching { store.setHealth(id, InstalledPlugin.HEALTH_QUARANTINED, lastError) }
        }
    }

    override suspend fun registry(): List<RegistryPlugin> = registryClient.fetch(store.registriesSnapshot())

    override val installed: Flow<List<InstalledPlugin>> = store.installed

    override val registries: Flow<List<String>> = store.registries

    override suspend fun addRegistry(url: String) {
        store.addRegistry(url)
    }

    override suspend fun removeRegistry(url: String) {
        store.removeRegistry(url)
    }

    override suspend fun install(entry: RegistryPlugin): InstalledPlugin {
        val extracted = installer.install(entry.id, entry.repo, entry.downloadUrl)
        return loadAndPersist(entry.id, extracted, fallbackDescription = entry.description, fallbackAuthor = entry.author, fallbackCategory = entry.category)
    }

    override suspend fun update(entry: RegistryPlugin): InstalledPlugin {
        // Same pipeline: the installer preserves settings.json/storage.json across the re-install,
        // and the runtime replaces the loaded module graph wholesale.
        runtime.unregisterPlugin(entry.id)
        return install(entry)
    }

    override fun bundled(): List<BundledPlugin> = bundledPlugins?.list().orEmpty()

    /**
     * Reuses the installer's existing zip path, which was written and then never called from anywhere —
     * the only sideload the UI offered was by URL, which needs somewhere public to host the archive.
     */
    override suspend fun installBundled(assetName: String): InstalledPlugin {
        val plugins = requireNotNull(bundledPlugins) { "no bundled plugins in this build" }
        val extracted = plugins.open(assetName).use { installer.installFromZip(it, origin = "bundled:$assetName") }
        val pluginId = extracted.dir.name
        runtime.unregisterPlugin(pluginId)
        return loadAndPersist(pluginId, extracted)
    }

    override suspend fun installFromUrl(url: String): InstalledPlugin {
        val extracted = installer.installFromUrl(url)
        val pluginId = extracted.dir.name
        runtime.unregisterPlugin(pluginId) // re-sideload of an existing id replaces it
        return loadAndPersist(pluginId, extracted)
    }

    private suspend fun loadAndPersist(
        pluginId: String,
        extracted: fm.rizx.player.data.plugin.install.ExtractedPlugin,
        fallbackDescription: String = "",
        fallbackAuthor: String = "",
        fallbackCategory: String = "",
    ): InstalledPlugin {
        runtime.clearQuarantine(pluginId)
        runtime.loadTsPluginModules(
            pluginId, extracted.sources, extracted.entryPath,
            version = extracted.manifest.version, cacheDir = extracted.dir,
        )
        val plugin = InstalledPlugin(
            id = pluginId,
            version = extracted.manifest.version,
            name = extracted.manifest.displayName.ifBlank { extracted.manifest.name },
            description = extracted.manifest.description.ifBlank { fallbackDescription },
            author = extracted.manifest.author.ifBlank { fallbackAuthor },
            category = extracted.manifest.category.ifBlank { fallbackCategory.ifBlank { "other" } },
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
            runtime.clearQuarantine(id)
            if (!runtime.isLoaded(id)) loadFromDisk(plugin)
        } else {
            runtime.unregisterPlugin(id)
        }
    }

    override suspend fun uninstall(id: String) {
        runtime.unregisterPlugin(id)
        store.snapshot().firstOrNull { it.id == id }?.let { File(it.dir).deleteRecursively() }
        kv?.evict(id) // drop the in-memory settings/storage mirror with the files
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
        settings.activeLyricsProviderId.first()?.let { id ->
            runCatching { providerRegistry.setActive(ProviderKind.LYRICS, id) }
        }
    }

    private suspend fun loadFromDisk(plugin: InstalledPlugin) {
        val files = installer.readSources(File(plugin.dir))
        runtime.loadTsPluginModules(
            plugin.id, files, plugin.entryPath,
            version = plugin.version, cacheDir = File(plugin.dir),
        )
    }
}
