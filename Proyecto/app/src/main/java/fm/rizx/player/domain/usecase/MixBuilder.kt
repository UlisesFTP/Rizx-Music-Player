package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.AppMix
import fm.rizx.player.domain.model.DailyPick
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.MixKind
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlin.math.pow

/**
 * Builds Rizx's **own** mixes — by weighting and counting what the user plays, the way a music app is
 * expected to.
 *
 * Pure, deterministic and Android-free: the same inputs always produce the same mixes, which is what
 * lets the Home draw its mosaic wall on the first frame and keep it there. Every input is data the Home
 * already holds — the play history, the likes and the loaded feed — so **a mix costs no network call**.
 *
 * ### The weighting
 * The history is an ordered list with no timestamps, so **rank is the clock**: a track [HALF_LIFE]
 * positions further back counts half as much as the one just played. A like adds [LIKE_WEIGHT] whenever
 * it happened, because it was explicit rather than incidental. Those per-track weights sum into a score
 * per artist, and every mix below is a different question asked of that one distribution.
 *
 * ### The meter
 * [AppMix.weight] is **how much evidence backs the mix**, normalized to `0f..1f`, and each kind names its
 * own evidence (an artist's accumulated weight, the share of the history revived, how many sources
 * blended). The mosaic draws it as a bar, so a mix resting on two half-forgotten plays reads as weak —
 * because it is.
 *
 * Deliberately **not** fed the personalized For-you rows: those are the slowest half of the Home, and a
 * mosaic wall that grew when they landed would shove the whole feed down — the very jump the For-you
 * skeletons exist to prevent. The recommendations get their own reserved slot instead, via [pick].
 */
class MixBuilder {

    /**
     * The mosaic wall's mixes, strongest kind first, capped at [limit].
     *
     * Kinds are emitted in [MixKind]'s declaration order, so trimming keeps the best. A kind that cannot
     * reach its minimum size is simply absent — a four-song "mix" is not a mix.
     */
    fun build(
        history: List<Track>,
        liked: List<Track> = emptyList(),
        feed: HomeFeed = HomeFeed(),
        limit: Int = MAX_MIXES,
    ): List<AppMix> {
        val taste = Taste(history, liked)
        val charts = feed.chartTracks()
        val chartsByArtist = charts.indexByArtist()

        return buildList {
            val daily = daily(taste, charts)
            daily?.let(::add)
            addAll(artistMixes(taste, chartsByArtist))
            onRepeat(taste)?.let(::add)
            rediscover(taste, history)?.let(::add)
            val used = daily?.tracks?.mapTo(HashSet()) { it.source }.orEmpty()
            discovery(taste, charts, used)?.let(::add)
            global(feed, charts)?.let(::add)
        }.take(limit)
    }

    /**
     * The day's single pick: the first recommendation for an artist the user has **never played**, plus
     * the seed that earned it — that reason line is the whole point of the card it feeds.
     *
     * Prefers a [ForYouSection.Mix] row, whose seed is a *song* ("similar to <song>"), over an artist
     * row. With nothing unheard on offer it still returns the top recommendation rather than nothing: a
     * card that vanishes is worse than one recommending something familiar.
     */
    fun pick(
        history: List<Track>,
        liked: List<Track> = emptyList(),
        forYou: List<ForYouSection> = emptyList(),
    ): DailyPick? {
        val taste = Taste(history, liked)
        val rows = forYou.filterIsInstance<ForYouSection.Mix>().map { it.seedTitle to it.items } +
            forYou.filterIsInstance<ForYouSection.BecauseYouLike>().map { it.artistName to it.items }
        for ((seed, items) in rows) {
            items.firstOrNull { !taste.knows(it) }?.let { return DailyPick(it, seed) }
        }
        return rows.firstOrNull { it.second.isNotEmpty() }?.let { DailyPick(it.second.first(), it.first) }
    }

    // ---- The kinds -----------------------------------------------------------------------------

    /**
     * [MixKind.DAILY] — the flagship. Opens on the user's own strongest tracks, then alternates chart
     * songs by artists they play with everything else the charts brought, so it reads as *theirs* from
     * the first card without being a replay of their history.
     */
    private fun daily(taste: Taste, charts: List<Track>): AppMix? {
        if (taste.size < MIN_TASTE) return null
        val familiar = charts.filter { taste.knows(it) }
        val rest = charts.filterNot { taste.knows(it) }
        val tracks = interleave(taste.ranked.take(DAILY_OWN), familiar, rest)
            .distinctBy { it.source }
            .take(MIX_TRACKS)
        if (tracks.size < MIN_TRACKS) return null
        return AppMix(
            kind = MixKind.DAILY,
            subject = taste.artists.firstOrNull()?.name.orEmpty(),
            tracks = tracks,
            weight = confidence(taste.evidence),
            artistCount = taste.artistCount,
        )
    }

