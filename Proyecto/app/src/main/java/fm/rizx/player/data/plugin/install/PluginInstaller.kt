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
import java.util.zip.ZipInputStream

/** A plugin's source, extracted and ready to transpile+load. */
data class ExtractedPlugin(
    val manifest: PluginManifest,
    val dir: File,
    /** Extension-less module path → TypeScript source (e.g. `index` → …, `discogs` → …). */
    val tsFiles: Map<String, String>,
    val entryPath: String,
)

/**
 * Downloads a Nuclear plugin's latest GitHub release, extracts it to app storage, and reads its
 * TypeScript sources (ADR 0014). No code runs here — extraction is guarded against zip-slip and size
 * blowups; running is the runtime's job. Unauthenticated GitHub API (60 req/h) is plenty for personal use.
 */
class PluginInstaller(
    private val client: OkHttpClient,
    private val json: Json,
    private val pluginsRoot: File,
) {
    suspend fun install(pluginId: String, repo: String, downloadUrl: String? = null): ExtractedPlugin = withContext(Dispatchers.IO) {
        val assetUrl = downloadUrl ?: resolveAssetUrl(repo)
        val zipBytes = download(assetUrl)
        val dir = File(pluginsRoot, pluginId).apply { deleteRecursively(); mkdirs() }
        try {
            extract(zipBytes, dir)
            val manifest = readManifest(dir)
            val entryPath = normalize(manifest.main)
            val tsFiles = collectTsFiles(dir)
            if (tsFiles[entryPath] == null && tsFiles.isEmpty()) {
                throw AppError.ProviderFailure("PluginInstaller", "no source files in $repo")
            }
            ExtractedPlugin(manifest, dir, tsFiles, entryPath)
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

    private fun extract(zipBytes: ByteArray, dir: File) {
        val canonicalRoot = dir.canonicalPath
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            var total = 0L
            while (entry != null) {
                if (!entry.isDirectory) {
                    // GitHub zipball nests everything under a top dir; strip the first path segment then.
                    val name = entry.name.substringAfter('/', entry.name).ifBlank { entry.name }
                    val out = File(dir, name)
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

    private fun readManifest(dir: File): PluginManifest {
        val pkg = File(dir, "package.json")
        if (!pkg.exists()) throw AppError.ProviderFailure("PluginInstaller", "missing package.json")
        val obj = json.parseToJsonElement(pkg.readText()).jsonObject
        val nuclear = obj["nuclear"]?.jsonObject
        return PluginManifest(
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: dir.name,
            version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "0.0.0",
            description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
            author = obj["author"]?.jsonPrimitive?.contentOrNull ?: "",
            main = obj["main"]?.jsonPrimitive?.contentOrNull ?: "src/index.ts",
            category = nuclear?.get("category")?.jsonPrimitive?.contentOrNull ?: "other",
            displayName = nuclear?.get("displayName")?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    /** Re-reads an already-installed plugin's TS sources from its [dir] (for startup reload). */
    fun readSources(dir: File): Map<String, String> = collectTsFiles(dir)

    private fun collectTsFiles(dir: File): Map<String, String> {
        val src = File(dir, "src").takeIf { it.isDirectory } ?: dir
        return src.walkTopDown()
            .filter { it.isFile && it.extension == "ts" }
            .associate { it.relativeTo(src).path.replace('\\', '/').removeSuffix(".ts") to it.readText() }
    }

    private fun normalize(main: String): String =
        main.removePrefix("./").removePrefix("src/").removeSuffix(".ts").removeSuffix(".js")

    private companion object {
        const val MAX_ZIP_BYTES = 20 * 1024 * 1024
        const val MAX_UNZIPPED_BYTES = 40 * 1024 * 1024
    }
}
