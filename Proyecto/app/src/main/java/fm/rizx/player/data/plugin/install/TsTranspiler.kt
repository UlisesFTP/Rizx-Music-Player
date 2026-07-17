package fm.rizx.player.data.plugin.install

import com.dokar.quickjs.QuickJs
import fm.rizx.player.data.plugin.engine.PluginException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors

/**
 * Transpiles a Nuclear plugin's **TypeScript source** to CommonJS on-device (ADR 0014) — releases ship
 * `.ts` files under `src` (no built `dist`), which the desktop host compiles on the fly. Runs the vendored Sucrase bundle
 * (`assets/plugin-runtime/sucrase.min.js`) inside its **own** short-lived QuickJS instance, isolated from
 * the plugin engine so a transpiler failure can't corrupt running plugins.
 *
 * `transforms: ['typescript', 'imports']` strips types and lowers ES module `import`/`export` to
 * `require`/`exports`, matching the CommonJS loader in the runtime's bootstrap.
 */
class TsTranspiler(
    private val sucraseJs: String,
    private val json: Json,
) {
    private val dispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "rizx-sucrase") }.asCoroutineDispatcher()

    @Volatile
    private var quickJs: QuickJs? = null

    private suspend fun engine(): QuickJs {
        quickJs?.let { return it }
        val qjs = QuickJs.create(dispatcher)
        qjs.evaluate<Any?>(sucraseJs) // defines globalThis.Sucrase
        quickJs = qjs
        return qjs
    }

    /** Transpile TypeScript/ESM [tsSource] to CommonJS ES2020, or throw [PluginException]. */
    suspend fun transpile(tsSource: String): String = withContext(dispatcher) {
        val qjs = engine()
        // Pass the (possibly large) source via a global to avoid embedding it in the eval string.
        qjs.evaluate<Any?>("globalThis.__ts_src = ${enc(tsSource)};")
        val code = try {
            qjs.evaluate<Any?>(
                "globalThis.Sucrase.transform(globalThis.__ts_src, { transforms: ['typescript', 'imports'] }).code",
            ) as? String
        } catch (e: Exception) {
            throw PluginException("TypeScript transpile failed: ${e.message}", e)
        }
        code ?: throw PluginException("TypeScript transpile produced no output")
    }

    suspend fun close() = withContext(dispatcher) {
        runCatching { quickJs?.close() }
        quickJs = null
    }

    private fun enc(s: String): String = json.encodeToString(String.serializer(), s)
}
