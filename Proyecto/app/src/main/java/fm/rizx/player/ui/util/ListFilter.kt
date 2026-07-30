package fm.rizx.player.ui.util

import fm.rizx.player.domain.model.Track
import java.text.Normalizer

/**
 * Narrowing a list the app **already holds** — liked songs, downloads, one playlist, the on-device scan.
 *
 * This is not a search: nothing is fetched, nothing is resolved, no provider is asked. The rows are in
 * memory, so filtering them is a local decision that also works offline and cannot fail. Kept pure and
 * Android-free so the matching rule itself can be tested directly.
 *
 * Two things make it forgiving enough to type into quickly:
 *
 * - **Accents fold.** "rosalia" finds "Rosalía" and the other way round — this catalogue is largely Latin,
 *   and a filter that demands the accent is a filter the owner would have to fight.
 * - **Every word must appear, but any field may carry it.** "ice killaz" finds *Natural Born Killaz* by
 *   Ice Cube, which a single substring test over either field never would.
 */
object ListFilter {

    /**
     * True when [query] is blank — nothing to narrow — or when each of its words appears somewhere in
     * [fields]. Nulls are skipped, so a row without an album is matched on what it does have.
     */
    fun matches(query: String, vararg fields: String?): Boolean {
        val words = words(query)
        if (words.isEmpty()) return true
        val haystack = fields.filterNotNull().joinToString(" ") { fold(it) }
        return words.all { it in haystack }
    }

    /** A song is searched by its title, everyone credited on it, and its album. */
    fun matchesTrack(query: String, track: Track): Boolean = matches(
        query,
        track.title,
        track.artists.joinToString(" ") { it.name },
        track.album?.title,
    )

    /** The words to look for: [fold]ed, so the query is compared on the same terms as the rows. */
    fun words(query: String): List<String> = fold(query).split(' ').filter { it.isNotEmpty() }

    /**
     * Lowercased, accent-stripped, punctuation-dropped.
     *
     * Punctuation is **removed** rather than turned into a space: dropping it glues "Hip-Hop" into
     * "hiphop", which the word-containment test above still matches from either "hiphop" or "hip hop",
     * whereas a space would only match the second. Letters of any script survive — `\p{L}` rather than
     * `a-z`, so a Cyrillic or Japanese title stays searchable instead of folding away to nothing.
     */
    fun fold(raw: String): String {
        val flat = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
        return SPACES.replace(NON_ALNUM.replace(flat, ""), " ").trim()
    }

    private val MARKS = Regex("""\p{Mn}+""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N} ]""")
    private val SPACES = Regex("""\s+""")
}
