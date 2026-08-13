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
    /**
     * Optional SHA-256 (hex) of the plugin archive. The official Nuclear registry publishes none, so this
     * is nullable; when a registry DOES publish it, the installer verifies the download against it before
     * any plugin code runs. Absence never fails an install (verify-when-present, like a lossless index).
     */
    val sha256: String? = null,
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

        /**
         * Registry plugins that cannot do anything here — a different reason from
         * [REPLACED_BY_NATIVE], and deliberately a different list, because "you already have this" and
         * "this could never work" are not the same fact and one may stop being true without the other.
         *
         * A scrobbling plugin has two ways to reach the host and neither exists: `JsPluginRuntime`'s
         * `buildProvider` maps six `ProviderKind`s and `scrobbling` is not one of them, so the
         * descriptor is logged and dropped; and while `bootstrap.js` offers `api.Events.on`, nothing on
         * the Kotlin side ever calls `rizx.emit`, so no playback event is ever delivered. It would
         * install, report success, and silently scrobble nothing.
         *
         * Removing an id from here needs the missing half built first, not just the line deleted.
         */
        val NOT_RUNNABLE = setOf(
            "nuclear-plugin-lastfm",
        )

        /** Everything kept out of the store by id, whatever the reason. */
        val HIDDEN = REPLACED_BY_NATIVE + NOT_RUNNABLE

        /**
         * Categories `JsPluginRuntime.buildProvider` has no dispatcher for. A plugin in one of these
         * registers nothing and is never called, exactly like the ids in [NOT_RUNNABLE] — so this is
         * the same rule expressed generally, and a *future* scrobbling or discovery plugin is kept out
         * without anyone having to notice and add its id.
         *
         * `discovery` is here even though the runtime does build a provider for it: nothing in the app
         * ever calls one. The up-next engine is a closed set, so a discovery plugin installs, looks
         * healthy, and does nothing.
         */
        val UNDISPATCHABLE_CATEGORIES = setOf("scrobbling", "discovery")
    }

    /** Whether the store should list this entry at all. */
    val isHidden: Boolean
        get() = id in HIDDEN || category.trim().lowercase() in UNDISPATCHABLE_CATEGORIES
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
    /** The archive's own version, so a build shipping a newer one can replace what is installed. */
    val version: String = "",
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
    /** SHA-256 (hex) of the archive this was installed from — recorded on install (trust-on-first-use). */
    val archiveSha256: String = "",
) {
    val isQuarantined: Boolean get() = health == HEALTH_QUARANTINED

    companion object {
        const val HEALTH_QUARANTINED = "quarantined"
    }
}

/**
 * The plugin API this build implements, declared by a plugin as `"rizx": { "apiVersion": 1 }`.
 *
 * Additive on purpose: a plugin written for Nuclear declares nothing and keeps working exactly as
 * before. Declaring it is how a third-party plugin says which host contract it was written against, and
 * the only way the host can refuse one written for a *newer* Rizx instead of failing later in some
 * unrelated place. Bump this when the contract changes in a way an existing plugin could notice.
 */
const val RIZX_PLUGIN_API_VERSION = 1

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
    /** `rizx.apiVersion`, or null for a plugin that predates it (treated as legacy Nuclear). */
    val apiVersion: Int? = null,
)
