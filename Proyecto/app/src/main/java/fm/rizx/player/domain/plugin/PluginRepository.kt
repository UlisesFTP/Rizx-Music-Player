package fm.rizx.player.domain.plugin

import kotlinx.coroutines.flow.Flow

/**
 * Downloads, installs, enables/disables and removes Nuclear plugins (ADR 0014/0019). Installing runs
 * the plugin in the sandboxed runtime and bridges its providers into the app registry; the set of
 * installed plugins is persisted and reloaded at startup.
 */
interface PluginRepository {
    /** The official registry, merged with any user-added registries (network; cached). */
    suspend fun registry(): List<RegistryPlugin>

    /** Plugins installed on this device (reactive). */
    val installed: Flow<List<InstalledPlugin>>

    /** Downloads [entry]'s latest release, installs it, and enables it. */
    suspend fun install(entry: RegistryPlugin): InstalledPlugin

    /**
     * Re-installs [entry] at its latest release, preserving the plugin's settings/storage.
     * Same pipeline as [install] — exposed separately so callers say what they mean.
     */
    suspend fun update(entry: RegistryPlugin): InstalledPlugin

    /** Sideload: install a plugin zip from a pasted URL; the id comes from its own manifest. */
    suspend fun installFromUrl(url: String): InstalledPlugin

    /** Plugin archives shipped inside the APK. Empty when the build carries none. */
    fun bundled(): List<BundledPlugin>

    /** Installs one of [bundled] straight from the APK — no network, no URL to paste. */
    suspend fun installBundled(assetName: String): InstalledPlugin

    /**
     * Installs the bundled archives that ship with this build, once each (call at app start, before
     * [reloadInstalled]). Shipping a plugin inside the APK and then asking the user to go and find it
     * in a store is a step that carries no decision.
     *
     * Installs an archive the first time it is seen and re-installs one whose bundled version differs
     * from what is on disk. **Never resurrects one the user uninstalled** — that is remembered
     * separately from the installed list, which uninstalling empties.
     */
    suspend fun seedBundled()

    /** Enables (loads) or disables (unregisters) an installed plugin, persisting the choice. */
    suspend fun setEnabled(id: String, enabled: Boolean)

    /** Unregisters, deletes files, and forgets an installed plugin. */
    suspend fun uninstall(id: String)

    /** Loads every enabled installed plugin (call once at app start). Each is isolated. */
    suspend fun reloadInstalled()

    /**
     * Tears the JS runtime down and reloads the enabled plugins.
     *
     * Every plugin shares one engine thread and the sandbox has no way to interrupt running JS, so a
     * plugin stuck in a loop takes the whole subsystem with it. This is the way out that does not
     * involve force-stopping the app. Quarantined plugins stay off — they are disabled on disk.
     */
    suspend fun restartRuntime()

    /** User-added registry URLs (reactive). The official registry is implicit. */
    val registries: Flow<List<String>>

    suspend fun addRegistry(url: String)

    suspend fun removeRegistry(url: String)
}
