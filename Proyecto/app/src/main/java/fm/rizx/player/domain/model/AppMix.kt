package fm.rizx.player.domain.model

/**
 * Why the app built a mix. The UI turns this — plus [AppMix.subject] and the counts — into a localized
 * title and caption, so the domain stays resource-free exactly as [ForYouSection] does.
 *
 * Declared in priority order: [MixBuilder][fm.rizx.player.domain.usecase.MixBuilder] emits mixes in
 * this order and keeps the first few, so the strongest kind leads the Home.
 */
enum class MixKind {
    /** A blend of what you play, what you like and what the app has just recommended. */
    DAILY,

    /** One artist's orbit: their songs in your history plus their songs elsewhere on the feed. */
    ARTIST,

    /** The songs you keep coming back to — your own top-weighted tracks. */
    ON_REPEAT,

    /** Songs from deeper in your history, by artists you played more than once. */
    REDISCOVER,

    /** Recommendations by artists that are not in your history at all. */
    DISCOVERY,

    /** The charts, interleaved across every source that contributed. */
    GLOBAL,
}

/**
 * A mix **Rizx assembled itself**, by weighting and counting what the user plays — not a playlist any
 * service handed us. Purely derived: everything it is built from (history, likes, the loaded feed, the
 * already-fetched recommendations) is data the Home holds anyway, so a mix costs **no network call**
 * and appears as fast as the feed does.
 *
 * Deliberately *not* `@Serializable`: it is recomputed from its cached inputs on every load, so caching
 * it would only risk showing a mix whose statistics no longer match the history behind it.
 */
data class AppMix(
    val kind: MixKind,
    /** What the mix is about (an artist's name), or empty for the kinds that need no subject. */
    val subject: String = "",
    val tracks: List<Track> = emptyList(),
    /**
     * How much evidence backs this mix, `0f..1f` — the *meter* the mosaic draws. Not decoration: it is
     * the summed, recency-decayed weight of the seeds behind the mix (or, for [MixKind.GLOBAL], how
     * many sources it blends), normalized. A mix built off two half-forgotten plays reads as weak
     * because it *is* weak.
     */
    val weight: Float = 0f,
    /** How many distinct artists the mix draws on — the number its caption quotes. */
    val artistCount: Int = 0,
) {
    /** Stable identity for LazyColumn keys: kind plus subject, which is what makes a mix unique. */
    val id: String get() = "${kind.name}|${subject.lowercase()}"

    val leadTrack: Track? get() = tracks.firstOrNull()
}

/**
 * The single strongest recommendation of the day, with the song that earned it.
 *
 * Kept apart from [AppMix] because it is one *track*, not a list, and because its whole point is the
 * reason line: [becauseOf] names the seed the recommendation came from ("similar to <song>"), which
 * only the recommendation row that produced it knows.
 */
data class DailyPick(val track: Track, val becauseOf: String)
