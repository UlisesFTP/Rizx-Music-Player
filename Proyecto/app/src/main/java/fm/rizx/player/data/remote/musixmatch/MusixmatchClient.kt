package fm.rizx.player.data.remote.musixmatch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Thin client for Musixmatch's web endpoints — the source with the largest catalogue of **richsync**
 * (word-by-word) lyrics.
 *
 * **Nothing is embedded here.** Musixmatch's web player signs its own calls with a secret that ships
 * inside its JavaScript bundle and rotates; this reads that bundle at runtime, recovers the secret, and
 * signs the same way (HMAC-SHA256 over `url + yyyyMMdd`). It is the same shape as the keyless Spotify
 * embed scrape this app already does — no API key, no account, no credential in the APK.
 *
 * That also makes it the most fragile provider by construction: a change to their bundle and this stops
 * answering. Every step is failure-tolerant and returns null, so it simply loses its turn in the chain.
 */
class MusixmatchClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    /** The recovered secret, kept for the day it was derived for (the signature is date-stamped). */
    @Volatile
    private var cached: Pair<String, String>? = null // date (yyyyMMdd) to secret

    /** Search results as `(trackId, title, artist, album, durationMs, hasRichsync)`. */
    fun search(query: String, limit: Int): List<MusixmatchTrack> {
        val body = call(
            "track.search",
            "q" to query,
            "page_size" to limit.toString(),
            "page" to "1",
            "s_track_rating" to "desc",
        ) ?: return emptyList()
        val list = body["track_list"]?.jsonArray ?: return emptyList()
        return list.mapNotNull { entry ->
            val track = entry.jsonObject["track"]?.jsonObject ?: return@mapNotNull null
            MusixmatchTrack(
                trackId = track.long("track_id") ?: return@mapNotNull null,
                title = track.string("track_name").orEmpty(),
                artist = track.string("artist_name").orEmpty(),
                album = track.string("album_name"),
                durationMs = track.long("track_length")?.times(1000),
                hasRichsync = (track.long("has_richsync") ?: 0L) > 0L,
            )
        }
    }

    /** The word-by-word body for a track (a JSON string parsed by `RichSyncParser`), or null. */
    fun richSync(trackId: Long): String? =
        call("track.richsync.get", "track_id" to trackId.toString())
            ?.get("richsync")?.jsonObject?.string("richsync_body")

    /** The line-timed LRC subtitle for a track, for songs with no richsync. */
    fun subtitle(trackId: Long): String? =
        call("track.subtitle.get", "track_id" to trackId.toString())
            ?.get("subtitle")?.jsonObject?.string("subtitle_body")

    // ---- Signed transport ----

    /** Runs a signed call and returns `message.body`, or null on any failure (including a non-200 body). */
    private fun call(endpoint: String, vararg params: Pair<String, String>): Map<String, JsonElement>? =
        runCatching {
            val secret = secret() ?: return null
            val query = (params.toList() + BASE_PARAMS).joinToString("&") { (k, v) -> "$k=${v.encode()}" }
            val url = "$API_BASE$endpoint?$query"
            val signed = url + "&signature=${sign(url, secret).encode()}&signature_protocol=sha256"

            val payload = get(signed) ?: return null
            val message = json.parseToJsonElement(payload).jsonObject["message"]?.jsonObject ?: return null
            val status = message["header"]?.jsonObject?.long("status_code")
            if (status != 200L) return null
            message["body"]?.jsonObject
        }.getOrNull()

    /** `base64(HMAC-SHA256(url + yyyyMMdd, secret))` — the scheme their own player uses. */
    private fun sign(url: String, secret: String): String {
        val stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(secret.toByteArray(), HMAC)) }
        return Base64.getEncoder().encodeToString(mac.doFinal((url + stamp).toByteArray()))
    }

    /**
     * The signing secret, read out of the web player's own JavaScript: find the `_app` chunk, pull the
     * encoded literal out of it, reverse it and base64-decode. Cached per day.
     */
    private fun secret(): String? {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        cached?.let { (day, value) -> if (day == today) return value }

        val html = get(SEARCH_PAGE) ?: return null
        val chunk = APP_CHUNK.find(html)?.groupValues?.get(1) ?: return null
        val script = get(if (chunk.startsWith("http")) chunk else SITE + chunk.removePrefix("/")) ?: return null
        val encoded = SECRET_LITERAL.find(script)?.groupValues?.get(1) ?: return null
        val secret = runCatching {
            String(Base64.getDecoder().decode(encoded.reversed()))
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        cached = today to secret
        return secret
    }

    private fun get(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Cookie", "mxm_bab=AB")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string()
        }
    }.getOrNull()

    private fun String.encode(): String = URLEncoder.encode(this, "UTF-8")

    private fun Map<String, JsonElement>.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } }.getOrNull()

    private fun Map<String, JsonElement>.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.content?.toDouble()?.toLong() }.getOrNull()

    private companion object {
        const val SITE = "https://www.musixmatch.com/"
        const val SEARCH_PAGE = "${SITE}search"
        const val API_BASE = "${SITE}ws/1.1/"
        const val HMAC = "HmacSHA256"

        val BASE_PARAMS = listOf("app_id" to "web-desktop-app-v1.0", "format" to "json")

        /** The Next.js bundle that carries the signing secret. */
        val APP_CHUNK = Regex("""src="([^"]*/_next/static/chunks/pages/_app-[^"]+\.js)"""")

        /** `…from("<encoded>".split("").reverse()).join("")` — the secret, written backwards. */
        val SECRET_LITERAL =
            Regex("""from\(\s*"(.*?)"\s*\.split\(\s*""\s*\)\s*\.reverse\(\)\s*\)\.join\(\s*""\s*\)""")
    }
}

/** One search hit. [hasRichsync] tells us whether asking for word timings is worth a request. */
data class MusixmatchTrack(
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val hasRichsync: Boolean,
)
