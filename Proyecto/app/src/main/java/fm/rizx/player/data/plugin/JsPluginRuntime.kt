package fm.rizx.player.data.plugin

import android.util.Log
import fm.rizx.player.data.plugin.bridge.JsDashboardProvider
import fm.rizx.player.data.plugin.bridge.JsMetadataProvider
import fm.rizx.player.data.plugin.bridge.JsStreamingProvider
import fm.rizx.player.data.plugin.engine.QuickJsEngine
import fm.rizx.player.data.plugin.install.TsTranspiler
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.provider.ProviderDescriptor
import fm.rizx.player.domain.provider.ProviderRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections

/**
 * Loads and runs Nuclear plugins in the sandboxed QuickJS runtime (ADR 0014) and bridges the provider
 * descriptors they register into the app's [ProviderRegistry] as Kotlin adapters (with `pluginId` set).
 *
 * A plugin's `onEnable(api)` calls `api.Providers.register(descriptor)`, which fires the engine's
 * register callback; those registrations are queued (on the engine thread) and then flushed — building
 * the Kotlin adapter and calling `registry.register` on the main thread (the registry is single-thread).
 * Each plugin is isolated: a hook that throws is caught and surfaced, never crashing the app.
 */
class JsPluginRuntime(
    httpClient: okhttp3.OkHttpClient,
    bootstrapJs: String,
    private val json: Json,
    private val registry: ProviderRegistry,
    private val transpiler: TsTranspiler? = null,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val pending = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val registeredUids = mutableMapOf<String, MutableList<String>>()

    private val engine = QuickJsEngine(httpClient, bootstrapJs, json) { pluginId, metaJson ->
        pending.add(pluginId to metaJson)
    }

    /** Transpiles a plugin's TypeScript [tsSource] to CommonJS, then loads it (single-file entry). */
    suspend fun loadTsPlugin(pluginId: String, tsSource: String) {
        val transpiler = requireNotNull(transpiler) { "no TS transpiler configured" }
        loadPlugin(pluginId, transpiler.transpile(tsSource))
    }

    /**
     * Loads a **multi-file** plugin: [tsFiles] maps each module's extension-less path (e.g. `index`,
     * `discogs`) to its TypeScript source; [entryPath] is the module to require (from `package.json`
     * `main`). Every file is transpiled, wired into a minimal CommonJS module graph (relative `require`
     * resolves across the files; the plugin SDK resolves to an empty module), then `onLoad`/`onEnable`
     * run and providers register. This is the real-plugin path used by the installer.
     */
    suspend fun loadTsPluginModules(pluginId: String, tsFiles: Map<String, String>, entryPath: String) {
        val transpiler = requireNotNull(transpiler) { "no TS transpiler configured" }
        val compiled = LinkedHashMap<String, String>()
        for ((path, ts) in tsFiles) compiled[normalizePath(path)] = transpiler.transpile(ts)
        engine.eval(buildModuleGraph(pluginId, compiled, normalizePath(entryPath)))
        engine.evalCaptured("globalThis.__rizx.runHook(${enc(pluginId)}, 'onLoad')")
        engine.evalCaptured("globalThis.__rizx.runHook(${enc(pluginId)}, 'onEnable')")
        flushRegistrations()
    }

    /** Builds a self-contained CommonJS module graph that requires [entryPath] and stores the plugin. */
    private fun buildModuleGraph(pluginId: String, modules: Map<String, String>, entryPath: String): String {
        val defines = modules.entries.joinToString("\n") { (path, code) ->
            "  __defs[${enc(path)}] = function(module, exports, require) {\n$code\n};"
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
              function __resolve(base, spec) {
                if (spec[0] !== '.') return spec; // bare specifier (e.g. the SDK)
                var joined = __norm((base ? base + '/' : '') + spec);
                if (__defs[joined]) return joined;
                if (__defs[joined + '/index']) return joined + '/index';
                return joined;
              }
              function __require(fromPath, spec) {
                if (spec === '@nuclearplayer/plugin-sdk') return {};
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

    /** Strips a leading `src/` and any `.ts`/`.js` extension so paths key uniformly (`src/index.ts` → `index`). */
    private fun normalizePath(path: String): String =
        path.removePrefix("./").removePrefix("src/").removeSuffix(".ts").removeSuffix(".js")

    /**
     * Loads a plugin from already-transpiled CommonJS [source] under [pluginId], runs `onLoad`/`onEnable`,
     * and registers whatever providers it declared. A minimal `require` resolves the (type-only) plugin
     * SDK to an empty module; multi-file relative requires arrive with Phase C's module loader.
     */
    suspend fun loadPlugin(pluginId: String, source: String) {
        val store = "globalThis.__rizx.plugins[${enc(pluginId)}] = (function(){" +
            "var module={exports:{}};var exports=module.exports;" +
            "var require=function(s){ if(s==='@nuclearplayer/plugin-sdk'){return {};} " +
            "throw new Error('require not supported: '+s); };" +
            "(function(module,exports,require){\n$source\n})(module,exports,require);" +
            "return (module.exports && module.exports.default) || module.exports;})();"
        engine.eval(store)
        engine.evalCaptured("globalThis.__rizx.runHook(${enc(pluginId)}, 'onLoad')")
        engine.evalCaptured("globalThis.__rizx.runHook(${enc(pluginId)}, 'onEnable')")
        flushRegistrations()
    }

    private suspend fun flushRegistrations() {
        val snapshot = synchronized(pending) { pending.toList().also { pending.clear() } }
        for ((pluginId, metaJson) in snapshot) {
            val provider = buildProvider(pluginId, metaJson) ?: continue
            withContext(mainDispatcher) { runCatching { registry.register(provider) } }
            registeredUids.getOrPut(pluginId) { mutableListOf() }.add(provider.id)
        }
    }

    private fun buildProvider(pluginId: String, metaJson: String): ProviderDescriptor? {
        val meta = runCatching { json.parseToJsonElement(metaJson).jsonObject }.getOrNull() ?: return null
        val uid = meta["uid"]?.jsonPrimitive?.contentOrNull ?: return null
        val kind = meta["kind"]?.jsonPrimitive?.contentOrNull
        val name = meta["name"]?.jsonPrimitive?.contentOrNull ?: uid
        val descriptorId = meta["id"]?.jsonPrimitive?.contentOrNull ?: uid
        val methods = (meta["methods"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
        val caps = (meta["searchCapabilities"] as? JsonArray)
            ?.mapNotNull { mapCap(it.jsonPrimitive.contentOrNull) }?.toSet().orEmpty()
        return when (kind) {
            "metadata" -> JsMetadataProvider(
                id = uid, name = name, version = "1.0", pluginId = pluginId, uid = uid,
                descriptorId = descriptorId, searchCapabilities = caps, methods = methods,
                engine = engine, json = json,
            )
            "streaming" -> JsStreamingProvider(
                id = uid, name = name, version = "1.0", pluginId = pluginId, uid = uid,
                descriptorId = descriptorId, engine = engine, json = json,
            )
            "dashboard" -> JsDashboardProvider(
                id = uid, name = name, version = "1.0", pluginId = pluginId, uid = uid,
                descriptorId = descriptorId, methods = methods, engine = engine, json = json,
            )
            else -> {
                // playlists/lyrics adapters can follow the same pattern when a plugin needs them.
                Log.w(TAG, "plugin '$pluginId' registered unsupported kind '$kind' (uid=$uid) — skipped")
                null
            }
        }
    }

    /** Runs `onDisable` and unregisters every provider the plugin contributed (for disable/uninstall). */
    suspend fun unregisterPlugin(pluginId: String) {
        runCatching { engine.evalCaptured("globalThis.__rizx.runHook(${enc(pluginId)}, 'onDisable')") }
        val uids = synchronized(registeredUids) { registeredUids.remove(pluginId).orEmpty() }
        for (uid in uids) withContext(mainDispatcher) { runCatching { registry.unregister(uid) } }
        runCatching { engine.eval("delete globalThis.__rizx.plugins[${enc(pluginId)}];") }
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

    private companion object {
        const val TAG = "JsPlugin"
    }
}
