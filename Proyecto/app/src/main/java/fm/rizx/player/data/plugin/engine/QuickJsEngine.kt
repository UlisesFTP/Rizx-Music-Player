package fm.rizx.player.data.plugin.engine

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import fm.rizx.player.data.plugin.PluginKvStore
import fm.rizx.player.data.plugin.YtdlpFacade
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Raised when plugin JS throws or a host boundary is violated. Carried as a typed message to the UI. */
class PluginException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One QuickJS instance for the plugin runtime (ADR 0014/0019), confined to a single thread (QuickJS is
 * not thread-safe; a single thread also serializes all plugin JS = the crash-isolation baseline).
 * Injects a capability sandbox: `fetch` (http/https, size-capped during the read, call-timeout, over
 * the shared OkHttp client), `console`, timers, base64/URL/DOM-lite, per-plugin settings/storage via
 * [PluginKvStore], `crypto` primitives (SecureRandom / javax.crypto HMAC — hex strings across the
 * boundary), and open-in-browser — no filesystem, no Android APIs.
 *
 * `evaluate` returns the Promise object (not its resolved value), so async plugin calls go through
 * [evalCaptured]: run a JS statement that stashes the settled result/error on `__rizx.__last`/`__err`
 * (QuickJS drains the job queue during `evaluate`), then read it back.
 */
class QuickJsEngine(
    private val httpClient: OkHttpClient,
    private val bootstrapJs: String,
    private val json: Json,
    /** Extra scripts evaluated right after the bootstrap (the vendored DOMParser bundle). */
    private val extraJs: List<String> = emptyList(),
    private val kv: PluginKvStore? = null,
    /** Fires `api.Shell.openExternal(url)` — an ACTION_VIEW intent in production. */
    private val onOpenExternal: ((String) -> Unit)? = null,
    /** `api.Ytdlp` backed by the native YouTube extractor; null keeps the sandbox's rejecting stub. */
    private val ytdlp: YtdlpFacade? = null,
    /** Called (on the engine thread) when a plugin registers a provider descriptor. */
    private val onRegister: (pluginId: String, metaJson: String) -> Unit,
) {
    private val engineDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "rizx-quickjs") }.asCoroutineDispatcher()

    /** Fetches get a hard call timeout so a stalled server can't wedge a plugin invocation forever. */
    private val fetchClient by lazy {
        httpClient.newBuilder().callTimeout(FETCH_TIMEOUT_S, TimeUnit.SECONDS).build()
    }

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
        qjs.function("__rizx_kv_get") { args ->
            kv?.get(args[0] as String, args[1] as String, args[2] as String)
        }
        qjs.function("__rizx_kv_set") { args ->
            kv?.set(args[0] as String, args[1] as String, args[2] as String, args[3] as String)
            null
        }
        qjs.function("__rizx_kv_remove") { args ->
            kv?.remove(args[0] as String, args[1] as String, args[2] as String)
            null
        }
        qjs.function("__rizx_open_external") { args ->
            val url = args.getOrNull(0) as? String ?: return@function null
            if (url.startsWith("http://") || url.startsWith("https://")) onOpenExternal?.invoke(url)
            null
        }
        qjs.function("__rizx_random_hex") { args ->
            val n = ((args.getOrNull(0) as? Number)?.toInt() ?: 16).coerceIn(1, 1024)
            ByteArray(n).also { secureRandom.nextBytes(it) }.toHex()
        }
        qjs.function("__rizx_hmac_hex") { args ->
            val alg = hmacAlgorithm(args[0] as String)
            val mac = Mac.getInstance(alg)
            mac.init(SecretKeySpec((args[1] as String).hexToBytes(), alg))
            mac.doFinal((args[2] as String).hexToBytes()).toHex()
        }
        qjs.function("__rizx_digest_hex") { args ->
            val alg = digestAlgorithm(args[0] as String)
            MessageDigest.getInstance(alg).digest((args[1] as String).hexToBytes()).toHex()
        }
        qjs.asyncFunction("__rizx_ytdlp") { args ->
            val facade = ytdlp ?: throw PluginException("yt-dlp is not available on Android")
            facade.handle(args[0] as String, args[1] as String)
        }
        qjs.evaluate<Any?>(bootstrapJs)
        for (script in extraJs) runCatching { qjs.evaluate<Any?>(script) }
            .onFailure { Log.w(TAG, "extra runtime script failed to evaluate: ${it.message}") }
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

    /**
     * Tears the VM down and lets the next call lazily rebuild it from the bootstrap — the recovery
     * hammer for a wedged engine (ADR 0014). Loaded plugins are gone afterwards; the repository is
     * responsible for reloading the enabled set.
     */
    suspend fun restart() = withContext(engineDispatcher) {
        runCatching { quickJs?.close() }
        quickJs = null
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
            builder.header(k, v.jsonPrimitive.content) // caller-set headers pass through, User-Agent included
        }
        val reqBody = bodyStr?.toRequestBody()
        builder.method(method, reqBody)

        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            fetchClient.newCall(builder.build()).execute().use { response ->
                val source = response.body?.source()
                // Cap enforced while reading: request(N+1) buffers at most N+1 bytes — an oversized
                // body fails here instead of after materializing fully in memory.
                val text = if (source == null) "" else {
                    if (source.request(MAX_BODY_BYTES + 1L)) throw PluginException("plugin fetch body too large")
                    source.buffer.readString(Charsets.UTF_8)
                }
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

    private fun hmacAlgorithm(name: String): String = when (name.uppercase().replace("-", "")) {
        "SHA1" -> "HmacSHA1"
        "SHA256" -> "HmacSHA256"
        "SHA512" -> "HmacSHA512"
        else -> throw PluginException("unsupported HMAC hash: $name")
    }

    private fun digestAlgorithm(name: String): String = when (name.uppercase().replace("-", "")) {
        "SHA1" -> "SHA-1"
        "SHA256" -> "SHA-256"
        "SHA512" -> "SHA-512"
        else -> throw PluginException("unsupported digest: $name")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "bad hex" }
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private companion object {
        const val TAG = "JsPlugin"
        const val MAX_BODY_BYTES = 10 * 1024 * 1024
        const val FETCH_TIMEOUT_S = 30L
        val secureRandom = SecureRandom()
    }
}
