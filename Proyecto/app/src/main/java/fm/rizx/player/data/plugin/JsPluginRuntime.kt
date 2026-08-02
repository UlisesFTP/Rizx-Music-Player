package fm.rizx.player.data.plugin

import android.util.Log
import fm.rizx.player.data.plugin.bridge.JsDashboardProvider
import fm.rizx.player.data.plugin.bridge.JsDiscoveryProvider
import fm.rizx.player.data.plugin.bridge.JsLyricsProvider
import fm.rizx.player.data.plugin.bridge.JsMetadataProvider
import fm.rizx.player.data.plugin.bridge.JsPlaylistProvider
import fm.rizx.player.data.plugin.bridge.JsProviderInvoker
import fm.rizx.player.data.plugin.bridge.JsStreamingProvider
import fm.rizx.player.data.plugin.engine.PluginException
import fm.rizx.player.data.plugin.engine.QuickJsEngine
import fm.rizx.player.data.plugin.install.PluginInstaller
import fm.rizx.player.data.plugin.install.PluginSourceFile
import fm.rizx.player.data.plugin.install.PluginSourceKind
import fm.rizx.player.data.plugin.install.TsTranspiler
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.provider.ProviderDescriptor
import fm.rizx.player.domain.provider.ProviderRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads and runs Nuclear plugins in the sandboxed QuickJS runtime (ADR 0014/0019) and bridges the
 * provider descriptors they register into the app's [ProviderRegistry] as Kotlin adapters (with
 * `pluginId` set).
 *
 * A plugin's `onEnable(api)` calls `api.Providers.register(descriptor)`, which fires the engine's
 * register callback; registrations flush **continuously** (a `register` from a fetch callback or a
 * timer minutes after load still lands), building the Kotlin adapter and calling `registry.register`
 * on the main thread (the registry is single-thread).
 *
 * Robustness: every provider invocation goes through the [JsProviderInvoker] chokepoint — per-call
 * timeout, and a per-plugin consecutive-failure counter that **quarantines** the plugin (unregisters
 * its providers, reports the last error via [onQuarantine]) after [QUARANTINE_THRESHOLD] straight
 * failures. A broken plugin degrades to empty results and eventually steps aside; it never crashes
 * the app.
 */
