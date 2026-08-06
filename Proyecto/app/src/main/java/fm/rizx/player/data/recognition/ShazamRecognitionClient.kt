package fm.rizx.player.data.recognition

import fm.rizx.player.domain.recognition.RecognitionError
import fm.rizx.player.domain.recognition.RecognitionOutcome
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * One request to the recognition service: a fingerprint in, an identification out.
 *
 * Holds no retry, no cache and no concurrency policy — those live in [ShazamRecognitionProvider], so
 * that everything here can be exercised against a `MockWebServer` one status code at a time.
 *
 * **This endpoint is not documented or supported by anyone.** It takes no key and no account, and it
 * answers a plainly-identified client — verified live, which is why the User-Agent below names this
 * app instead of imitating some other device. The risk is not access, it is stability: the shape can
 * change without notice, and when it does this must degrade into an ordinary error rather than take
 * anything else with it.
 */
internal class ShazamRecognitionClient(
    okHttpClient: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = SHAZAM_BASE_URL,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val timeZone: () -> String = { ZoneId.systemDefault().id },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Derived from the shared client the way the downloader's is: same connection pool and dispatcher,
     * none of the catalogue caching. A recognition answer must never be served from disk — the same
     * fingerprint asked twice is two separate questions about the room — and the shared User-Agent
     * interceptor would overwrite the one this request sets.
     */
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .cache(null)
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun tag(signature: String, sampleMs: Long): RecognitionOutcome {
        val language = locale().language.takeIf { it.isNotBlank() } ?: FALLBACK_LANGUAGE
        val country = locale().country.takeIf { it.isNotBlank() } ?: FALLBACK_COUNTRY

        val url = "$baseUrl/discovery/v5/$language/$country/android/-/tag/${newId().uppercase()}/${newId()}"
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("sync", "true")
            ?.addQueryParameter("webv3", "true")
            ?.addQueryParameter("sampling", "true")
            ?.addQueryParameter("connected", "")
            ?.addQueryParameter("shazamapiversion", "v3")
            ?.addQueryParameter("sharehub", "true")
            ?.addQueryParameter("video", "v3")
            ?.build()
            ?: return RecognitionOutcome.Failed(RecognitionError.UNKNOWN)

        val timestamp = now()
        val payload = json.encodeToString(
            ShazamTagRequest.serializer(),
            ShazamTagRequest(
                geolocation = ShazamGeolocation.NEUTRAL,
                signature = ShazamSignature(samplems = sampleMs, timestamp = timestamp, uri = signature),
                timestamp = timestamp,
                timezone = timeZone(),
            ),
        )

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", JSON_MEDIA_TYPE)
            .header("Content-Language", "${language}_$country")
            .header("User-Agent", USER_AGENT)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()

        return client.execute(request)
    }

    private fun read(response: Response): RecognitionOutcome {
        when (response.code) {
            // The service answers a fingerprint it doesn't know with a perfectly ordinary 200 and an
            // empty match list; 404 is the rarer spelling of the same thing. Neither is a failure.
            in 200..299 -> Unit
            404 -> return RecognitionOutcome.NoMatch
            429 -> return RecognitionOutcome.Failed(RecognitionError.RATE_LIMITED)
            in 500..599 -> return RecognitionOutcome.Failed(RecognitionError.SERVICE_UNAVAILABLE)
            else -> return RecognitionOutcome.Failed(RecognitionError.INVALID_RESPONSE)
        }

        val body = response.body?.string().orEmpty()
        if (body.isBlank()) return RecognitionOutcome.Failed(RecognitionError.INVALID_RESPONSE)

        val parsed = runCatching { json.decodeFromString(ShazamTagResponse.serializer(), body) }
            .getOrElse { return RecognitionOutcome.Failed(RecognitionError.INVALID_RESPONSE) }

        val match = parsed.toMatch(PROVIDER_ID) ?: return RecognitionOutcome.NoMatch
        return RecognitionOutcome.Matched(match)
    }

    /**
     * Enqueues rather than blocking, so cancelling the coroutine actually cancels the HTTP call.
     *
     * **The body is read inside the callback, not after it.** OkHttp calls back as soon as the headers
     * arrive, so resuming there and reading the body afterwards puts a blocking socket read outside
     * any coroutine's reach: a user who pressed cancel would keep waiting for a body they no longer
     * want, for as long as the service felt like sending it. Read here, and `call.cancel()` closes the
     * socket out from under it — which is exactly what cancellation should do.
     */
    private suspend fun OkHttpClient.execute(request: Request): RecognitionOutcome =
        suspendCancellableCoroutine { continuation ->
            val call = newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Neither the fingerprint nor the body ever reaches a log line, in any build. Only
                    // the shape of the failure is useful, and it is already in the outcome.
                    if (!continuation.isCancelled) continuation.resume(failure(RecognitionError.NETWORK))
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = try {
                        response.use(::read)
                    } catch (e: IOException) {
                        failure(RecognitionError.NETWORK)
                    }
                    if (!continuation.isCancelled) continuation.resume(outcome)
                }
            })
        }

    private fun failure(error: RecognitionError) = RecognitionOutcome.Failed(error)

    internal companion object {
        const val PROVIDER_ID = "shazam"
        const val SHAZAM_BASE_URL = "https://amp.shazam.com"

        /**
         * Stable, and honest about what is calling. Verified against the live endpoint: it answers this
         * exactly as it answers anything else, so there is no reason to pose as a phone model — and
         * rotating through invented device strings would be pretending to be something this is not.
         */
        const val USER_AGENT = "Rizx/0.2.0 (Android; +https://github.com/UlisesFTP/Rizx-Music-Player)"

        private const val JSON_MEDIA_TYPE = "application/json"
        private const val FALLBACK_LANGUAGE = "en"
        private const val FALLBACK_COUNTRY = "US"
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val CALL_TIMEOUT_SECONDS = 30L
    }
}
