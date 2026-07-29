package fm.rizx.player.domain.match

import fm.rizx.player.domain.usecase.RecsBlender

/**
 * Decides whether two titles name the same recording — and, separately, whether they would wear the
 * same cover.
 *
 * The distinction is the whole point. "GOOSE COAT" and "GOOSE COAT (Remix)" are the same *song* and
 * two different *recordings* released under different art; treating them as interchangeable is
 * exactly how a track ends up wearing another release's cover. So version words are **compared**,
 * never silently dropped.
 *
 * But "same recording" is stricter than "same artwork". A *remaster* or a *radio edit* is a different
 * recording that ships under **the same cover as its original release** — so rejecting those would
 * make covers vanish on some of the most common catalogue rows there are, trading a wrong-cover bug
 * for a missing-cover bug. [sharesArtwork] is the looser question artwork should ask; [sameTitle] is
 * the strict one.
 *
 * Version words are read **only from qualifier positions** — inside `(…)`/`[…]`, or after a dash.
 * A whole-title scan calls "Live and Let Die" a live take and "Radio Ga Ga" a radio edit.
 *
 * What is always stripped is platform decoration ("(Official Video)", "[FREE DL]", a trailing
 * "| Label"): it says nothing about the recording, only about where the row was scraped. A YouTube
 * row titled "Xavi - La Diabla (Official Video)" is the same recording as a catalogue's "La Diabla",
 * and those rows are precisely the ones that most need a cover borrowed.
 *
 * Folding is [RecsBlender.nameKey] — the same normalisation the feed's dedup and
 * [fm.rizx.player.domain.usecase.ArtistNameMatching] use, so all three agree on what "the same" means.
 * Pure Kotlin, no Android.
 */
object RecordingIdentity {

    private val blender = RecsBlender()

    /**
     * The version words qualifying [title] (`remix`, `live`, `acoustic`, …).
     *
     * Read from qualifier segments only, and matched both as a whole word and glued
     * ("Sped Up" and "SpedUp" are one tag). Order-independent and duplicate-free.
     */
    fun versionTags(title: String): Set<String> {
        val tags = mutableSetOf<String>()
        for (segment in qualifiers(title)) {
            val folded = fold(segment)
            val padded = " $folded "
            val glued = folded.replace(" ", "")
            for (word in VERSION_WORDS) {
                if (padded.contains(" $word ") || glued.contains(word)) tags += word
            }
        }
        return tags
    }

    /**
     * The comparable core of a title: folded, with platform decoration and every version/noise
     * qualifier removed. Version words leave the stem because [sameTitle] compares them separately.
     */
    fun titleStem(title: String): String {
        var s = title
        for (regex in DECORATION) s = regex.replace(s, " ")
        s = QUALIFIER.replace(s) { m -> if (isVersionOrNoise(m.groupValues[1])) " " else m.value }
        s = DASH_TAIL.replace(s) { m -> if (isVersionOrNoise(m.groupValues[1])) " " else m.value }
        val words = fold(s).split(' ').filter { it.isNotEmpty() && it !in NOISE_WORDS }
        return words.joinToString(" ")
    }

    /**
     * True when [a] and [b] are the same recording: same stem **and** the same version tags. A studio
     * track never matches its own live or remixed release.
     */
    fun sameTitle(a: String, b: String): Boolean = matches(a, b, VERSION_WORDS)

    /**
     * True when [a] and [b] would carry the same cover art — [sameTitle] with the version words that
     * do **not** change the artwork ignored on both sides (a remaster and a radio edit ship under the
     * original release's cover).
     *
     * This is the right question for borrowing a cover, and only for that: it deliberately says yes
     * to two things that are not the same recording.
     */
    fun sharesArtwork(a: String, b: String): Boolean = matches(a, b, ARTWORK_CHANGING_WORDS)

