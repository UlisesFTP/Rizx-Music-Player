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

    /**
     * Wrong artist, in the one case it is survivable: our own credit is a YouTube channel and the real
     * artist is written in our title instead (see [artistFits]). Any other wrong artist is rejected.
     */
    private const val WRONG_ARTIST_PENALTY = 120_000L

    /**
     * Beyond this a *timed* candidate is a different cut — a radio edit against the album version, an
     * extended mix — and its timings would drift a verse out by the end. Prose is still eligible: the
     * words of a longer cut are the right words. Live and remix are separate gates; this is what
     * catches the "Intro + Spring Day" medley KuGou files under a track called Intro.
     */
    const val MAX_TIMED_DRIFT_MS = 45_000L

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
     * The language this recording is *sung in*, when the title declares one.
     *
     * A re-recording in another language is the same song, by the same artist, and — this is what makes
     * it lethal here — the same length. "DNA" and "DNA (Japanese Version)" differ by 573 ms, so every
     * signal [score] has agrees with the wrong one; only the title can tell them apart. NetEase and
     * KuGou are Japanese/Chinese-market catalogues, so their copy of a K-pop song is very often the
     * Japanese re-recording, and it is the copy that carries word timings.
     *
     * A qualifier counts when it is a language *and nothing else* ("(Korean)"), or when it carries a
     * version marker as well ("(Japanese Ver.)", "- 日本語版"). Both halves are needed: "French Kiss" is
     * a song, and tagging it would reject the one candidate that is actually right.
     */
    fun languageTags(title: String): Set<String> {
        val parts = QUALIFIER.findAll(title).map { it.value }.toMutableList()
        title.substringAfter(" - ", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.let { parts += it }

        val tags = mutableSetOf<String>()
        for (part in parts) {
            val folded = tighten(part)
            if (folded.isEmpty()) continue
            val versioned = VERSION_MARKERS.any { it in folded }
            for ((tag, spelling) in LANGUAGE_SPELLINGS) {
                if (folded == spelling || (versioned && spelling in folded)) tags += tag
            }
        }
        // Not every catalogue brackets its qualifiers: NetEase writes "N.O -Japanese Ver.-", and the
        // dashes fold away to nothing. So a language word immediately followed by a version word counts
        // wherever it sits in the title — that pair is never a song's own name.
        val words = fold(title).split(' ').filter { it.isNotEmpty() }
        for (i in 0 until words.size - 1) {
            if (words[i + 1] !in VERSION_WORDS_STANDALONE) continue
            LANGUAGE_SPELLINGS.firstOrNull { (_, spelling) -> spelling == words[i] }?.let { tags += it.first }
        }
        return tags
    }

    /**
     * How badly [target] fits [track]: lower is better, `null` means "not this recording".
     *
     * A `null` means one of four things: a version mismatch, a language mismatch, a timed candidate
     * from a cut long enough to be a different edit, or an artist that is simply somebody else. Those
     * are the four ways a lyric ends up *wrong* rather than merely imperfect. Everything else — a
     * missing duration, prose instead of timings, a channel credit — is expensive but still eligible,
     * because the alternative to a mediocre match is often no lyrics at all.
     */
    fun score(track: Track, target: LyricsMatchTarget): Long? {
        val ours = versionTags(track.title)
        val theirs = versionTags(target.title)
        if (ours != theirs) return null
        if (languageTags(track.title) != languageTags(target.title)) return null

        var score = 0L
        val drift = durationDrift(track.durationMs, target.durationMs)
        if (target.synced && target.durationMs != null && drift > MAX_TIMED_DRIFT_MS) return null
        score += drift
        if (!target.synced) score += UNSYNCED_PENALTY

        when (artistFits(track, target)) {
            ArtistFit.SAME, ArtistFit.UNKNOWN -> Unit
            ArtistFit.BILLED_LATER, ArtistFit.NAMED_IN_TITLE -> score += WRONG_ARTIST_PENALTY
            ArtistFit.DIFFERENT -> return null
        }
        if (target.title.isNotBlank() && !sameTitle(track.title, target.title)) {
            // Not the same title. Survivable only when theirs is *inside* ours, word for word — the
            // channel-titled upload again, "BTS (방탄소년단) 'IDOL' Official MV" against "IDOL". "IDOL"
            // against "Dreamers", or "For Youth" against "For You", is another song.
            if (!containsTitle(track.title, target.title)) return null
            score += WRONG_TITLE_PENALTY
        }
        return score
    }

    private enum class ArtistFit { SAME, UNKNOWN, BILLED_LATER, NAMED_IN_TITLE, DIFFERENT }

    /**
     * Whether [target] is by the artist we are playing.
     *
     * A wrong artist used to be a penalty, on the theory that a featured credit shouldn't be fatal.
     * In practice it was the door every wrong lyric came through: for a K-pop track NetEase's search
     * is mostly covers, tributes, karaoke tracks and unrelated songs that share the title, and the one
     * with the closest length won — "2.0" by someone else, an instrumental "Like Animals", a marching
     * band's "ON". The featured case is handled properly instead: both credit lines are split into
     * their billed artists, and one shared name is enough.
     *
     * The single survivable mismatch is a track credited to a YouTube **channel** — "HYBE LABELS" —
     * whose title spells out the artist: `BTS (방탄소년단) 'DNA' Official MV`. Then the candidate's
     * artist is found in our title, what is left of the title still contains theirs, and the match
     * stands at a penalty. The reverse — *their* title naming *our* artist, "BTS DNA" by a cover
     * singer — is exactly the tribute pattern and is refused.
     */
    private fun artistFits(track: Track, target: LyricsMatchTarget): ArtistFit {
        val ours = track.artists.map { it.name }.filter { it.isNotBlank() && !isPlaceholderArtist(it) }
        val theirs = target.artist.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
        if (ours.isEmpty() || theirs == null) return ArtistFit.UNKNOWN

        val ourCredits = ours.flatMap { ArtistNameMatching.credits(it) + it }
        val theirCredits = ArtistNameMatching.credits(theirs)
        // Who is billed *first* matters. A cover on NetEase is filed as "Ysabelle Cuevas, Bts": the
        // original artist is on the credit line, second, and the words are somebody else's. Sharing a
        // name with a later credit keeps the candidate eligible, but not certain.
        val lead = theirCredits.first()
        if (ourCredits.any { ArtistNameMatching.sameArtist(it, lead) || ArtistNameMatching.sameArtist(it, theirs) }) {
            return ArtistFit.SAME
        }
        if (ourCredits.any { a -> theirCredits.drop(1).any { b -> ArtistNameMatching.sameArtist(a, b) } }) {
            return ArtistFit.BILLED_LATER
        }

        val ourTitle = tighten(track.title)
        val theirTitle = tighten(target.title)
        val namedInOurTitle = (theirCredits + theirs).flatMap { ArtistNameMatching.keys(it) }
            .filter { it.length >= MIN_NAMED_ARTIST }
            .any { key -> key in ourTitle && theirTitle.isNotEmpty() && ourTitle.replace(key, "").contains(theirTitle) }
        return if (namedInOurTitle) ArtistFit.NAMED_IN_TITLE else ArtistFit.DIFFERENT
    }

    /** "Various Artists" and friends say nothing about who sings; treat them as no credit at all. */
    private fun isPlaceholderArtist(name: String): Boolean = tighten(name) in PLACEHOLDER_ARTISTS

    /** A candidate together with how far it was from a sure thing; see [pick]. */
    data class Scored<T>(val candidate: T, val score: Long)

    /** The best of [targets] for [track], or `null` when every one of them is a different recording. */
    fun <T> bestOf(track: Track, targets: List<T>, asTarget: (T) -> LyricsMatchTarget): T? =
        pick(track, targets, asTarget)?.candidate

    /**
     * [bestOf], keeping the score — which the provider hands on as `Lyrics.matchScore`, so that the
     * repository can tell a lyric matched at 0 from one matched at 120 000.
     */
    fun <T> pick(track: Track, targets: List<T>, asTarget: (T) -> LyricsMatchTarget): Scored<T>? {
        var best: Scored<T>? = null
        for (candidate in targets) {
            val score = score(track, asTarget(candidate)) ?: continue
            if (best == null || score < best.score) best = Scored(candidate, score)
        }
        return best
    }

    /**
     * True when a lyric file's first line is a "Title - Artist" header naming a version the track is
     * not: "Dynamite (EDM Remix) - BTS (防弹少年团)" at the top of a file listed as plain "Dynamite".
     * The listing lied; the file didn't. Only a line that reads as a header counts — a first *lyric*
     * line is never held to this, whatever brackets it may contain.
     */
    fun headerContradicts(track: Track, firstLine: String): Boolean {
        if ("-" !in firstLine) return false
        val header = tighten(firstLine)
        val title = tighten(stripQualifiers(track.title))
        val artists = track.artists.flatMap { ArtistNameMatching.keys(it.name) }
        val looksLikeHeader = title.isNotEmpty() && title in header && artists.any { it in header }
        return looksLikeHeader && versionTags(firstLine) != versionTags(track.title)
    }

    /** True when two titles name the same song once qualifiers and punctuation are set aside. */
    fun sameTitle(a: String, b: String): Boolean {
        val left = fold(stripQualifiers(a)).trim()
        val right = fold(stripQualifiers(b)).trim()
        if (left.isEmpty() || right.isEmpty()) return true
        if (left == right) return true
        // A subtitle one side kept and the other dropped is not a different song — "Trivia 轉 : Seesaw"
        // is "Seesaw", "Intro : Persona" is "Persona" — but only when the extra words are a *segment*
        // of the title, set off by punctuation. Plain substring containment made "Stay" match "Stay
        // Gold" and "For You" match "For Youth", and both are other songs by the same artist.
        return segments(a).any { it == right } || segments(b).any { it == left }
    }

    /**
     * True when every word of [theirs] appears, in order and adjacent, somewhere in [ours] — "dna" inside
     * "bts 방탄소년단 dna official mv". Whole words: "for you" is not inside "for youth".
     */
    private fun containsTitle(ours: String, theirs: String): Boolean {
        val big = fold(stripQualifiers(ours)).split(' ').filter { it.isNotEmpty() }
        val small = fold(stripQualifiers(theirs)).split(' ').filter { it.isNotEmpty() }
        if (small.isEmpty() || small.size > big.size) return false
        return (0..big.size - small.size).any { start -> big.subList(start, start + small.size) == small }
    }

    /** The colon-, dash- or slash-separated pieces of a title, folded, qualifiers removed. */
    private fun segments(title: String): List<String> =
        SEGMENT_SEPARATOR.split(QUALIFIER.replace(title, " "))
            .map { fold(it).trim() }
            .filter { it.isNotEmpty() }

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

    /**
     * Bracketed asides: `(Live)`, `[Remix]`; `- 2011 Remaster` is handled separately. Full-width
     * brackets too — NetEase writes `（1MA remix）`, and a remix that slips past this gate wins races.
     */
    private val QUALIFIER = Regex("""[(\[（【][^)\]）】]*[)\]）】]""")

    /** What sets a subtitle off from a title: " : ", " - ", " / ", "：", "／", and "-…-" wrappers. */
    private val SEGMENT_SEPARATOR = Regex("""\s*[:：/／|]\s*|\s+-+\s*|\s*-+\s+""")

    /** Names that credit nobody. Folded and space-free. */
    private val PLACEHOLDER_ARTISTS = setOf(
        "variousartists", "various", "va", "unknown", "unknownartist", "artistadesconocido",
        "artistasvarios", "variosartistas", "desconocido", "artistedivers", "diversartistes",
    )

    /** A candidate artist shorter than this can't be trusted to be "found" inside a title. */
    private const val MIN_NAMED_ARTIST = 3

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
        "live", "remix", "mix", "acoustic", "spedup", "slowed", "remaster",
        "instrumental", "karaoke", "nightcore",
    )

    /** The version words as whole tokens, for the unbracketed "Japanese Ver." scan in [languageTags]. */
    private val VERSION_WORDS_STANDALONE = setOf("ver", "version", "dub", "edition")

    /** Folded and space-free, the shape every needle below is compared against. */
    private fun tighten(text: String): String = fold(text).replace(" ", "")

    /**
     * What turns a language into a *version* of the song. "ver" covers "version", "versión" and "ver.";
     * it also sits inside "cover", which is the right answer anyway — a Spanish cover is a Spanish
     * recording with different words.
     */
    private val VERSION_MARKERS = listOf("ver", "dub", "版", "バージョン").map { tighten(it) }

    /**
     * Language names as they actually appear in titles, already folded so they can be matched against
     * folded text. Folding matters more than it looks: NFD splits Hangul into jamo, so a literal
     * "한국어" written here would never equal the folded title it is meant to match.
     *
     * "mandarin" maps to `chinese` on purpose — the same recording is labelled both ways, and two names
     * for one language would make a track disagree with its own lyric.
     */
    private val LANGUAGE_SPELLINGS: List<Pair<String, String>> = listOf(
        "japanese" to listOf("japanese", "japonés", "japonesa", "日本語", "ジャパニーズ"),
        "korean" to listOf("korean", "coreano", "한국어", "국문"),
        "chinese" to listOf("chinese", "chino", "mandarin", "mandarín", "中文", "国语"),
        "cantonese" to listOf("cantonese", "cantonés", "粤语"),
        "english" to listOf("english", "inglés", "英語"),
        "spanish" to listOf("spanish", "español", "espanhol"),
        "french" to listOf("french", "francés", "francais", "français"),
        "german" to listOf("german", "alemán", "deutsch"),
        "italian" to listOf("italian", "italiano"),
        "portuguese" to listOf("portuguese", "portugués", "português"),
        "russian" to listOf("russian", "ruso"),
        "thai" to listOf("thai"),
        "vietnamese" to listOf("vietnamese"),
        "indonesian" to listOf("indonesian"),
    ).flatMap { (tag, spellings) -> spellings.map { tag to tighten(it) } }
}
