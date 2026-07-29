package fm.rizx.player.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.domain.plugin.InstalledPlugin
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
    /** Stable reason key when [status] is [StoreStatus.UNSUPPORTED] (localized by the UI). */
    val unsupportedReason: String? = null,
)

enum class StoreStatus { AVAILABLE, INSTALLING, INSTALLED, UNSUPPORTED, ERROR }

/** A downloaded plugin in the "Installed plugins" section. */
data class InstalledPluginRow(
    val id: String,
    val name: String,
    val version: String,
    val category: String,
    val enabled: Boolean,
    val quarantined: Boolean = false,
    /** The failure that caused the quarantine — shown on the row so "why did it turn off" is answerable. */
    val lastError: String = "",
    /** True when the plugin came from a registry (so re-installing its latest release is possible). */
    val canUpdate: Boolean = false,
)

data class PluginsUiState(
    val rows: List<PluginRow> = emptyList(),
    val store: List<StoreRow> = emptyList(),
    val installedPlugins: List<InstalledPluginRow> = emptyList(),
    val registries: List<String> = emptyList(),
    val storeError: String? = null,
    val sideloadBusy: Boolean = false,
)

/**
 * Backs the Plugins screen (ADR 0014/0019). Installed tab: built-in + plugin-backed providers (active
 * radio / enable toggle) plus the downloaded plugins with health, update and uninstall. Store tab: the
 * Nuclear registry merged with any user-added registries — every entry visible, the non-runnable ones
 * labeled with their reason instead of hidden — plus sideload (install from URL) and registry
 * management.
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
    private var installedCache: List<InstalledPlugin> = emptyList()
    private val installing = mutableSetOf<String>()

    init {
        refresh()
        observeInstalled()
        observeRegistries()
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
                installedCache = installed
                rebuildInstalled()
                snapshot()      // plugin providers may have (un)registered
                rebuildStore()
            }
        }
    }

    private fun observeRegistries() {
        viewModelScope.launch {
            plugins.registries.collect { urls -> _state.update { it.copy(registries = urls) } }
        }
    }

    fun loadStore() {
        viewModelScope.launch {
            runCatching { plugins.registry() }
                .onSuccess { registryCache = it; _state.update { s -> s.copy(storeError = null) }; rebuildInstalled(); rebuildStore() }
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

    /** Re-installs [id]'s latest release (settings preserved). Only offered for registry plugins. */
    fun update(id: String) {
        val entry = registryCache.firstOrNull { it.id == id } ?: return
        if (id in installing) return
        installing.add(id)
        rebuildStore()
        viewModelScope.launch {
            runCatching { plugins.update(entry) }
                .onFailure { e -> _state.update { it.copy(storeError = e.toSafeMessage("Update failed. Please try again.")) } }
            installing.remove(id)
            snapshot()
            rebuildStore()
        }
    }

    /** Sideload a plugin zip from a pasted URL. */
    fun installFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank() || _state.value.sideloadBusy) return
        _state.update { it.copy(sideloadBusy = true) }
        viewModelScope.launch {
            runCatching { plugins.installFromUrl(trimmed) }
                .onSuccess { _state.update { s -> s.copy(storeError = null) } }
                .onFailure { e -> _state.update { it.copy(storeError = e.toSafeMessage("Couldn't install from that URL")) } }
            _state.update { it.copy(sideloadBusy = false) }
            snapshot()
        }
    }

    fun addRegistry(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http")) return
        viewModelScope.launch {
            runCatching { plugins.addRegistry(trimmed) }
            loadStore()
        }
    }

    fun removeRegistry(url: String) {
        viewModelScope.launch {
            runCatching { plugins.removeRegistry(url) }
            loadStore()
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

    private fun rebuildInstalled() {
        val registryIds = registryCache.map { it.id }.toSet()
        _state.update {
            it.copy(
                installedPlugins = installedCache.map { p ->
                    InstalledPluginRow(
                        id = p.id, name = p.name, version = p.version, category = p.category,
                        enabled = p.enabled,
                        quarantined = p.isQuarantined,
                        lastError = p.lastError,
                        canUpdate = p.id in registryIds,
                    )
                },
            )
        }
    }

    private fun rebuildStore() {
        val installedIds = installedCache.map { it.id }.toSet()
        _state.update {
            it.copy(
                // Every registry entry is listed; the ones that can't run here say WHY instead of
                // vanishing — transparency beats a mysteriously short catalog (ADR 0019).
                store = registryCache.map { entry ->
                    StoreRow(
                        id = entry.id,
                        displayName = entry.name.ifBlank { entry.id.removePrefix("nuclear-plugin-").replaceFirstChar { c -> c.uppercase() } },
                        category = entry.category,
                        description = entry.description,
                        author = entry.author,
                        status = when {
                            !entry.isSupported -> StoreStatus.UNSUPPORTED
                            entry.id in installing -> StoreStatus.INSTALLING
                            entry.id in installedIds -> StoreStatus.INSTALLED
                            else -> StoreStatus.AVAILABLE
                        },
                        unsupportedReason = entry.unsupportedReason,
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
        ProviderKind.DASHBOARD, ProviderKind.PLAYLISTS, ProviderKind.DISCOVERY -> false
        else -> true
    }
}