    private fun matches(a: String, b: String, significant: Set<String>): Boolean {
        if (versionTags(a).intersect(significant) != versionTags(b).intersect(significant)) return false
        val left = titleStem(a)
        val right = titleStem(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true
        // A scraped row often prefixes the artist ("Xavi - La Diabla"), so the shorter stem may sit
        // inside the longer one. Both must still be substantial, or "Intro" matches "Introduction".
        val (shorter, longer) = if (left.length <= right.length) left to right else right to left
        return shorter.length >= MIN_CONTAINED_STEM && longer.contains(shorter)
    }

    /** The bracketed and after-dash segments of a title — where a version word actually means one. */
    private fun qualifiers(title: String): List<String> =
        QUALIFIER.findAll(title).map { it.groupValues[1] }.toList() +
            DASH_TAIL.findAll(title).map { it.groupValues[1] }.toList()

    private fun isVersionOrNoise(segment: String): Boolean {
        val folded = fold(segment)
        val words = folded.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val glued = folded.replace(" ", "")
        return words.all { it in VERSION_WORDS || it in NOISE_WORDS || it.toIntOrNull() != null } ||
            VERSION_WORDS.any { glued.contains(it) }
    }

    private fun fold(raw: String): String = blender.nameKey(raw)

    /**
     * Words naming a different recording of the same song. Spanish and Portuguese forms are here
     * because this app's catalogue is largely Latin — "En Vivo" is as common as "Live".
     */
    val VERSION_WORDS: Set<String> = setOf(
        "remix", "remixes", "rmx",
        "live", "vivo", "acustico", "acoustic", "unplugged", "session", "sesion",
        "instrumental", "karaoke", "cover", "tribute",
        "demo", "reprise", "rework", "bootleg", "mashup", "flip",
        "spedup", "sped", "slowed", "nightcore", "reverb",
        "extended", "club", "dub", "vip",
        "remaster", "remastered", "remasterizado", "radio", "edit", "mono", "stereo",
    )

    /**
     * The subset that actually changes the cover. A remaster, a radio edit and a mono/stereo reissue
     * keep their original release's art, so they may donate a cover; a remix or a live take may not.
     *
     * "edit" sits on the safe side of this line because the dangerous case — someone else's edit,
     * i.e. a remix by another name — is caught by the artist comparison instead.
     */
    private val ARTWORK_NEUTRAL_WORDS: Set<String> =
        setOf("remaster", "remastered", "remasterizado", "radio", "edit", "mono", "stereo")

    val ARTWORK_CHANGING_WORDS: Set<String> = VERSION_WORDS - ARTWORK_NEUTRAL_WORDS

    /** Words that carry no identity meaning but survive folding. */
    private val NOISE_WORDS: Set<String> = setOf(
        "official", "video", "audio", "music", "hd", "hq", "4k", "lyrics", "lyric",
        "visualizer", "visualiser", "mv", "version", "ver",
    )

    /**
     * Platform decoration, removed before anything else. Matched as whole bracketed constructs so
     * "(Official Music Video)" cannot leave a stray "music" behind in the stem.
     */
    private val DECORATION: List<Regex> = listOf(
        Regex("""[(\[][^)\]]*\b(?:official|lyric|lyrics|visuali[sz]er|audio|video|mv|hd|hq|4k)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
        Regex("""[(\[]\s*(?:free\s*dl|free\s*download|out\s*now|premiere)\s*[)\]]""", RegexOption.IGNORE_CASE),
        // A trailing "| Label" the way SoundCloud and YouTube rows carry it.
        Regex("""\s*\|\s*[^|]{0,40}$"""),
    )

    /** A `(…)` or `[…]` segment. */
    private val QUALIFIER = Regex("""[(\[]([^)\]]*)[)\]]""")

    /** Everything after a spaced dash — "Song - Live", "Song — Acoustic". */
    private val DASH_TAIL = Regex("""\s[-–—]\s([^-–—]+)$""")

    /** Below this, containment is coincidence rather than a match ("Intro" inside "Introduction"). */
    private const val MIN_CONTAINED_STEM = 6
}