class JsPluginRuntime(
    httpClient: okhttp3.OkHttpClient,
    bootstrapJs: String,
    private val json: Json,
    private val registry: ProviderRegistry,
    private val transpiler: TsTranspiler? = null,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    extraJs: List<String> = emptyList(),
    kv: PluginKvStore? = null,
    onOpenExternal: ((String) -> Unit)? = null,
    ytdlp: YtdlpFacade? = null,
) : JsProviderInvoker {

    private val pending = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val registeredUids = mutableMapOf<String, MutableList<String>>()
    private val pluginVersions = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val failureCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val quarantined = Collections.synchronizedSet(mutableSetOf<String>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Set by the repository: persists the quarantine (health + lastError) and disables the plugin. */
    var onQuarantine: (suspend (pluginId: String, lastError: String) -> Unit)? = null

    private val engine = QuickJsEngine(
        httpClient, bootstrapJs, json,
        extraJs = extraJs, kv = kv, onOpenExternal = onOpenExternal, ytdlp = ytdlp,
    ) { pluginId, metaJson ->
        pending.add(pluginId to metaJson)
        // Continuous flush: registrations made after the load hooks (settings-gated, async init)
        // must not be lost. Idempotent — it just drains whatever is pending.
        scope.launch { runCatching { flushRegistrations() } }
    }

    /** Transpiles a plugin's TypeScript [tsSource] to CommonJS, then loads it (single-file entry). */
    suspend fun loadTsPlugin(pluginId: String, tsSource: String) {
        loadTsPluginModules(
            pluginId,
            mapOf("index" to PluginSourceFile(tsSource, PluginSourceKind.TS)),
            "index",
        )
    }

    /**
     * Loads a **multi-file** plugin: [sources] maps each module's extension-less path (relative to the
     * plugin root, e.g. `src/index`, `dist/index`) to its source; [entryPath] is the module to require
     * (from `package.json` `main`). Every file is lowered to CommonJS as its kind demands (TS/TSX via
     * Sucrase — cached by content hash under [cacheDir] — plain JS only when it is ESM, JSON verbatim),
     * wired into a minimal CommonJS module graph, then `onLoad`/`onEnable` run and providers register.
     */
    suspend fun loadTsPluginModules(
        pluginId: String,
        sources: Map<String, PluginSourceFile>,
        entryPath: String,
        version: String? = null,
        cacheDir: File? = null,
    ) {
        val transpiler = requireNotNull(transpiler) { "no TS transpiler configured" }
        if (pluginId in quarantined) throw PluginException("plugin '$pluginId' is quarantined — re-enable it to retry")
        val entry = PluginInstaller.resolveEntry(sources, normalizePath(entryPath))
            ?: throw PluginException("entry '$entryPath' not found in plugin '$pluginId'")
        val compiled = LinkedHashMap<String, CompiledModule>()
        for ((path, source) in sources) {
            compiled[path] = when (source.kind) {
                PluginSourceKind.JSON -> CompiledModule(source.code, isJson = true)
                PluginSourceKind.TS -> CompiledModule(cached(cacheDir, source) { transpiler.transpile(source.code) })
                PluginSourceKind.TSX -> CompiledModule(cached(cacheDir, source) { transpiler.transpile(source.code, jsx = true) })
                PluginSourceKind.JS -> CompiledModule(cached(cacheDir, source) { transpiler.transpileJsIfEsm(source.code) })
            }
        }
        version?.let { pluginVersions[pluginId] = it }
        // All or nothing. Providers register from inside `onLoad`, and the registration hook flushes them
        // into the app the moment they arrive — so a plugin that threw on a later line used to leave a
        // live, working provider behind while the caller reported a failed install and persisted nothing.
        // The result was a plugin that worked until the next restart and a screen insisting it hadn't
        // installed. A failed load now leaves the app exactly as it found it.
        try {
            engine.eval(buildModuleGraph(pluginId, compiled, entry))
            engine.evalCaptured("globalThis.__rizx.runHook(${enc(pluginId)}, 'onLoad')")
            engine.evalCaptured("globalThis.__rizx.runHook(${enc(pluginId)}, 'onEnable')")
            flushRegistrations()
        } catch (e: Throwable) {
            synchronized(pending) { pending.removeAll { (id, _) -> id == pluginId } }
            runCatching { unregisterPlugin(pluginId) }
            throw e
        }
    }

    // ---- JsProviderInvoker ------------------------------------------------

    override suspend fun invoke(uid: String, method: String, argsJson: String, timeoutMs: Long): String? {
        val pluginId = uid.substringBefore(':')
        if (pluginId in quarantined) throw PluginException("plugin '$pluginId' is quarantined")
        val statement = "globalThis.__rizx.invokeAndCapture(${enc(uid)}, ${enc(method)}, ${enc(argsJson)})"
        return try {
            val result = withTimeout(timeoutMs) { engine.evalCaptured(statement) }
            failureCounts[pluginId]?.set(0)
            result
        } catch (e: TimeoutCancellationException) {
            recordFailure(pluginId, "'$method' timed out after ${timeoutMs} ms")
            throw PluginException("$uid.$method timed out")
        } catch (e: PluginException) {
            recordFailure(pluginId, e.message ?: "plugin error")
            throw e
        }
    }

    private fun recordFailure(pluginId: String, message: String) {
        val count = failureCounts.getOrPut(pluginId) { AtomicInteger() }.incrementAndGet()
        if (count < QUARANTINE_THRESHOLD || !quarantined.add(pluginId)) return
        Log.w(TAG, "quarantining plugin '$pluginId' after $count consecutive failures: $message")
        scope.launch {
            runCatching { unregisterPlugin(pluginId) }
            runCatching { onQuarantine?.invoke(pluginId, message) }
        }
    }

    /** Clears quarantine + failure state (the repository calls this when the user re-enables). */
    fun clearQuarantine(pluginId: String) {
        quarantined.remove(pluginId)
        failureCounts[pluginId]?.set(0)
    }

    // ---- module graph -----------------------------------------------------

    /** Content-addressed transpile cache: `<dir>/.cache/<sha256>.js`. Best-effort — I/O never fails a load. */
    private suspend fun cached(cacheDir: File?, source: PluginSourceFile, produce: suspend () -> String): String {
        val dir = cacheDir?.let { File(it, PluginInstaller.CACHE_DIR) } ?: return produce()
        val key = sha256("${source.kind}:${source.code}")
        val file = File(dir, "$key.js")
        runCatching { if (file.isFile) return file.readText() }
        val out = produce()
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$key.tmp")
            tmp.writeText(out)
            tmp.renameTo(file)
        }
        return out
    }

    private data class CompiledModule(val code: String, val isJson: Boolean = false)

    /** Builds a self-contained CommonJS module graph that requires [entryPath] and stores the plugin. */
    private fun buildModuleGraph(pluginId: String, modules: Map<String, CompiledModule>, entryPath: String): String {
        val defines = modules.entries.joinToString("\n") { (path, module) ->
            if (module.isJson) {
                // JSON is a syntactic subset of a JS expression — inline it verbatim.
                "  __defs[${enc(path)}] = function(module) { module.exports = (\n${module.code}\n); };"
            } else {
                "  __defs[${enc(path)}] = function(module, exports, require) {\n${module.code}\n};"
            }
        }
        return """
            globalThis.__rizx.plugins[${enc(pluginId)}] = (function () {
              var __defs = {};
              var __cache = {};
              function __dirname(p) { var i = p.lastIndexOf('/'); return i < 0 ? '' : p.slice(0, i); }
              function __norm(p) {
                var parts = p.split('/'); var out = [];
                for (var i = 0; i < parts.length; i++) {
                  var s = parts[i];
                  if (s === '' || s === '.') continue;
                  if (s === '..') out.pop(); else out.push(s);
                }
                return out.join('/');
              }
              function __stripExt(p) {
                return p.replace(/\.(ts|tsx|js|mjs|cjs|json)$/, '');
              }
              function __resolve(base, spec) {
                if (spec[0] !== '.') return spec; // bare specifier (SDK / stubs)
                var joined = __norm((base ? base + '/' : '') + spec);
                if (__defs[joined]) return joined;
                var bare = __stripExt(joined);
                if (__defs[bare]) return bare;
                if (__defs[joined + '/index']) return joined + '/index';
                return joined;
              }
              function __require(fromPath, spec) {
                if (spec[0] !== '.' && spec[0] !== '/') {
                  var stub = globalThis.__rizx.requireStub(spec);
                  if (stub !== undefined) return stub;
                }
                var key = __resolve(__dirname(fromPath), spec);
                if (__cache[key]) return __cache[key].exports;
                var def = __defs[key];
                if (!def) throw new Error('module not found: ' + spec + ' (from ' + fromPath + ')');
                var m = { exports: {} };
                __cache[key] = m;
                def(m, m.exports, function (s) { return __require(key, s); });
                return m.exports;
              }
            $defines
              var entry = __require('', ${enc(entryPath)});
              return (entry && entry.default) || entry;
            })();
        """.trimIndent()
    }

    /** Uniform module keys: strip a leading `./` and any known extension (`src/`/`dist/` are kept). */
    private fun normalizePath(path: String): String =
        path.removePrefix("./")
            .removeSuffix(".ts").removeSuffix(".tsx").removeSuffix(".js").removeSuffix(".mjs").removeSuffix(".cjs")

    // ---- registration -----------------------------------------------------

    private suspend fun flushRegistrations() {
        val snapshot = synchronized(pending) { pending.toList().also { pending.clear() } }
        for ((pluginId, metaJson) in snapshot) {
            val provider = buildProvider(pluginId, metaJson) ?: continue
            withContext(mainDispatcher) { runCatching { registry.register(provider) } }
            synchronized(registeredUids) {
                registeredUids.getOrPut(pluginId) { mutableListOf() }.add(provider.id)
            }
        }
    }

    private fun buildProvider(pluginId: String, metaJson: String): ProviderDescriptor? {
        val meta = runCatching { json.parseToJsonElement(metaJson).jsonObject }.getOrNull() ?: return null
        val uid = meta["uid"]?.jsonPrimitive?.contentOrNull ?: return null
        val kind = meta["kind"]?.jsonPrimitive?.contentOrNull
        val name = meta["name"]?.jsonPrimitive?.contentOrNull ?: uid
        val descriptorId = meta["id"]?.jsonPrimitive?.contentOrNull ?: uid
        val version = pluginVersions[pluginId] ?: "1.0"
        val methods = meta.strings("methods")
        val caps = meta.strings("searchCapabilities").mapNotNull { mapCap(it) }.toSet()
        return when (kind) {
            "metadata" -> JsMetadataProvider(
                id = uid, name = name, version = version, pluginId = pluginId, uid = uid,
                descriptorId = descriptorId, searchCapabilities = caps, methods = methods,
                invoker = this, json = json,
            )
            "streaming" -> JsStreamingProvider(
                id = uid, name = name, version = version, pluginId = pluginId, uid = uid,
                descriptorId = descriptorId, methods = methods, invoker = this, json = json,
            )
            "dashboard" -> JsDashboardProvider(
                id = uid, name = name, version = version, pluginId = pluginId, uid = uid,
                descriptorId = descriptorId, methods = methods,
                declaredCapabilities = meta.strings("capabilities"),
                invoker = this, json = json,
            )
            "playlists" -> JsPlaylistProvider(
                id = uid, name = name, version = version, pluginId = pluginId, uid = uid,
                descriptorId = descriptorId, methods = methods, invoker = this, json = json,
            )
            "lyrics" -> JsLyricsProvider(
                id = uid, name = name, version = version, pluginId = pluginId, uid = uid,
                methods = methods, invoker = this, json = json,
            )
            "discovery" -> JsDiscoveryProvider(
                id = uid, name = name, version = version, pluginId = pluginId, uid = uid,
                descriptorId = descriptorId, invoker = this, json = json,
            )
            else -> {
                Log.w(TAG, "plugin '$pluginId' registered unknown kind '$kind' (uid=$uid) — skipped")
                null
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObject.strings(key: String): Set<String> =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()

    /** Runs `onDisable`+`onUnload` and unregisters every provider the plugin contributed. */
    suspend fun unregisterPlugin(pluginId: String) {
        runCatching { engine.evalCaptured("globalThis.__rizx.runHook(${enc(pluginId)}, 'onDisable')") }
        runCatching { engine.evalCaptured("globalThis.__rizx.runHook(${enc(pluginId)}, 'onUnload')") }
        val uids = synchronized(registeredUids) { registeredUids.remove(pluginId).orEmpty() }
        for (uid in uids) withContext(mainDispatcher) { runCatching { registry.unregister(uid) } }
        runCatching {
            engine.eval(
                "delete globalThis.__rizx.plugins[${enc(pluginId)}];" +
                    "globalThis.__rizx.dropProviders(${enc(pluginId)});",
            )
        }
    }

    /**
     * The recovery hammer (ADR 0014): unregisters everything, tears the VM down, and clears failure
     * state. The repository reloads the enabled set afterwards.
     */
    suspend fun restart() {
        val pluginIds = synchronized(registeredUids) { registeredUids.keys.toList() }
        for (id in pluginIds) runCatching { unregisterPlugin(id) }
        engine.restart()
        failureCounts.clear()
        quarantined.clear()
    }

    /** True if [pluginId] currently has registered providers (already loaded this session). */
    fun isLoaded(pluginId: String): Boolean = synchronized(registeredUids) { registeredUids.containsKey(pluginId) }

    suspend fun close() = engine.close()

    private fun mapCap(s: String?): SearchCapability? = when (s?.lowercase()) {
        "unified" -> SearchCapability.UNIFIED
        "artists" -> SearchCapability.ARTISTS
        "albums" -> SearchCapability.ALBUMS
        "tracks" -> SearchCapability.TRACKS
        "playlists" -> SearchCapability.PLAYLISTS
        else -> null
    }

    private fun enc(s: String): String = json.encodeToString(String.serializer(), s)

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "JsPlugin"
        const val QUARANTINE_THRESHOLD = 5
    }
}