    /**
     * [MixKind.ARTIST] for the most-played artists: their songs in the history plus their songs anywhere
     * on the feed. Needs [MIN_ARTIST_PLAYS] plays — one play is not a taste — and enough songs to be
     * worth starting, which is what stops a barely-known artist from getting a two-song "mix".
     */
    private fun artistMixes(taste: Taste, chartsByArtist: Map<String, List<Track>>): List<AppMix> =
        taste.artists.asSequence()
            .filter { it.plays >= MIN_ARTIST_PLAYS }
            .mapNotNull { stat ->
                val tracks = (taste.tracksOf(stat.key) + chartsByArtist[stat.key].orEmpty())
                    .distinctBy { it.source }
                    .take(MIX_TRACKS)
                tracks.takeIf { it.size >= MIN_ARTIST_TRACKS }?.let {
                    AppMix(MixKind.ARTIST, stat.name, it, confidence(stat.score), artistCount = 1)
                }
            }
            .take(ARTIST_MIXES)
            .toList()

    /** [MixKind.ON_REPEAT] — pure history, heaviest first. Nothing external, nothing recommended. */
    private fun onRepeat(taste: Taste): AppMix? {
        val tracks = taste.ranked.take(MIX_TRACKS)
        if (tracks.size < MIN_ON_REPEAT) return null
        return AppMix(
            kind = MixKind.ON_REPEAT,
            tracks = tracks,
            weight = confidence(taste.evidence),
            artistCount = taste.artistCount,
        )
    }

    /**
     * [MixKind.REDISCOVER] — the older half of the history, kept to artists played more than once.
     *
     * Rank is the only clock available, so "a while back" means "deeper in the list". The play filter is
     * what stops this becoming a bin of things tried once and abandoned. Its meter is the share of the
     * history it revives.
     */
    private fun rediscover(taste: Taste, history: List<Track>): AppMix? {
        if (history.size < MIN_REDISCOVER * 2) return null
        val tracks = history.drop(history.size / 2)
            .filter { taste.playsOf(it) >= MIN_ARTIST_PLAYS }
            .distinctBy { it.source }
            .take(MIX_TRACKS)
        if (tracks.size < MIN_REDISCOVER) return null
        return AppMix(
            kind = MixKind.REDISCOVER,
            tracks = tracks,
            weight = (tracks.size.toFloat() / history.size).coerceIn(0f, 1f),
            artistCount = tracks.artistCount(),
        )
    }

    /**
     * [MixKind.DISCOVERY] — feed songs by artists with **no** play at all, minus whatever [daily] already
     * used so the two are not the same wall twice.
     *
     * Absent with no history: "new to you" would then be the entire chart, which is what [global] already
     * is — under a name claiming personalization it does not have.
     */
    private fun discovery(taste: Taste, charts: List<Track>, used: Set<ProviderRef>): AppMix? {
        if (taste.isEmpty) return null
        val tracks = charts
            .filterNot { it.source in used || taste.knows(it) }
            .distinctBy { it.source }
            .take(MIX_TRACKS)
        if (tracks.size < MIN_DISCOVERY) return null
        return AppMix(
            kind = MixKind.DISCOVERY,
            tracks = tracks,
            weight = (tracks.size.toFloat() / MIX_TRACKS).coerceIn(0f, 1f),
            artistCount = tracks.artistCount(),
        )
    }

    /**
     * [MixKind.GLOBAL] — the charts themselves, one track per source at a time so the mix is genuinely
     * cross-platform instead of one provider's list with the others appended.
     *
     * The cold-start mix: it asks nothing of the history, so a fresh install still gets a mosaic wall.
     * Its meter is how many sources actually contributed.
     */
    private fun global(feed: HomeFeed, charts: List<Track>): AppMix? {
        val tracks = charts.take(MIX_TRACKS)
        if (tracks.size < MIN_TRACKS) return null
        val sources = feed.topTracks.count { it.items.isNotEmpty() }
        return AppMix(
            kind = MixKind.GLOBAL,
            tracks = tracks,
            weight = (sources / GLOBAL_FULL_SOURCES).coerceIn(0f, 1f),
            artistCount = tracks.artistCount(),
        )
    }
}

// ---- Statistics -------------------------------------------------------------------------------

/** One artist's standing in the taste distribution. */
private class ArtistStat(val key: String, val name: String, val score: Float, val plays: Int)

/**
 * The distribution every mix is a question about: a weight per track and a score per artist.
 *
 * Artists are folded through [ArtistNameMatching.key], so a play credited to "DualipaVEVO" counts
 * towards the same artist as one credited to "Dua Lipa" — without that, the history of anyone who plays
 * from search would splinter into channel-shaped strangers.
 */
private class Taste(history: List<Track>, liked: List<Track>) {
    private val tracks = LinkedHashMap<ProviderRef, Track>()
    private val weights = HashMap<ProviderRef, Float>()
    private val scores = HashMap<String, Float>()
    private val plays = HashMap<String, Int>()
    private val names = HashMap<String, String>()
    private val byArtist = HashMap<String, MutableList<Track>>()

