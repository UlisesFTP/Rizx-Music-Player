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
    /**
     * A stable reason key when the plugin cannot (or need not) run on Android, null when it can.
     * Shown in the store — visible-with-reason, never silently hidden (ADR 0019). With the runtime v2
     * (`api.Ytdlp` facade, persisted Settings/Storage, DOMParser) only the desktop MediaSession plugin
     * remains: Media3's session already does its job natively.
     */
    val unsupportedReason: String? get() = UNSUPPORTED_REASONS[id]

    val isSupported: Boolean get() = unsupportedReason == null

    companion object {
        const val REASON_NATIVE_EQUIVALENT = "native-equivalent"

        val UNSUPPORTED_REASONS = mapOf(
            "nuclear-plugin-mediasession" to REASON_NATIVE_EQUIVALENT,
        )
    }
}

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
