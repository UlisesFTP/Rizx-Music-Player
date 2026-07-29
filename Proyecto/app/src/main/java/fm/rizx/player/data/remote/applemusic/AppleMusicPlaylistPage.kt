package fm.rizx.player.data.remote.applemusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** One row of an Apple editorial playlist as the page publishes it: an id, a title, a duration. */
data class ApplePlaylistRow(val trackId: String, val title: String, val durationMs: Long?)

/** An Apple editorial playlist read off its public page. */
data class ApplePlaylistPage(val name: String, val rows: List<ApplePlaylistRow>)

/**
 * Reads an Apple Music editorial playlist ("Today's Hits", "Top 100: Global") from the **JSON-LD the
 * page publishes** — a `schema.org/MusicPlaylist` block, the same structured data any search engine
 * reads. Keyless: no token, no developer key, nothing bypassed (ADR 0018).
 *
 * The rows are deliberately *thin*. Apple's JSON-LD gives each track a name, a duration and a URL
 * ending in its catalogue id — but **no artist**, and its `thumbnailUrl` is a 1200×630 social-card
 * crop rather than a cover. So this returns ids, and the caller goes back to the owning catalogue
 * (`AppleMusicMetadataProvider.trackDetails`, one batched lookup) for the real artist, album and
 * square artwork. That round trip is the whole reason the playlist can be shown correctly at all.
 */
class AppleMusicPlaylistPage(
    private val client: OkHttpClient,
    private val json: Json,
) {

    /** Fetches and parses [url], or null when the page isn't a playlist we can read. */
    fun fetch(url: String): ApplePlaylistPage? {
        val html = runCatching {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        }.getOrNull() ?: return null
        return parse(html)
    }

    internal fun parse(html: String): ApplePlaylistPage? {
        val block = LD_JSON.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return null
        val root = runCatching { json.parseToJsonElement(block) }.getOrNull() ?: return null
        val obj = when (root) {
            is JsonObject -> root
            is JsonArray -> root.firstOrNull { (it as? JsonObject)?.type() == "MusicPlaylist" } as? JsonObject
            else -> null
        } ?: return null
        if (obj.type() != "MusicPlaylist") return null

        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val rows = (obj["track"] as? JsonArray).orEmpty().mapNotNull { element ->
            runCatching {
                val row = element.jsonObject
                val trackUrl = row["url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val id = TRACK_ID.find(trackUrl)?.groupValues?.get(1) ?: return@runCatching null
                ApplePlaylistRow(
                    trackId = id,
                    title = row["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    durationMs = row["duration"]?.jsonPrimitive?.contentOrNull?.let(::isoDurationMs),
                )
            }.getOrNull()
        }
        return if (rows.isEmpty()) null else ApplePlaylistPage(name, rows)
    }

    private fun JsonObject.type(): String? = this["@type"]?.jsonPrimitive?.contentOrNull

    /** `PT3M33S` → milliseconds. Null when absent or malformed — duration is a nicety here. */
    internal fun isoDurationMs(raw: String): Long? {
        val m = ISO_DURATION.matchEntire(raw.trim()) ?: return null
        val (h, min, s) = m.destructured
        val total = (h.toLongOrNull() ?: 0) * 3_600 + (min.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
        return total.takeIf { it > 0 }?.times(1_000)
    }

    private companion object {
        /** A desktop UA: Apple serves the JSON-LD block only to something that looks like a browser. */
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        val LD_JSON = Regex("""<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val TRACK_ID = Regex("""/song/[^/]*/(\d+)""")
        val ISO_DURATION = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")
    }
}