    init {
        history.forEachIndexed { rank, track -> add(track, recency(rank)) }
        liked.forEach { add(it, LIKE_WEIGHT) }
    }

    val isEmpty: Boolean get() = tracks.isEmpty()
    val size: Int get() = tracks.size
    val artistCount: Int get() = scores.size

    /** Every known track, heaviest first. Ties keep history order, so the result is stable. */
    val ranked: List<Track> get() = tracks.values.sortedByDescending { weights[it.source] ?: 0f }

    /** Total weight of the top few tracks — the evidence a taste-based mix rests on. */
    val evidence: Float get() = weights.values.sortedDescending().take(EVIDENCE_TRACKS).sum()

    val artists: List<ArtistStat>
        get() = scores.entries
            .map { (key, score) -> ArtistStat(key, names[key] ?: key, score, plays[key] ?: 0) }
            // Name as the tie-break, so equal scores always order the same way.
            .sortedWith(compareByDescending<ArtistStat> { it.score }.thenBy { it.name })

    fun knows(track: Track): Boolean = track.artistKeys().any { it in scores }

    /** How many plays the best-known artist credited on this track has. */
    fun playsOf(track: Track): Int = track.artistKeys().maxOfOrNull { plays[it] ?: 0 } ?: 0

    fun tracksOf(artistKey: String): List<Track> = byArtist[artistKey].orEmpty()

    private fun add(track: Track, weight: Float) {
        tracks.putIfAbsent(track.source, track)
        weights[track.source] = (weights[track.source] ?: 0f) + weight
        val counted = HashSet<String>()
        track.artists.forEach { credit ->
            if (credit.name.isBlank()) return@forEach
            val key = ArtistNameMatching.key(credit.name)
            // A credit that folds to nothing, or a name billed twice on one track, must not count twice.
            if (key.isEmpty() || !counted.add(key)) return@forEach
            scores[key] = (scores[key] ?: 0f) + weight
            plays[key] = (plays[key] ?: 0) + 1
            // The shortest spelling wins as the display name: channel decoration only ever *adds*
            // characters, so "Dua Lipa" beats "DualipaVEVO" for the title of a mix about them.
            val known = names[key]
            if (known == null || credit.name.length < known.length) names[key] = credit.name
            val list = byArtist.getOrPut(key) { mutableListOf() }
            if (list.none { it.source == track.source }) list += track
        }
    }
}

/** How many mixes the wall holds before it stops being a wall and becomes a list. */
private const val MAX_MIXES = 6
private const val MIX_TRACKS = 25

/** Below this a mix isn't worth starting. */
private const val MIN_TRACKS = 8
private const val MIN_TASTE = 3
private const val MIN_ON_REPEAT = 8
private const val MIN_DISCOVERY = 8
private const val MIN_REDISCOVER = 6

/** How many of the user's own tracks open the daily mix before the feed takes over. */
private const val DAILY_OWN = 4

private const val ARTIST_MIXES = 2
private const val MIN_ARTIST_PLAYS = 2
private const val MIN_ARTIST_TRACKS = 5

/** Positions back at which a play counts half as much as the most recent one. */
private const val HALF_LIFE = 8f

/** What an explicit like is worth — a little under a just-played track. */
private const val LIKE_WEIGHT = 0.8f

/** Summed weight at which a taste-based mix's meter reads full, and over how many tracks. */
private const val EVIDENCE_FULL = 5f
private const val EVIDENCE_TRACKS = 6

/** Sources blended at which the global mix's meter reads full. */
private const val GLOBAL_FULL_SOURCES = 3f

private fun recency(rank: Int): Float = 0.5f.pow(rank / HALF_LIFE)

private fun confidence(evidence: Float): Float = (evidence / EVIDENCE_FULL).coerceIn(0f, 1f)

/** The folded artist keys credited on a track, in credit order and without repeats. */
private fun Track.artistKeys(): List<String> = artists.asSequence()
    .map { it.name }
    .filter { it.isNotBlank() }
    .map { ArtistNameMatching.key(it) }
    .filter { it.isNotEmpty() }
    .distinct()
    .toList()

private fun List<Track>.artistCount(): Int = flatMap { it.artistKeys() }.distinct().size

private fun List<Track>.indexByArtist(): Map<String, List<Track>> =
    flatMap { track -> track.artistKeys().map { it to track } }
        .groupBy({ it.first }, { it.second })

/** Chart tracks, one per contributing source at a time. */
private fun HomeFeed.chartTracks(): List<Track> =
    interleave(*topTracks.map { it.items }.toTypedArray()).distinctBy { it.source }

/**
 * Round-robin across the pools: one from each, then the next from each, skipping the exhausted. This is
 * what makes a blend read as a blend instead of as concatenated blocks.
 */
private fun interleave(vararg pools: List<Track>): List<Track> {
    val out = ArrayList<Track>(pools.sumOf { it.size })
    var i = 0
    while (true) {
        var added = false
        pools.forEach { pool -> pool.getOrNull(i)?.let { out += it; added = true } }
        if (!added) return out
        i++
    }
}
