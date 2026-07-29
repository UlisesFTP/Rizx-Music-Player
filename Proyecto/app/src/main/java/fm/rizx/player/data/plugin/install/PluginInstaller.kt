package fm.rizx.player.data.plugin.install

import fm.rizx.player.core.error.AppError
import fm.rizx.player.domain.plugin.PluginManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** What language/shape a collected plugin source file is, decided by its extension. */
enum class PluginSourceKind { TS, TSX, JS, JSON }

/** One plugin source file: its [code] and how the loader must treat it. */
data class PluginSourceFile(val code: String, val kind: PluginSourceKind)

/** A plugin's source, extracted and ready to transpile+load. */
data class ExtractedPlugin(
    val manifest: PluginManifest,
    val dir: File,
    /** Extension-less path relative to the plugin root (e.g. `src/index`, `dist/index`) → source. */
    val sources: Map<String, PluginSourceFile>,
    val entryPath: String,
)

/**
 * Downloads a Nuclear plugin's latest GitHub release, extracts it to app storage, and reads its
 * sources (ADR 0014/0019). No code runs here — extraction is guarded against zip-slip and size
 * blowups; running is the runtime's job. Unauthenticated GitHub API (60 req/h) is plenty for personal use.
 *
 * Layout handling: registry releases ship **flat** `plugin.zip` files (`src/… package.json` at the
 * root), while GitHub zipballs wrap everything in a `repo-sha/` directory — so the wrapper is stripped
 * only when the archive's sole top-level directory is the one holding `package.json`.
 */
