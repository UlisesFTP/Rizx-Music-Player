package fm.rizx.player.data.remote.spotify

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Pages a public Spotify playlist past the 100 rows its embed ships, via the same **pathfinder** GraphQL
 * gateway the web player uses. See [SpotifyPathfinderResponse] for why this stays inside the keyless rule.
 *
 * Two published constants make it work, and each has a recovery path because each can rotate:
 * - the **anonymous bearer**, lifted from the embed page the provider already fetches for the playlist's
 *   name and cover — so no extra request is spent getting it;
 * - the **persisted-query hash**. Spotify accepts persisted queries only (a raw GraphQL document is
 *   rejected with "Missing extensions in the request"), and the hash changes when the web player is
 *   redeployed. [KNOWN_HASH] is tried first because it costs nothing; only when the gateway rejects it
 *   does the client go read the current one out of the public bundle and cache it for the process.
 *
 * Failure is never fatal here: every entry point returns null rather than throwing, because the caller
 * already holds the embed's first 100 tracks and a partial import beats a failed one.
 */
class SpotifyPathfinderClient(
    private val client: OkHttpClient,
    private val json: Json,
    /** Overridden by tests to point at a local server. */
    private val queryUrl: String = QUERY_URL,
    private val webPlayerUrl: String = WEB_PLAYER_URL,
) {

    /** Cached for the process: re-scraping the bundle on every page would cost ~1 MB per request. */
    @Volatile
    private var hash: String = KNOWN_HASH

    /**
     * One page of [playlistId] starting at [offset]. Returns null when the gateway cannot serve it —
     * including after a hash refresh, which is attempted exactly once per call.
     */
    fun contents(playlistId: String, token: String, offset: Int, limit: Int): SpotifyPathfinderContent? {
        query(playlistId, token, offset, limit, hash)?.let { return it }
        // A stale hash is the one failure worth paying to recover from: the alternative is silently
        // handing the user 100 tracks of a 900-track playlist for as long as the constant stays wrong.
        val fresh = scrapeHash(playlistId)?.takeIf { it != hash } ?: return null
        hash = fresh
        return query(playlistId, token, offset, limit, fresh)
    }

    private fun query(
        playlistId: String,
        token: String,
        offset: Int,
        limit: Int,
        queryHash: String,
    ): SpotifyPathfinderContent? = runCatching {
        val url = queryUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("operationName", OPERATION)
            ?.addQueryParameter(
                "variables",
                """{"uri":"spotify:playlist:$playlistId","offset":$offset,"limit":$limit}""",
            )
            ?.addQueryParameter(
                "extensions",
                """{"persistedQuery":{"version":1,"sha256Hash":"$queryHash"}}""",
            )
            ?.build() ?: return null
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null // 400 = unknown operation, i.e. the hash rotated
            val body = readBounded(response, MAX_QUERY_BYTES) ?: return null
            json.decodeFromString(SpotifyPathfinderResponse.serializer(), body)
                .data?.playlistV2?.content
                ?.takeIf { it.items.isNotEmpty() }
        }
    }.getOrNull()

    /**
     * Reads the current persisted-query hash out of the public web-player bundle: the playlist page names
     * its script, the script spells the operation immediately before its hash
     * (`…l("fetchPlaylistContents","query","<64 hex>"…`). Both regexes are deliberately narrow so a miss
     * returns null instead of feeding a wrong 64-hex string to the gateway.
     *
     * The bundle URL is only listed on a real content page — the site root serves a shell without it —
     * so the page being imported doubles as the lookup.
     */
    private fun scrapeHash(playlistId: String): String? = runCatching {
        val page = get(webPlayerUrl.format(playlistId), MAX_PAGE_BYTES) ?: return null
        val bundleUrl = BUNDLE_URL.find(page)?.value ?: return null
        val bundle = get(bundleUrl, MAX_BUNDLE_BYTES) ?: return null
        val at = bundle.indexOf(OPERATION).takeIf { it >= 0 } ?: return null
        // Search *forward* from the name: the entry right before it is a different operation with its
        // own hash, and scanning backwards would happily return that one instead.
        val match = SHA256.find(bundle, at) ?: return null
        match.value.takeIf { match.range.first < at + HASH_WINDOW }
    }.getOrNull()

    private fun get(url: String, maxBytes: Long): String? =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) null else readBounded(response, maxBytes)
        }

    /** Same bounded-read shape as the other import readers: a hostile length can't be allocated. */
    private fun readBounded(response: okhttp3.Response, maxBytes: Long): String? {
        val source = response.body?.source() ?: return null
        if (source.request(maxBytes + 1L)) throw IOException("spotify response too large")
        return source.readUtf8()
    }

    companion object {
        const val QUERY_URL = "https://api-partner.spotify.com/pathfinder/v1/query"

        /** `%s` = playlist id. A content page, not the root — only it lists the bundle. */
        const val WEB_PLAYER_URL = "https://open.spotify.com/playlist/%s"

        private const val OPERATION = "fetchPlaylistContents"

        /**
         * The hash the web player shipped when this was written, verified live against a 200-track
         * playlist. Only a starting guess — [scrapeHash] takes over the moment Spotify rotates it.
         */
        const val KNOWN_HASH = "86dde7b9d9356e2369414647cf6950cfed96e778e129cfdfc99aea6c1613b3b0"

        private val BUNDLE_URL =
            Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/web-player\.[0-9a-f]+\.js""")
        private val SHA256 = Regex("""[0-9a-f]{64}""")
        private const val HASH_WINDOW = 200

        private const val MAX_QUERY_BYTES = 8L * 1024 * 1024
        private const val MAX_PAGE_BYTES = 4L * 1024 * 1024
        private const val MAX_BUNDLE_BYTES = 16L * 1024 * 1024
    }
}
