package fm.rizx.player.domain.lyrics

import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.usecase.ArtistNameMatching
import java.text.Normalizer
import kotlin.math.abs

/** A lyric candidate reduced to the four things worth matching on. Every provider maps its DTO to this. */
data class LyricsMatchTarget(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    /** Whether this candidate carries timings — a tie-break, not a gate. */
    val synced: Boolean = true,
)

/**
 * Picks the lyric that belongs to *this* recording.
 *
 * Every provider used to choose by duration alone — `minByOrNull { abs(theirs - ours) }` — which is fine
 * until two recordings of the same song are the same length. Then a live take, a sped-up edit or a remix
 * wins, the words are right and the timings are nonsense, and the karaoke sweep makes that far more
 * obvious than the old static highlight ever did.
 *
 * So version tags are a **hard gate**: if we are playing a remix and the candidate isn't one (or the
 * reverse), it is not the same recording and no amount of duration agreement changes that. Everything
 * else is a score, lowest wins, in the same milliseconds the providers already used.
 *
 * Pure Kotlin, no Android, and it reuses [ArtistNameMatching] so a YouTube channel credit
 * ("ModjoOfficial") matches the artist a lyrics database knows.
 */
object LyricsTrackMatcher {

    /** Unknown duration is a real cost, not a free pass — same figure the providers already used. */
    const val UNKNOWN_DURATION_PENALTY = 60_000L

    /** A line-timed answer is worth having, but never at the price of the right recording. */
    const val UNSYNCED_PENALTY = 30_000L

    /** Wrong artist, when we can tell. Below the version gate: a featured credit shouldn't be fatal. */
    private const val WRONG_ARTIST_PENALTY = 120_000L

    /** Wrong title. Same idea — punished hard, not rejected, because titles are punctuated freely. */
    private const val WRONG_TITLE_PENALTY = 90_000L

    /**
     * How this recording differs from the ordinary studio version. Empty means "the normal one".
     *
     * Only qualifiers count — text in brackets, or after a dash — so a song actually *called* "Live and
     * Let Die" isn't read as a live recording.
     */
    fun versionTags(title: String): Set<String> {
        val qualifiers = QUALIFIER.findAll(title).map { it.value }.joinToString(" ")
        val tail = title.substringAfter(" - ", missingDelimiterValue = "")
        // Spaces go too, so "sped up" and "spedup" — both spellings are everywhere — read the same.
        val haystack = fold("$qualifiers $tail").replace(" ", "")
        return VERSION_WORDS.filterTo(mutableSetOf()) { it in haystack }
    }

    /**
     * How badly [target] fits [track]: lower is better, `null` means "not this recording".
     *
     * A `null` is only ever returned for a version mismatch. Everything else — a missing duration, an
     * artist we can't line up, prose instead of timings — is expensive but still eligible, because the
     * alternative to a mediocre match is often no lyrics at all.
     */
    fun score(track: Track, target: LyricsMatchTarget): Long? {
        val ours = versionTags(track.title)
        val theirs = versionTags(target.title)
        if (ours != theirs) return null

        var score = 0L
        score += durationDrift(track.durationMs, target.durationMs)
        if (!target.synced) score += UNSYNCED_PENALTY

        val artists = track.artists.map { it.name }
        if (artists.isNotEmpty() && target.artist.isNotBlank() &&
            artists.none { ArtistNameMatching.sameArtist(it, target.artist) }
        ) {
            score += WRONG_ARTIST_PENALTY
        }
        if (target.title.isNotBlank() && !sameTitle(track.title, target.title)) {
            score += WRONG_TITLE_PENALTY
        }
        return score
    }

    /** The best of [targets] for [track], or `null` when every one of them is a different recording. */
    fun <T> bestOf(track: Track, targets: List<T>, asTarget: (T) -> LyricsMatchTarget): T? {
        var best: T? = null
        var bestScore = Long.MAX_VALUE
        for (candidate in targets) {
            val score = score(track, asTarget(candidate)) ?: continue
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    /** True when two titles name the same song once qualifiers and punctuation are set aside. */
    fun sameTitle(a: String, b: String): Boolean {
        val left = fold(stripQualifiers(a))
        val right = fold(stripQualifiers(b))
        if (left.isEmpty() || right.isEmpty()) return true
        // Containment, not equality: "Yellow" vs "Yellow (2000 Remaster)" already agreed on their tags,
        // and one side keeping a subtitle the other dropped is not a different song.
        return left == right || left.contains(right) || right.contains(left)
    }

    private fun durationDrift(ours: Long?, theirs: Long?): Long = when {
        ours == null -> 0L
        theirs == null -> UNKNOWN_DURATION_PENALTY
        else -> abs(theirs - ours)
    }

    private fun stripQualifiers(title: String): String =
        QUALIFIER.replace(title, " ").substringBefore(" - ")

    /** Lowercase, accent-free, letters and digits only — so punctuation can't split a match. */
    private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase()
        .filter { it.isLetterOrDigit() || it == ' ' }

    /** Bracketed asides: `(Live)`, `[Remix]`, `- 2011 Remaster` is handled separately. */
    private val QUALIFIER = Regex("""[(\[][^)\]]*[)\]]""")

    private val DIACRITICS = Regex("\\p{Mn}+")

    /**
     * The differences that make a lyric's *timings* wrong even when its words are right.
     *
     * Folded and space-free, so they are matched against folded, space-free text. "spedup" and "slowed"
     * cover the edits that flood YouTube and are exactly the ones whose duration lands closest to the
     * original, which is what makes duration-only matching pick them.
     *
     * Deliberately short. No "remastered" (it contains "remaster", so the two spellings would stop
     * agreeing), no "edit" (it is inside "edition"), and no "cover" or "demo" — a different take usually
     * has the same words *and* roughly the right pace, so rejecting it costs more than it saves.
     */
    private val VERSION_WORDS = listOf(
        "live", "remix", "acoustic", "spedup", "slowed", "remaster",
        "instrumental", "karaoke", "nightcore",
    )
}
