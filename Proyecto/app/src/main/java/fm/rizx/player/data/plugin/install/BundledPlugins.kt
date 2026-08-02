package fm.rizx.player.data.plugin.install

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.rizx.player.domain.plugin.BundledPlugin
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plugin archives shipped inside the APK, installable with one tap and no network.
 *
 * **The reason this exists is where a plugin's pointer lives.** A registry entry needs a public URL to
 * the zip, and for a plugin whose whole content is "here is a list of files" that URL is the thing the
 * repository deliberately does not publish. Bundling the archive keeps the store entry and leaves the
 * public source tree carrying nothing: `app/src/main/assets/plugins/` is git-ignored, so a clone simply
 * builds an app with no bundled plugins and no section for them.
 *
 * The manifest is read straight out of the archive, so the row shows the plugin's own name and
 * description rather than something restated here.
 */
@Singleton
class BundledPlugins @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Every `.zip` under `assets/plugins/`, described by its own `package.json`. Empty is normal. */
    fun list(): List<BundledPlugin> = runCatching {
        context.assets.list(ASSET_DIR).orEmpty()
            .filter { it.endsWith(".zip", ignoreCase = true) }
            .mapNotNull { describe(it) }
            .sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    /** Opens [assetName] for the installer. The caller closes it. */
    fun open(assetName: String): InputStream = context.assets.open("$ASSET_DIR/$assetName")

    /**
     * Reads the archive's `package.json` without extracting anything.
     *
     * A malformed archive is skipped rather than surfaced: a plugin nobody can install is not worth a
     * row explaining itself, and this runs while the store is being drawn.
     */
    private fun describe(assetName: String): BundledPlugin? = runCatching {
        val json = ZipInputStream(open(assetName)).use { zip ->
            generateSequence { zip.nextEntry }
                .firstOrNull { it.name.substringAfterLast('/') == "package.json" }
                ?.let { zip.readBytes().decodeToString() }
        } ?: return null
        BundledPlugin(
            assetName = assetName,
            // Normalised the way the installer will normalise it, or the row compares a manifest's raw
            // name against a directory name and shows "Install" for something already installed.
            id = PluginInstaller.pluginIdFor(json.stringField("name") ?: assetName.removeSuffix(".zip")),
            name = json.stringField("displayName") ?: json.stringField("name") ?: assetName,
            description = json.stringField("description").orEmpty(),
            category = json.stringField("category") ?: "other",
        )
    }.getOrNull()

    /**
     * One field out of a small, known JSON document.
     *
     * A regex rather than the serializer because `package.json` is read here only to label a row, and a
     * plugin whose manifest has an unexpected shape should still be installable — the installer parses
     * it properly a moment later, and that is the parse that has to be strict.
     *
     * Flat over the whole document on purpose: `category` sits under `nuclear` in a Nuclear manifest and
     * at the top level in others, and for a label either will do.
     */
    private fun String.stringField(key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(this)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private companion object {
        const val ASSET_DIR = "plugins"
    }
}
