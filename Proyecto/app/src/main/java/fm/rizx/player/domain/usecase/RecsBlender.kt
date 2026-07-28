package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.Track
import java.text.Normalizer

/**
 * Pure blending of the multi-source Home sections: **cross-source dedup where the Deezer copy always
 * wins** (owner rule), then a weighted interleave (weighted fair queuing) so every source shows up in
 * rough proportion to its weight instead of being concatenated block-by-block. No Android, no IO.
 *
 * Key normalization is deliberately conservative: lowercase, diacritics stripped, `(feat …)`/`ft.`
 * tails dropped, punctuation flattened — but `remix`/`live`/`acoustic`/`remaster` markers are **kept**,
 * because those distinguish genuinely different recordings and merging them would be wrong.
 */
class RecsBlender(
    private val weights: Map<String, Double> = DEFAULT_WEIGHTS,
) {

    fun blendTracks(sections: List<AttributedResult<Track>>): List<Track> =
        blend(sections) { trackKey(it) }

    fun blendAlbums(sections: List<AttributedResult<AlbumRef>>): List<AlbumRef> =
        blend(sections, ::albumKey)

    fun blendArtists(sections: List<AttributedResult<ArtistRef>>): List<ArtistRef> =
        blend(sections, ::artistKey)

    fun blendPlaylists(sections: List<AttributedResult<PlaylistRef>>): List<PlaylistRef> =
        blend(sections, ::playlistKey)

    // The four identity keys. Public because the Home feed dedups *across rows* with the very same
    // notion of "the same thing" this blender uses within a row (see HomeFeedDeduper).

    /** The dedup key of a track: normalized `title|primary artist`. */
    fun trackKey(track: Track): String =
        normalize(track.title) + "|" + normalize(track.artists.firstOrNull()?.name.orEmpty())

    fun albumKey(album: AlbumRef): String =
        normalize(album.title) + "|" + normalize(album.artists.firstOrNull()?.name.orEmpty())

    fun artistKey(artist: ArtistRef): String = nameKey(artist.name)

    /**
     * The same accent- and punctuation-folding the dedup keys use, on a bare name. Exposed so anything
     * matching a name against a provider's search results ([ResolveArtistRefUseCase]) agrees with the
     * feed on what counts as "the same artist".
     */
    fun nameKey(raw: String): String = normalize(raw)

    fun playlistKey(playlist: PlaylistRef): String = normalize(playlist.name)

    /**
     * Dedup in preference order (Deezer first, then heavier weights), then interleave by weighted
     * fair queuing: each step takes from the source with the smallest virtual finish time
     * `(taken+1)/weight`, which realizes the weights exactly without fixed windows.
     */
    private fun <T> blend(sections: List<AttributedResult<T>>, keyOf: (T) -> String): List<T> {
        if (sections.isEmpty()) return emptyList()

        val ordered = sections.sortedByDescending { weightOf(it.providerId) }
        val seen = mutableSetOf<String>()
        val queues = ordered.map { section ->
            section.providerId to section.items
                .filter { seen.add(keyOf(it)) } // first source to claim a key keeps it → Deezer wins
                .toMutableList()
        }

        val taken = queues.associate { it.first to 0 }.toMutableMap()
        val out = ArrayList<T>(queues.sumOf { it.second.size })
        while (queues.any { it.second.isNotEmpty() }) {
            val (providerId, queue) = queues
                .filter { it.second.isNotEmpty() }
                .minByOrNull { (id, _) -> (taken.getValue(id) + 1) / weightOf(id) }!!
            out += queue.removeAt(0)
            taken[providerId] = taken.getValue(providerId) + 1
        }
        return out
    }

    private fun weightOf(providerId: String): Double =
        weights[providerId] ?: DEFAULT_WEIGHT

    private fun normalize(raw: String): String {
        val lower = raw.lowercase().trim()
        val noFeat = PAREN_FEAT.replace(lower, "").let { TAIL_FEAT.replace(it, "") }
        val flat = Normalizer.normalize(noFeat, Normalizer.Form.NFD).replace(MARKS, "")
        val cleaned = NON_ALNUM.replace(flat, " ")
        return SPACES.replace(cleaned, " ").trim().ifEmpty { lower }
    }

    companion object {
        /**
         * Owner-set source weights for the Home blend. Deezer leads (it is also the dedup-preferred
         * source); YouTube's share of the recommendation surface lives in the For-you Mix rows
         * rather than in these chart sections.
         */
        val DEFAULT_WEIGHTS: Map<String, Double> = mapOf(
            "deezer-dashboard" to 0.40,
            "spotify-charts" to 0.20,
            "applemusic-charts" to 0.15,
        )

        /** Unknown/future sources still get through, just with the smallest voice. */
        private const val DEFAULT_WEIGHT = 0.10

        private val PAREN_FEAT = Regex("""\s*[(\[](?:feat\.?|ft\.?|with)\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
        private val TAIL_FEAT = Regex("""\s+(?:feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)
        private val MARKS = Regex("""\p{Mn}+""")
        private val NON_ALNUM = Regex("""[^a-z0-9 ]""")
        private val SPACES = Regex("""\s+""")
    }
}
