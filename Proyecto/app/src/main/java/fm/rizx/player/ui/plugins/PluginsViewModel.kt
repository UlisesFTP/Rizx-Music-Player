package fm.rizx.player.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.domain.plugin.PluginRepository
import fm.rizx.player.domain.plugin.RegistryPlugin
import fm.rizx.player.domain.provider.EnabledProviderStore
import fm.rizx.player.domain.provider.ProviderHealth
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.usecase.ProviderHealthProbe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One built-in/loaded provider row in the Installed tab. */
data class PluginRow(
    val id: String,
    val name: String,
    val version: String,
    val kind: ProviderKind,
    val singleActive: Boolean,
    val active: Boolean,
    val enabled: Boolean,
    val health: ProviderHealth,
    val fromPlugin: Boolean,
)

/** A downloadable plugin in the Store tab. */
data class StoreRow(
    val id: String,
    val displayName: String,
    val category: String,
    val description: String,
    val author: String,
    val status: StoreStatus,
)

enum class StoreStatus { AVAILABLE, INSTALLING, INSTALLED, ERROR }

/** A downloaded plugin in the "Installed plugins" section. */
data class InstalledPluginRow(val id: String, val name: String, val version: String, val category: String, val enabled: Boolean)

data class PluginsUiState(
    val rows: List<PluginRow> = emptyList(),
    val store: List<StoreRow> = emptyList(),
    val installedPlugins: List<InstalledPluginRow> = emptyList(),
    val storeError: String? = null,
)

/**
 * Backs the Plugins screen (ADR 0014). Installed tab: built-in + plugin-backed providers (active radio /
 * enable toggle). Store tab: the real Nuclear registry with Download/Install actions; installing a plugin
 * downloads + runs it and its providers appear in the Installed tab.
 */
@HiltViewModel
class PluginsViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val settings: SettingsRepository,
    private val enabled: EnabledProviderStore,
    private val probe: ProviderHealthProbe,
    private val plugins: PluginRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    private var registryCache: List<RegistryPlugin> = emptyList()
    private val installing = mutableSetOf<String>()

    init {
        refresh()
        observeInstalled()
        loadStore()
    }

    fun refresh() {
        viewModelScope.launch {
            snapshot()
            probeAll()
        }
    }

    private fun observeInstalled() {
        viewModelScope.launch {
            plugins.installed.collect { installed ->
                _state.update {
                    it.copy(
                        installedPlugins = installed.map { p ->
                            InstalledPluginRow(p.id, p.name, p.version, p.category, p.enabled)
                        },
                    )
                }
                snapshot()      // plugin providers may have (un)registered
                rebuildStore()
            }
        }
    }

    fun loadStore() {
        viewModelScope.launch {
            runCatching { plugins.registry() }
                .onSuccess { registryCache = it; _state.update { s -> s.copy(storeError = null) }; rebuildStore() }
                .onFailure { e -> _state.update { s -> s.copy(storeError = e.toSafeMessage("Couldn't load the plugin store")) } }
        }
    }

    fun install(id: String) {
        val entry = registryCache.firstOrNull { it.id == id } ?: return
        if (!entry.isSupported || id in installing) return
        installing.add(id)
        rebuildStore()
        viewModelScope.launch {
            runCatching { plugins.install(entry) }
                .onFailure { e -> _state.update { it.copy(storeError = e.toSafeMessage("Install failed. Please try again.")) } }
            installing.remove(id)
            snapshot()
            rebuildStore()
        }
    }

    fun setPluginEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { plugins.setEnabled(id, enabled) }
                .onFailure { e -> _state.update { it.copy(storeError = e.toSafeMessage("Couldn't update the plugin")) } }
            snapshot()
        }
    }

    fun uninstall(id: String) {
        viewModelScope.launch {
            runCatching { plugins.uninstall(id) }
                .onFailure { e -> _state.update { it.copy(storeError = e.toSafeMessage("Couldn't remove the plugin")) } }
            snapshot()
            rebuildStore()
        }
    }

    fun setActive(kind: ProviderKind, id: String) {
        runCatching { registry.setActive(kind, id) }
        viewModelScope.launch {
            when (kind) {
                ProviderKind.METADATA -> settings.setActiveMetadataProviderId(id)
                ProviderKind.STREAMING -> settings.setActiveStreamingProviderId(id)
                // Lyrics is single-active too, and with several sources to choose between the pick has
                // to survive a restart — otherwise it silently reverts to whichever registered first.
                ProviderKind.LYRICS -> settings.setActiveLyricsProviderId(id)
                else -> Unit
            }
            snapshot()
        }
    }

    fun setEnabled(id: String, on: Boolean) {
        viewModelScope.launch { enabled.setEnabled(id, on); snapshot() }
    }

    private fun rebuildStore() {
        val installedIds = _state.value.installedPlugins.map { it.id }.toSet()
        _state.update {
            it.copy(
                // Desktop-only plugins (yt-dlp subprocess, desktop media keys, a Last.fm login widget
                // Compose can't render) are hidden entirely rather than shown as a dead "Desktop only"
                // row — the store lists only what actually runs here. See RegistryPlugin.UNSUPPORTED.
                store = registryCache.filter { it.isSupported }.map { entry ->
                    StoreRow(
                        id = entry.id,
                        displayName = entry.name.ifBlank { entry.id.removePrefix("nuclear-plugin-").replaceFirstChar { c -> c.uppercase() } },
                        category = entry.category,
                        description = entry.description,
                        author = entry.author,
                        status = when {
                            entry.id in installing -> StoreStatus.INSTALLING
                            entry.id in installedIds -> StoreStatus.INSTALLED
                            else -> StoreStatus.AVAILABLE
                        },
                    )
                },
            )
        }
    }

    private suspend fun snapshot() {
        val providers = registry.list(null)
        val enabledMap = enabled.snapshot(providers.map { it.id })
        val existingHealth = _state.value.rows.associate { it.id to it.health }
        _state.update {
            it.copy(
                rows = providers.map { p ->
                    PluginRow(
                        id = p.id,
                        name = p.name,
                        version = p.version,
                        kind = p.kind,
                        singleActive = p.kind.isSingleActive(),
                        active = registry.getActive(p.kind) == p.id,
                        enabled = enabledMap[p.id] ?: true,
                        health = existingHealth[p.id] ?: ProviderHealth.Unknown,
                        fromPlugin = p.pluginId != null,
                    )
                },
            )
        }
    }

    private suspend fun probeAll() {
        val providers = registry.list(null)
        val results = providers.map { p -> viewModelScope.async { p.id to probe.probe(p) } }.awaitAll().toMap()
        _state.update { st -> st.copy(rows = st.rows.map { it.copy(health = results[it.id] ?: it.health) }) }
    }

    private fun ProviderKind.isSingleActive(): Boolean = when (this) {
        ProviderKind.DASHBOARD, ProviderKind.PLAYLISTS -> false
        else -> true
    }
}
