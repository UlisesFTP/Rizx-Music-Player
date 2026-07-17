package fm.rizx.player.data.remote.audius

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves and caches a healthy Audius discovery-node host. Audius has no fixed base URL: `GET`
 * [DISCOVERY_URL] returns a rotating list of gateway hosts. We pick the first, cache it, and re-discover
 * on failure ([markBad]). If discovery itself fails, we fall back to [DISCOVERY_URL]'s host (which also
 * proxies the `/v1` API), so playback degrades gracefully instead of dying.
 */
class AudiusHostProvider(
    private val api: AudiusApi,
    private val discoveryUrl: String = DISCOVERY_URL,
    private val fallbackHost: String = FALLBACK_HOST,
) {
    private val mutex = Mutex()
    private var cached: String? = null

    /** Current base host (e.g. `https://discoveryprovider.audius.co`), discovering + caching on first use. */
    suspend fun host(): String = mutex.withLock {
        cached ?: discover().also { cached = it }
    }

    /** Marks [host] unhealthy so the next [host] call re-discovers. */
    suspend fun markBad(host: String) = mutex.withLock {
        if (cached == host) cached = null
    }

    private suspend fun discover(): String =
        runCatching { api.hosts(discoveryUrl).data.firstOrNull { it.isNotBlank() } }
            .getOrNull()
            ?.trimEnd('/')
            ?: fallbackHost

    companion object {
        const val DISCOVERY_URL = "https://api.audius.co"
        const val FALLBACK_HOST = "https://api.audius.co"
    }
}
