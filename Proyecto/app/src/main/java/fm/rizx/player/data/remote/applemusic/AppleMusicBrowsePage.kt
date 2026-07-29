package fm.rizx.player.data.remote.applemusic

import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Discovers Apple Music's **"Top 100" chart playlists** — Global plus one per country — from the
 * links its public browse page publishes.
 *
 * Discovered rather than hard-coded on purpose. The list is long and grows (20+ countries and
 * counting), and a table baked into the app would quietly rot the first time Apple added or renamed a
 * storefront. One request an hour always reflects what Apple currently offers.
 *
 * Verified live: the ids are **stable across storefronts** — `top-100-global` resolves to the same
 * `pl.…` id whether the page is fetched from `/us/`, `/mx/` or `/es/`; only the slug is localised. So
 * one fetch of the user's own storefront yields correctly-named entries for every country.
 *
 * Each lockup on the page carries its cover and its already-localised title (`alt="Top 100: Mexico"`),
 * which is why this scrapes the page rather than deriving names from slugs: Apple's own wording beats
 * anything reconstructed from `top-100-south-korea`.
 */
class AppleMusicBrowsePage(
    private val client: OkHttpClient,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = 60 * 60_000L,
) {

    @Volatile
    private var cache: Cached? = null

    /** The country charts for [storefront], Global first. Empty (briefly cached) when unreachable. */
    fun topCharts(storefront: String): List<PlaylistRef> {
        cache?.takeIf { it.storefront == storefront && nowMs() - it.atMs < ttlMs }?.let { return it.refs }
        val refs = runCatching { parse(fetchHtml(storefront), storefront) }.getOrDefault(emptyList())
        // A failure is remembered too, so a flaky network doesn't retry on every single Home load.
        cache = Cached(storefront, nowMs(), refs)
        return refs
    }

    private fun fetchHtml(storefront: String): String {
        val request = Request.Builder()
            .url("https://music.apple.com/$storefront/browse")
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) "" else response.body?.string().orEmpty()
        }
    }

    /**
     * Walks the page link by link and, for each, looks **backwards** over the enclosing lockup for its
     * title and cover. Deliberately not one giant regex spanning the markup: the lockup's internals
     * change with every Apple redesign, and a scan that simply gives up on the nearest fields degrades
     * to "chart without a cover" instead of matching nothing at all.
     */
    internal fun parse(html: String, storefront: String): List<PlaylistRef> {
        if (html.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        val refs = mutableListOf<PlaylistRef>()

        for (link in TOP_100_LINK.findAll(html)) {
            val slug = link.groupValues[1]
            val playlistId = link.groupValues[2]
            if (!seen.add(playlistId)) continue

            val lockup = html.substring(maxOf(0, link.range.first - LOOKBEHIND), link.range.first)
            val title = nearestTitle(lockup) ?: displayName(slug)

            refs += PlaylistRef(
                id = playlistId,
                name = title,
                artwork = ARTWORK.findAll(lockup).lastOrNull()?.value?.let(::artworkSet),
                source = ProviderRef(
                    AppleMusicIds.PROVIDER,
                    "playlist:$playlistId",
                    "https://music.apple.com/$storefront/playlist/$slug/$playlistId",
                ),
            )
        }
        // Global first — the one chart every user has a reason to open — then Apple's own ordering,
        // which puts the major storefronts ahead of the long alphabetical tail.
        return refs.sortedByDescending { it.name.contains(GLOBAL, ignoreCase = true) }
    }

    /**
     * The title nearest to (and before) a link, in whichever of the page's **two** shapes it appears.
     *
     * Verified live: only about a third of the charts are rendered as HTML lockups with `alt="…"`.
     * The rest live in a serialised JSON blob as `"title":"Top 100: Spain"`, and reading only the
     * first shape silently fell back to slug-derived names for two thirds of the list. Neither shape
     * carries a cover for the JSON entries — those tiles are drawn typographically instead.
     */
    private fun nearestTitle(lockup: String): String? {
        val candidates = ALT.findAll(lockup).map { it.range.first to it.groupValues[1] } +
            JSON_TITLE.findAll(lockup).map { it.range.first to it.groupValues[1] }
        return candidates
            .filter { it.second.isNotBlank() }
            .maxByOrNull { it.first }
            ?.second
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /** Apple serves any square size from the same path, so the thumb is resized rather than re-found. */
    private fun artworkSet(url: String): ArtworkSet = ArtworkSet(
        listOfNotNull(
            Artwork(url = SIZE.replace(url, "${COVER_PX}x${COVER_PX}cc"), width = COVER_PX, height = COVER_PX, purpose = ArtworkPurpose.COVER),
            Artwork(url = SIZE.replace(url, "${THUMB_PX}x${THUMB_PX}cc"), width = THUMB_PX, height = THUMB_PX, purpose = ArtworkPurpose.THUMBNAIL),
        ),
    )

    /** Fallback when the lockup gave no title: `top-100-south-korea` → `Top 100: South Korea`. */
    internal fun displayName(slug: String): String {
        val country = slug.removePrefix("top-100").trim('-')
        if (country.isEmpty()) return "Top 100"
        val words = country.split('-').filter { it.isNotEmpty() }.joinToString(" ") { word ->
            when (word) {
                in ACRONYMS -> word.uppercase()
                in LOWERCASE_JOINERS -> word
                else -> word.replaceFirstChar { it.uppercase() }
            }
        }
        return "Top 100: $words"
    }

    private class Cached(val storefront: String, val atMs: Long, val refs: List<PlaylistRef>)

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        val TOP_100_LINK = Regex("""/playlist/(top-100[a-z0-9\-]*)/(pl\.[a-z0-9]+)""")
        val ALT = Regex("""alt="([^"]{1,80})"""")
        val JSON_TITLE = Regex(""""title":"([^"]{1,80})"""")
        val ARTWORK = Regex("""https://is\d-ssl\.mzstatic\.com/image/thumb/[^"\s]+?\d+x\d+cc[^"\s]*?\.jpg""")
        val SIZE = Regex("""\d+x\d+cc""")

        /** How far back a lockup's own title and cover sit from its link, in characters. */
        const val LOOKBEHIND = 2_000

        const val GLOBAL = "global"
        const val COVER_PX = 632
        const val THUMB_PX = 316
        val ACRONYMS = setOf("usa", "uk", "uae")
        val LOWERCASE_JOINERS = setOf("and", "of", "the")
    }
}
