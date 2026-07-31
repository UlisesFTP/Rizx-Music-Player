package fm.rizx.player.data.artist

import fm.rizx.player.data.local.store.ArtistBioStore
import fm.rizx.player.data.local.store.StoredArtistBio
import fm.rizx.player.data.remote.wikipedia.WikipediaApi
import fm.rizx.player.data.remote.wikipedia.WikipediaSummaryDto
import fm.rizx.player.domain.model.ArtistBio
import fm.rizx.player.domain.usecase.ArtistNameMatching
import fm.rizx.player.domain.usecase.RecsBlender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Where the artist page's "About" paragraph comes from.
 *
 * An interface because the biography is an *optional* section from an outside source: the screen (and
 * its tests) should depend on the question, not on Wikipedia's answer to it.
 */
interface ArtistBioSource {
    /**
     * The biography for [artistName], or null when none can be trusted. [key] is the artist's
     * `ProviderRef.identityKey`, used as the cache key.
     */
    suspend fun bioFor(key: String, artistName: String): ArtistBio?
}

/**
 * The "About" paragraph on the artist page, from Wikipedia.
 *
 * No catalogue the app uses publishes biographies — Deezer has no such field and iTunes has none
 * either — so this is a separate, keyless, public source. It is **never** allowed to be wrong in the
 * one way that matters: showing someone else's life story under a musician's name.
 *
 * Three gates stand between a search hit and the screen:
 *
 * 1. **It must be a page, not a list.** A `disambiguation` summary is rejected outright.
 * 2. **It must be this artist.** The title is compared with [ArtistNameMatching.sameArtist] after
 *    dropping Wikipedia's parenthetical qualifier — "Ghost (Swedish band)" is Ghost, "Ghost (film)"
 *    is not this artist even though the search returned it first.
 * 3. **It must be about music.** The one-line description ("American rapper", "banda de música",
 *    "groupe de rock") has to carry a musical word in one of the app's four languages. The
 *    parenthetical counts too, since that is often where the qualifier lives.
 *
 * Anything that does not pass all three means **no biography**, which is always better than the wrong
 * one. Every failure is silent: the section simply is not drawn.
 *
 * Answers are cached — including the absences, which is the expensive case: an artist with no article
 * would otherwise cost two requests on every visit, forever.
 */
