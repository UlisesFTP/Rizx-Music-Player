package fm.rizx.player.domain.plugin

import kotlinx.serialization.Serializable

/** The registry file: `{ "$schema": ..., "plugins": [...], "version": ... }`. */
@Serializable
data class RegistryFile(
    val plugins: List<RegistryPlugin> = emptyList(),
)

/** An entry in Nuclear's plugin registry (`plugins.json`). */
@Serializable
data class RegistryPlugin(
    val id: String,
    val repo: String,
    val category: String = "other",
    val description: String = "",
    val author: String = "",
    val name: String = "",
    val version: String = "",
    /** Direct release asset URL when the registry provides one (skips the GitHub releases API). */
    val downloadUrl: String? = null,
) {
    companion object {
        /**
         * Registry plugins Rizx does not list, because it already does the same job natively — and does
         * it better, being written against Android rather than bridged into it.
         *
         * **Hidden rather than shown-with-a-reason**, which is a deliberate reversal of ADR 0019's rule.
         * That rule was written for plugins that genuinely *cannot* run here, where saying so is useful.
         * These can run; they would just install a second, worse YouTube next to the native one and then
         * compete with it in the streaming chain. A store row whose honest caption is "you already have
         * this, don't" is not information, it is clutter.
         *
         * A plugin removed from this set reappears in the store with no other change.
         */
        val REPLACED_BY_NATIVE = setOf(
            // Media3's session already exposes playback to the system controls.
            "nuclear-plugin-mediasession",
            // ADR 0014: native full-length YouTube audio via NewPipe, at the top of the streaming chain.
            "nuclear-plugin-youtube",
            // Native SoundCloud, also NewPipe, including the Underground search tab.
            "nuclear-plugin-soundcloud",
            // `DeezerDashboardProvider` fills the Home feed natively.
            "nuclear-plugin-deezer-dashboard",
            // Fanning a search across sources is what `StreamingRepositoryImpl`'s fallback chain is.
            "nuclear-plugin-omnisource",
            // Rizx imports YouTube playlists by URL natively, and paginates them (NewPipe 0.26.4).
            "nuclear-plugin-youtube-playlists",
        )
    }
}

/**
 * A plugin archive shipped inside the APK, installable with no network.
 *
 * [assetName] is the handle the installer needs; [id] is only for the row's identity in the list, since
 * the real id is settled by the manifest during installation.
 */
data class BundledPlugin(
    val assetName: String,
    val id: String,
    val name: String,
    val description: String = "",
    val category: String = "other",
)

/** A plugin that has been downloaded, transpiled and (optionally) enabled on this device. */
@Serializable
data class InstalledPlugin(
    val id: String,
    val version: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val category: String = "other",
    /** Absolute directory holding the extracted plugin. */
    val dir: String,
    /** Extension-less module path of the entry (from `package.json` `main`), e.g. `src/index`. */
    val entryPath: String,
    val enabled: Boolean = true,
    val installedAtIso: String = "",
    /** `""` = fine; [HEALTH_QUARANTINED] = auto-disabled after repeated failures (ADR 0019). */
    val health: String = "",
    /** The failure that caused [health], shown on the plugin's row. Safe text, never a URL. */
    val lastError: String = "",
) {
    val isQuarantined: Boolean get() = health == HEALTH_QUARANTINED

    companion object {
        const val HEALTH_QUARANTINED = "quarantined"
    }
}

/** Parsed `package.json` for a plugin. */
@Serializable
data class PluginManifest(
    val name: String,
    val version: String = "0.0.0",
    val description: String = "",
    val author: String = "",
    val main: String = "src/index.ts",
    val category: String = "other",
    val displayName: String = "",
)
