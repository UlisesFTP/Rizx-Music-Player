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
    /** Registry plugins we cannot run on Android (yt-dlp / desktop / unsupported kind). */
    val isSupported: Boolean get() = id !in UNSUPPORTED

    companion object {
        val UNSUPPORTED = setOf(
            "nuclear-plugin-youtube",      // audio via yt-dlp subprocess — native YouTube provider instead
            "nuclear-plugin-omnisource",   // fans out to yt-dlp YouTube
            "nuclear-plugin-mediasession", // desktop media keys
            "nuclear-plugin-lastfm",       // scrobbling kind (not modelled)
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
    /** Extension-less module path of the entry (from `package.json` `main`), e.g. `index`. */
    val entryPath: String,
    val enabled: Boolean = true,
    val installedAtIso: String = "",
)

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
