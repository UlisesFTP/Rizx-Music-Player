package fm.rizx.player.data.plugin.engine

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.Executors

/** Raised when plugin JS throws or a host boundary is violated. Carried as a typed message to the UI. */
class PluginException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One QuickJS instance for the plugin runtime (ADR 0014), confined to a single thread (QuickJS is not
 * thread-safe; a single thread also serializes all plugin JS = the crash-isolation baseline). Injects a
 * capability sandbox: only `fetch` (http/https, size-capped, over the shared OkHttp client), `console`,
 * timers, base64/URL, and a provider-registration callback — no DOM, no filesystem, no Android APIs.
 *
 * `evaluate` returns the Promise object (not its resolved value), so async plugin calls go through
 * [evalCaptured]: run a JS statement that stashes the settled result/error on `__rizx.__last`/`__err`
 * (QuickJS drains the job queue during `evaluate`), then read it back.
 */
class QuickJsEngine(
    private val httpClient: OkHttpClient,
    private val bootstrapJs: String,
    private val json: Json,
    /** Called (on the engine thread) when a plugin registers a provider descriptor. */
    private val onRegister: (pluginId: String, metaJson: String) -> Unit,
) {
    private val engineDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "rizx-quickjs") }.asCoroutineDispatcher()

    @Volatile
    private var quickJs: QuickJs? = null

    private suspend fun engine(): QuickJs {
        quickJs?.let { return it }
        val qjs = QuickJs.create(engineDispatcher)
        qjs.function("__rizx_log") { args ->
            Log.d(TAG, "[${args.getOrNull(0)}] ${args.getOrNull(1)}")
        }
        qjs.function("__rizx_onRegister") { args ->
            onRegister(args[0] as String, args[1] as String)
        }
        qjs.asyncFunction("__rizx_sleep") { args ->
            delay((args.getOrNull(0) as? Number)?.toLong() ?: 0L)
        }
        qjs.asyncFunction("__rizx_fetch") { args ->
            doFetch(args[0] as String)
        }
        qjs.evaluate<Any?>(bootstrapJs)
        quickJs = qjs
        return qjs
    }

    /** Evaluate arbitrary JS on the engine thread (no async-value capture). */
    suspend fun eval(code: String): Any? = withContext(engineDispatcher) { engine().evaluate<Any?>(code) }

    /**
     * Run [captureStatement] (a JS statement calling `__rizx.invokeAndCapture`/`__rizx.runHook`, which
     * settles `__rizx.__last`/`__err`), then read the captured JSON result or throw the captured error.
     * Serialized on the single engine thread, so the capture globals are never raced.
     */
    suspend fun evalCaptured(captureStatement: String): String? = withContext(engineDispatcher) {
        val qjs = engine()
        qjs.evaluate<Any?>(captureStatement)
        val err = qjs.evaluate<Any?>("globalThis.__rizx.__err")
        if (err != null) throw PluginException(err.toString())
        qjs.evaluate<Any?>("globalThis.__rizx.__last") as? String
    }

    suspend fun close() = withContext(engineDispatcher) {
        runCatching { quickJs?.close() }
        quickJs = null
    }

    private suspend fun doFetch(paramsJson: String): String {
        val params = json.parseToJsonElement(paramsJson).jsonObject
        val url = params["url"]!!.jsonPrimitive.content
        require(url.startsWith("http://") || url.startsWith("https://")) { "blocked non-http url" }
        val method = params["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val bodyStr = params["body"]?.jsonPrimitive?.contentOrNull
        val builder = Request.Builder().url(url)
        (params["headers"] as? JsonObject)?.forEach { (k, v) ->
            builder.header(k, v.jsonPrimitive.content)
        }
        val reqBody = bodyStr?.toRequestBody()
        builder.method(method, reqBody)

        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            httpClient.newCall(builder.build()).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (bytes.size > MAX_BODY_BYTES) throw PluginException("plugin fetch body too large")
                val text = String(bytes, Charsets.UTF_8)
                buildJsonObject {
                    put("status", response.code)
                    put("statusText", response.message)
                    put("url", response.request.url.toString())
                    put("body", text)
                    put("headers", buildJsonObject {
                        for (name in response.headers.names()) put(name.lowercase(), response.header(name) ?: "")
                    })
                }.toString()
            }
        }
    }

    private companion object {
        const val TAG = "JsPlugin"
        const val MAX_BODY_BYTES = 10 * 1024 * 1024
    }
}