class WikipediaArtistBioSource(
    private val api: WikipediaApi,
    private val store: ArtistBioStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Injectable so a test isn't at the mercy of the device's language. */
    private val language: () -> String = { Locale.getDefault().language },
    private val now: () -> Instant = { Instant.now() },
) : ArtistBioSource {

    override suspend fun bioFor(key: String, artistName: String): ArtistBio? = withContext(io) {
        if (artistName.isBlank()) return@withContext null

        store.get(key)?.let { cached ->
            if (cached.found) return@withContext ArtistBio(cached.text, cached.url)
            // A recorded absence still expires: an artist without an article today may have one later.
            if (!expired(cached)) return@withContext null
        }

        for (lang in languages()) {
            val found = lookUp(lang, artistName) ?: continue
            store.put(key, StoredArtistBio(text = found.text, url = found.sourceUrl, lang = lang))
            return@withContext found
        }
        store.put(key, StoredArtistBio()) // nothing anywhere: remember that too
        null
    }

    // ---- One language ---------------------------------------------------------------------------

    private suspend fun lookUp(lang: String, artistName: String): ArtistBio? {
        val titles = guarded { api.search(searchUrl(lang, artistName)).query.search.mapNotNull { it.title } }
            .orEmpty()
            // Only candidates whose title really is this artist get a second request spent on them.
            .filter { ArtistNameMatching.sameArtist(it.withoutQualifier(), artistName) }
            .take(MAX_CANDIDATES)

        for (title in titles) {
            val summary = guarded { api.summary(summaryUrl(lang, title)) } ?: continue
            val text = summary.extract?.trim().orEmpty()
            if (text.isBlank() || !accepts(summary, artistName)) continue
            return ArtistBio(text = text, sourceUrl = summary.contentUrls?.desktop?.page)
        }
        return null
    }

    /** All three gates. Kept together so the reason a page is rejected is readable in one place. */
    private fun accepts(summary: WikipediaSummaryDto, artistName: String): Boolean {
        if (summary.type.equals(DISAMBIGUATION, ignoreCase = true)) return false
        val title = summary.title.orEmpty()
        if (title.isNotBlank() && !ArtistNameMatching.sameArtist(title.withoutQualifier(), artistName)) return false
        // The qualifier ("(band)", "(cantante)") is a description in its own right, so it is searched too.
        val about = listOfNotNull(summary.description, title.qualifier(), summary.extract?.take(EXTRACT_SCAN))
            .joinToString(" ")
        return isMusical(about)
    }

    /**
     * True when this reads like a page about a musician, in any of the app's four languages.
     *
     * Matched at a **word boundary**: every keyword carries a leading space and the text is padded, so
     * "abandoned village" is not a band and "adjacent" is not a DJ. Suffixes still match, which is the
     * point — one " music" covers *music*, *musician*, *musical* and *música*.
     */
    private fun isMusical(text: String): Boolean {
        val folded = " " + folder.nameKey(text) + " "
        return MUSIC_WORDS.any { " $it" in folded }
    }

    // ---- URLs -----------------------------------------------------------------------------------

    /** The device's language first, then English — where most artists have an article at all. */
    private fun languages(): List<String> {
        val own = language().lowercase().take(2).takeIf { it in SUPPORTED } ?: FALLBACK
        return if (own == FALLBACK) listOf(FALLBACK) else listOf(own, FALLBACK)
    }

    private fun searchUrl(lang: String, name: String): String =
        "https://$lang.wikipedia.org/w/api.php?action=query&list=search&format=json" +
            "&srlimit=$SEARCH_LIMIT&srsearch=${encode(name)}"

    private fun summaryUrl(lang: String, title: String): String =
        "https://$lang.wikipedia.org/api/rest_v1/page/summary/${encode(title.replace(' ', '_'))}"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun expired(cached: StoredArtistBio): Boolean = runCatching {
        Duration.between(Instant.parse(cached.checkedAtIso), now()) >= RETRY_ABSENT_AFTER
    }.getOrDefault(true)

    /** Network and parsing failures are "no biography", never an error the screen has to handle. */
    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        val folder = RecsBlender()

        const val DISAMBIGUATION = "disambiguation"
        const val SEARCH_LIMIT = 5
        const val MAX_CANDIDATES = 2
        const val EXTRACT_SCAN = 300
        val SUPPORTED = setOf("es", "en", "pt", "fr")
        const val FALLBACK = "en"

        /** An artist with no article today may have one next month — but not next Tuesday. */
        val RETRY_ABSENT_AFTER: Duration = Duration.ofDays(30)

        /**
         * Already folded (lowercase, no accents) to match [RecsBlender.nameKey]'s output, and matched
         * with a leading space (see [isMusical]). Stems where the languages agree: "music" covers
         * *music/música/musicien/musical*, "cantant" covers *cantante/cantantes*, "chanteu" covers
         * *chanteur/chanteuse*.
         */
        val MUSIC_WORDS = listOf(
            "music", "singer", "band", "rapper", "songwriter", "guitarist", "drummer", "bassist",
            "pianist", "composer", "producer", "duo", "trio", "orchestra", "dj",
            "cantant", "cantaut", "banda", "grupo", "conjunto", "agrupacion", "compositor", "rapero",
            "cantor", "cantora", "dupla", "chanteu", "groupe", "musicien", "rappeur", "compositeur",
            "orquesta", "orquestra",
        )
    }
}

/** "Ghost (Swedish band)" → "Ghost". Wikipedia's qualifier is metadata, not part of the name. */
private fun String.withoutQualifier(): String = substringBefore(" (").trim()

/** "Ghost (Swedish band)" → "Swedish band", or empty when the title carries no qualifier. */
private fun String.qualifier(): String =
    substringAfter(" (", missingDelimiterValue = "").substringBeforeLast(")").trim()