class PluginInstaller(
    private val client: OkHttpClient,
    private val json: Json,
    private val pluginsRoot: File,
) {
    suspend fun install(pluginId: String, repo: String, downloadUrl: String? = null): ExtractedPlugin = withContext(Dispatchers.IO) {
        val assetUrl = downloadUrl ?: resolveAssetUrl(repo)
        val zipBytes = download(assetUrl)
        installFromZipBytes(pluginId, zipBytes, origin = repo)
    }

    /** Sideload: download a plugin zip from a pasted [url]; the id comes from its own manifest. */
    suspend fun installFromUrl(url: String): ExtractedPlugin = withContext(Dispatchers.IO) {
        installAutoId(download(url), origin = url)
    }

    /** Sideload from a picked file/stream; the id comes from the zip's own manifest. */
    suspend fun installFromZip(zip: InputStream, origin: String = "sideload"): ExtractedPlugin =
        withContext(Dispatchers.IO) {
            val bytes = zip.readBytes()
            if (bytes.size > MAX_ZIP_BYTES) throw AppError.ProviderFailure("PluginInstaller", "plugin archive too large")
            installAutoId(bytes, origin)
        }

    /**
     * Sideloads don't know their id upfront — extract to a scratch dir just to read `package.json`,
     * then run the normal install (which preserves an existing install's settings/storage) under the
     * manifest-derived id.
     */
    private fun installAutoId(zipBytes: ByteArray, origin: String): ExtractedPlugin {
        val tmp = File(pluginsRoot, ".tmp-sideload")
        try {
            tmp.deleteRecursively(); tmp.mkdirs()
            extract(zipBytes, tmp)
            unwrapZipball(tmp)
            val manifest = readManifest(tmp)
            val pluginId = manifest.name.lowercase().replace(ID_UNSAFE, "-").trim('-')
            if (pluginId.isBlank()) throw AppError.ProviderFailure("PluginInstaller", "plugin has no usable name")
            return installFromZipBytes(pluginId, zipBytes, origin)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun installFromZipBytes(pluginId: String, zipBytes: ByteArray, origin: String): ExtractedPlugin {
        val dir = File(pluginsRoot, pluginId)
        val keepDirs = listOf(File(dir, SETTINGS_FILE), File(dir, STORAGE_FILE))
        val kept = keepDirs.mapNotNull { f -> f.takeIf { it.exists() }?.let { it.name to it.readBytes() } }
        dir.deleteRecursively(); dir.mkdirs()
        try {
            extract(zipBytes, dir)
            unwrapZipball(dir)
            val manifest = readManifest(dir)
            val entryPath = normalize(manifest.main)
            val sources = collectSources(dir)
            resolveEntry(sources, entryPath)
                ?: throw AppError.ProviderFailure("PluginInstaller", "entry '$entryPath' not found in $origin")
            checkBareDependencies(sources)
            // Restore per-plugin settings/storage so an update never loses a plugin's tokens/state.
            for ((name, bytes) in kept) File(dir, name).writeBytes(bytes)
            return ExtractedPlugin(manifest, dir, sources, entryPath)
        } catch (e: Throwable) {
            dir.deleteRecursively() // installs are atomic
            throw e
        }
    }

    private fun resolveAssetUrl(repo: String): String {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { response ->
            if (response.code == 403) throw AppError.ProviderFailure("PluginInstaller", "GitHub rate limit — try again later")
            if (!response.isSuccessful) throw AppError.ProviderFailure("PluginInstaller", "no release for $repo (HTTP ${response.code})")
            val root = json.parseToJsonElement(response.body?.string() ?: "{}").jsonObject
            val assets = (root["assets"] as? JsonArray)?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull to it.jsonObject["browser_download_url"]?.jsonPrimitive?.contentOrNull }
                .filter { it.second != null }
            val zip = assets.firstOrNull { it.first == "plugin.zip" } ?: assets.firstOrNull { it.first?.endsWith(".zip") == true }
            return zip?.second
                ?: root["zipball_url"]?.jsonPrimitive?.contentOrNull
                ?: throw AppError.ProviderFailure("PluginInstaller", "no downloadable asset for $repo")
        }
    }

    private fun download(url: String): ByteArray =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw AppError.ProviderFailure("PluginInstaller", "download failed (HTTP ${response.code})")
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.size > MAX_ZIP_BYTES) throw AppError.ProviderFailure("PluginInstaller", "plugin archive too large")
            bytes
        }

    /** Extracts preserving entry paths verbatim (zip-slip and size guarded). */
    private fun extract(zipBytes: ByteArray, dir: File) {
        val canonicalRoot = dir.canonicalPath
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            var total = 0L
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = File(dir, entry.name)
                    if (!out.canonicalPath.startsWith(canonicalRoot)) throw AppError.ProviderFailure("PluginInstaller", "unsafe zip entry ${entry.name}")
                    out.parentFile?.mkdirs()
                    val bytes = zis.readBytes()
                    total += bytes.size
                    if (total > MAX_UNZIPPED_BYTES) throw AppError.ProviderFailure("PluginInstaller", "plugin unpacks too large")
                    out.writeBytes(bytes)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * A GitHub zipball nests everything under `repo-sha/`. If (and only if) `package.json` is missing at
     * the root while a single top-level directory carries it, hoist that directory's contents up.
     */
    private fun unwrapZipball(dir: File) {
        if (File(dir, "package.json").exists()) return
        val wrapper = dir.listFiles().orEmpty()
            .singleOrNull { it.isDirectory && File(it, "package.json").exists() } ?: return
        for (child in wrapper.listFiles().orEmpty()) {
            val target = File(dir, child.name)
            if (!child.renameTo(target)) {
                child.copyRecursively(target, overwrite = true)
                child.deleteRecursively()
            }
        }
        wrapper.delete()
    }

    private fun readManifest(dir: File): PluginManifest {
        val pkg = File(dir, "package.json")
        if (!pkg.exists()) throw AppError.ProviderFailure("PluginInstaller", "missing package.json")
        val obj = json.parseToJsonElement(pkg.readText()).jsonObject
        val nuclear = obj["nuclear"]?.jsonObject
        // Upstream uses `nuclear.categories` (plural array); older manifests use singular `category`.
        val category = nuclear?.get("category")?.jsonPrimitive?.contentOrNull
            ?: (nuclear?.get("categories") as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
        return PluginManifest(
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: dir.name,
            version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "0.0.0",
            description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
            author = obj["author"]?.jsonPrimitive?.contentOrNull ?: "",
            main = obj["main"]?.jsonPrimitive?.contentOrNull ?: "src/index.ts",
            category = category ?: "other",
            displayName = nuclear?.get("displayName")?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    /** Re-reads an already-installed plugin's sources from its [dir] (for startup reload). */
    fun readSources(dir: File): Map<String, PluginSourceFile> = collectSources(dir)

    private fun collectSources(dir: File): Map<String, PluginSourceFile> =
        dir.walkTopDown()
            .onEnter { it.name != "node_modules" && it.name != CACHE_DIR }
            .filter { it.isFile }
            .mapNotNull { file ->
                val kind = when (file.extension.lowercase()) {
                    "ts" -> if (file.name.endsWith(".d.ts")) null else PluginSourceKind.TS
                    "tsx" -> PluginSourceKind.TSX
                    "js", "mjs", "cjs" -> PluginSourceKind.JS
                    "json" -> PluginSourceKind.JSON // package.json included: plugins require it for their version
                    else -> null
                } ?: return@mapNotNull null
                val key = file.relativeTo(dir).path.replace('\\', '/').substringBeforeLast('.')
                key to PluginSourceFile(file.readText(), kind)
            }
            .toMap()

    /**
     * Registry releases put deps in `devDependencies` and ship plain sources, so any *runtime* bare
     * import would die at `require` inside the sandbox. Failing at install with the actual specifier
     * beats a cryptic "module not found" later; bundled releases (esbuild) inline everything and only
     * reference the externals the runtime stubs.
     */
    private fun checkBareDependencies(sources: Map<String, PluginSourceFile>) {
        for ((path, source) in sources) {
            if (source.kind == PluginSourceKind.JSON) continue
            for (match in BARE_IMPORT.findAll(source.code)) {
                val spec = match.groupValues[2]
                if (spec.startsWith('.') || spec.startsWith('/')) continue
                if (ALLOWED_BARE.any { spec == it || spec.startsWith("$it/") }) continue
                throw AppError.ProviderFailure(
                    "PluginInstaller",
                    "unbundled npm dependency '$spec' (in $path) — the plugin must ship a bundled (esbuild) release",
                )
            }
        }
    }

    companion object {
        /** Tolerant entry lookup: exact, then legacy `src/`-stripped keys, then a directory index. */
        fun resolveEntry(sources: Map<String, PluginSourceFile>, entryPath: String): String? = when {
            sources.containsKey(entryPath) -> entryPath
            sources.containsKey("src/$entryPath") -> "src/$entryPath"
            sources.containsKey("$entryPath/index") -> "$entryPath/index"
            else -> null
        }

        private fun normalize(main: String): String =
            main.removePrefix("./")
                .removeSuffix(".ts").removeSuffix(".tsx").removeSuffix(".js").removeSuffix(".mjs").removeSuffix(".cjs")

        /** `import … from 'x'` / `export … from 'x'` / `require('x')` — group 2 is the specifier. */
        private val BARE_IMPORT = Regex(
            """(?m)(?:^\s*(?:import|export)\s[^;]*?\sfrom\s*|^\s*import\s*|\brequire\s*\()\s*(['"])([^'"\n]+)\1""",
        )

        /** Specifiers the runtime resolves itself (type-only SDK + the inert UI stubs). */
        private val ALLOWED_BARE = setOf("@nuclearplayer/plugin-sdk", "@nuclearplayer/ui", "react", "react-dom")

        private val ID_UNSAFE = Regex("[^a-z0-9._-]+")

        const val CACHE_DIR = ".cache"
        const val SETTINGS_FILE = "settings.json"
        const val STORAGE_FILE = "storage.json"
        const val MAX_ZIP_BYTES = 20 * 1024 * 1024
        const val MAX_UNZIPPED_BYTES = 40 * 1024 * 1024
    }
}
