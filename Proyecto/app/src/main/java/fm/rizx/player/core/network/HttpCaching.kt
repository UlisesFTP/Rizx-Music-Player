package fm.rizx.player.core.network

import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Hosts whose GET responses are worth caching on disk.
 *
 * An allowlist rather than "everything", deliberately: the same [okhttp3.OkHttpClient] also backs the
 * NewPipe downloader, whose YouTube endpoints carry short-lived tokens and must never be replayed.
 * These four are the keyless, idempotent catalogue endpoints the Home and the artwork enricher hammer.
 */
private val CACHEABLE_HOSTS = setOf(
    "api.deezer.com",
    "itunes.apple.com",
    "rss.marketingtools.apple.com",
    "open.spotify.com",
)

/** Ten minutes: long enough that a relaunch and a scroll are free, short enough that charts stay current. */
private const val FRESH_SECONDS = 10 * 60

/** Offline, a week-old chart still beats an empty screen. */
private const val OFFLINE_MAX_STALE_DAYS = 7

/**
 * Makes catalogue responses cacheable.
 *
 * Deezer, iTunes and Apple's RSS all answer with `Cache-Control: no-cache` (or nothing at all), so
 * OkHttp's disk cache — which honours the server — stored nothing and every launch re-fetched the same
 * charts and the same cover-art searches. This is a **network** interceptor, so it rewrites the header
 * on the way in, before the cache decides whether to keep the response.
 *
 * Only applied to successful GETs on [CACHEABLE_HOSTS]; everything else passes through untouched.
 */
class CatalogueCacheControlInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.method != "GET" || request.url.host !in CACHEABLE_HOSTS || !response.isSuccessful) {
            return response
        }
        return response.newBuilder()
            .removeHeader("Pragma") // `Pragma: no-cache` would veto the Cache-Control below
            .header("Cache-Control", "public, max-age=$FRESH_SECONDS")
            .build()
    }
}

/**
 * Serves a stale cached copy when the network is unreachable.
 *
 * Rather than inspecting connectivity up front (which lies: "connected" to a captive portal is not
 * reachable), this simply retries a failed GET against the cache alone. If nothing is stored, OkHttp
 * answers `504` for an unsatisfiable `only-if-cached` request and the original [IOException] is
 * rethrown — so callers see the real failure, not a fake empty success.
 */
class OfflineCacheFallbackInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (request.method != "GET") throw e
            val fromCache = request.newBuilder()
                .cacheControl(
                    CacheControl.Builder()
                        .onlyIfCached()
                        .maxStale(OFFLINE_MAX_STALE_DAYS, TimeUnit.DAYS)
                        .build(),
                )
                .build()
            val response = runCatching { chain.proceed(fromCache) }.getOrElse { throw e }
            if (response.code == HTTP_UNSATISFIABLE_REQUEST) {
                response.close()
                throw e
            }
            response
        }
    }

    private companion object {
        /** What OkHttp returns when `only-if-cached` cannot be satisfied. */
        const val HTTP_UNSATISFIABLE_REQUEST = 504
    }
}
