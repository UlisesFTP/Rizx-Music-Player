package fm.rizx.player.domain.plugin

import kotlinx.coroutines.flow.Flow

/**
 * Downloads, installs, enables/disables and removes Nuclear plugins (ADR 0014). Installing runs the
 * plugin in the sandboxed runtime and bridges its providers into the app registry; the set of installed
 * plugins is persisted and reloaded at startup.
 */
interface PluginRepository {
    /** The official plugin registry (network; cached). */
    suspend fun registry(): List<RegistryPlugin>

    /** Plugins installed on this device (reactive). */
    val installed: Flow<List<InstalledPlugin>>

    /** Downloads [entry]'s latest release, installs it, and enables it. */
    suspend fun install(entry: RegistryPlugin): InstalledPlugin

    /** Enables (loads) or disables (unregisters) an installed plugin, persisting the choice. */
    suspend fun setEnabled(id: String, enabled: Boolean)

    /** Unregisters, deletes files, and forgets an installed plugin. */
    suspend fun uninstall(id: String)

    /** Loads every enabled installed plugin (call once at app start). Each is isolated. */
    suspend fun reloadInstalled()
}
