package fm.rizx.player.data.remote.applemusic

import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The playlist shelves of Apple Music's public **artist** and **curator** pages, keyless.
 *
 * Verified 2026-11-02: `music.apple.com/us/artist/bad-bunny/1126808565` carries an "Artist Playlists"
 * shelf (Essentials, Deep Cuts, set lists — Apple's answer to "This Is"), and a genre curator such as
 * `…/curator/apple-music-pop/976439548` carries ~110 of that genre's playlists.
 *
 * Read from the page's `serialized-server-data` JSON rather than the markup. The markup was tried first
 * (it is what [AppleMusicBrowsePage] does) and on these pages the title nearest a link is often the
 * *previous* card's — one card off for most of the shelf. The JSON has each item's own title, cover
 * template and URL side by side, so nothing has to be guessed.
 *
 * Video playlists and Fitness+ spotlights are dropped: they open, but as something other than music.
 */
class AppleMusicShelfPage(
    private val client: OkHttpClient,
    private val json: Json,
) {

    /**
     * The slug in both URLs is a placeholder: Apple redirects any slug to the canonical one (verified),
     * which spares encoding localized slugs such as `apple-music-música-mexicana`. Not every curator
     * exists in every storefront (Urbano Latino answers 404 on `/mx/`), so callers fall back to `us`.
     */
    fun artistPlaylists(storefront: String, artistId: String): List<PlaylistRef> =
        parse(fetch("https://music.apple.com/$storefront/artist/x/$artistId"), storefront)

    fun curatorPlaylists(storefront: String, curatorId: String): List<PlaylistRef> =
        parse(fetch("https://music.apple.com/$storefront/curator/x/$curatorId"), storefront)

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) "" else response.body?.string().orEmpty()
        }
    }

    internal fun parse(html: String, storefront: String): List<PlaylistRef> {
        val blob = SERVER_DATA.find(html)?.groupValues?.get(1) ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(blob) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, PlaylistRef>()
        walk(root) { item -> item.toPlaylistRef(storefront)?.let { out.putIfAbsent(it.id, it) } }
        return out.values.toList()
    }

    /** Depth-first, so shelves come out in page order. */
    private fun walk(element: JsonElement, visit: (JsonObject) -> Unit) {
        when (element) {
            is JsonObject -> {
                visit(element)
                element.values.forEach { walk(it, visit) }
            }
            is JsonArray -> element.forEach { walk(it, visit) }
            else -> Unit
        }
    }

    /**
     * A shelf item: a `contentDescriptor` of kind playlist **and** a title of its own. The second
     * condition is what skips the item's nested play/segue actions, which repeat the descriptor
     * without a title.
     */
    private fun JsonObject.toPlaylistRef(storefront: String): PlaylistRef? {
        val descriptor = this["contentDescriptor"] as? JsonObject ?: return null
        if (descriptor.string("kind") != "playlist") return null
        val url = descriptor.string("url") ?: return null
        val match = PLAYLIST_URL.find(url) ?: return null
        val slug = match.groupValues[1]
        val playlistId = match.groupValues[2]
        if (EXCLUDED.any { slug.contains(it) }) return null
        val title = string("title")
            ?: ((this["titleLinks"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("title")
            ?: return null
        val template = ((this["artwork"] as? JsonObject)?.get("dictionary") as? JsonObject)?.string("url")
        return PlaylistRef(
            id = playlistId,
            name = title.trim(),
            artwork = template?.let(::artworkSet),
            source = ProviderRef(
                AppleMusicIds.PROVIDER,
                "playlist:$playlistId",
                "https://music.apple.com/$storefront/playlist/$slug/$playlistId",
            ),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Apple's template takes any square size: `{w}x{h}…{f}`. */
    private fun artworkSet(template: String): ArtworkSet {
        fun sized(px: Int) = template.replace("{w}", "$px").replace("{h}", "$px").replace("{c}", "cc").replace("{f}", "jpg")
        return ArtworkSet(
            listOf(
                Artwork(url = sized(COVER_PX), width = COVER_PX, height = COVER_PX, purpose = ArtworkPurpose.COVER),
                Artwork(url = sized(THUMB_PX), width = THUMB_PX, height = THUMB_PX, purpose = ArtworkPurpose.THUMBNAIL),
            ),
        )
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        val SERVER_DATA = Regex("""<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val PLAYLIST_URL = Regex("""/playlist/([^/?#]+)/(pl\.[a-z0-9]+)""")
        val EXCLUDED = listOf("video", "fitness")
        const val COVER_PX = 600
        const val THUMB_PX = 300
    }
}
