package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.AppMix
import fm.rizx.player.domain.model.DailyPick
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.MixKind
import fm.rizx.player.domain.model.PlayStat
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.domain.model.Track
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Builds Rizx's **own** mixes from a [TasteProfile] — by weighting and counting what the user plays, the
 * way a music app is expected to.
 *
 * Pure, deterministic and Android-free: the same inputs always produce the same mixes, which is what
 * lets the Home draw its mosaic wall on the first frame and keep it there. Every input is data the Home
 * already holds — the listening log, the likes and the loaded feed — so **a mix costs no network call**.
 *
 * ### The daily mixes
 *
 * Not one mix but up to [MAX_DAILY_MIXES], one per facet of the listener's taste
 * ([TasteProfile.clusters]), each built the way every service builds one: mostly songs you know, with a
 * measured share of songs you do not ([FRESH_SHARE]). The known half is what makes it playable; the
 * fresh half is the only reason to open it twice. Both pools rotate with the day, so today's mix is not
 * yesterday's — while staying identical for the whole day, because a wall that reshuffles while you read
 * it is not variety, it is noise.
 *
 * ### The meter
 * [AppMix.weight] is **how much evidence backs the mix**, normalized to `0f..1f`, and each kind names its
 * own evidence (an artist's accumulated weight, the share of the history revived, how many sources
 * blended). The mosaic draws it as a bar, so a mix resting on two half-forgotten plays reads as weak —
 * because it is.
 *
 * ### The one rule about the personalized rows
 *
 * The "For you" rows are the slowest half of the Home. They may fill the **fresh** slots of a mix, but
 * they may never decide **whether a mix exists**: a mosaic wall that gained a tile when they landed
 * would shove the whole feed down — the very jump the For-you skeletons exist to prevent. Hence every
 * `MIN_*` gate below is counted against the known side only, and the day's single recommendation gets
 * its own reserved slot via [pick].
 */
class MixBuilder {

    /**
     * The mosaic wall's mixes, strongest kind first, capped at [limit].
     *
     * Kinds are emitted in [MixKind]'s declaration order, so trimming keeps the best. A kind that cannot
     * reach its minimum size is simply absent — a four-song "mix" is not a mix.
     */
    fun build(
        profile: TasteProfile,
        feed: HomeFeed = HomeFeed(),
        forYou: List<ForYouSection> = emptyList(),
        dayEpoch: Long = 0L,
        limit: Int = MAX_MIXES,
    ): List<AppMix> {
        val charts = feed.chartTracks()
        val chartsByArtist = charts.indexByArtist()
        val bridges = bridgePool(profile, charts, forYou)

        return buildList {
            val daily = dailyMixes(profile, chartsByArtist, bridges, dayEpoch)
            addAll(daily)
            addAll(artistMixes(profile, chartsByArtist, daily))
            onRepeat(profile)?.let(::add)
            rediscover(profile)?.let(::add)
            val used = daily.flatMapTo(HashSet()) { mix -> mix.tracks.map { it.source } }
            discovery(profile, charts, used)?.let(::add)
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
    fun pick(profile: TasteProfile, forYou: List<ForYouSection> = emptyList()): DailyPick? {
        val rows = forYou.filterIsInstance<ForYouSection.Mix>().map { it.seedTitle to it.items } +
            forYou.filterIsInstance<ForYouSection.BecauseYouLike>().map { it.artistName to it.items }
        for ((seed, items) in rows) {
            items.firstOrNull { !profile.knows(it) }?.let { return DailyPick(it, seed) }
        }
        return rows.firstOrNull { it.second.isNotEmpty() }?.let { DailyPick(it.second.first(), it.first) }
    }

    // ---- The kinds -----------------------------------------------------------------------------

    /**
     * [MixKind.DAILY] — one per facet of the taste, strongest facet first.
     *
     * Each opens on the listener's own strongest songs in that facet ([ANCHOR_HEAD] of them, never
     * rotated) so it reads as theirs from the first card — and so the tile's collage, which is drawn
     * from the first covers, does not change when the slower half of the Home lands.
     */
    private fun dailyMixes(
        profile: TasteProfile,
        chartsByArtist: Map<String, List<Track>>,
        bridges: List<Track>,
        dayEpoch: Long,
    ): List<AppMix> {
        if (profile.size < MIN_TASTE) return emptyList()
        val spent = HashSet<ProviderRef>()
        // Numbered as they survive, not as they were found: a facet too small to become a mix must not
        // leave a hole where "Your daily mix" — always number zero — should be.
        var next = 0
        val byFacet = profile.clusters(MAX_DAILY_MIXES).mapNotNull { cluster ->
            // Known = played or liked in this facet, then anything on the feed by the same artists.
            val known = (profile.tracksOf(cluster) + cluster.artistKeys.flatMap { chartsByArtist[it].orEmpty() })
                .distinctBy { it.source }
                .filterNot { it.source in spent }
            // The gate is on the known side alone — see the class note about the For-you rows.
            if (known.size < MIN_TRACKS) return@mapNotNull null
            daily(next, cluster.label, cluster.lead, cluster.genre, cluster.score, known, bridges, spent, dayEpoch)
                ?.also { next++ }
        }
        if (byFacet.isNotEmpty()) return byFacet

        // No single facet is big enough on its own — a broad taste split three ways, which is ordinary.
        // The listener still gets their daily mix; it is simply about all of it rather than one corner.
        val everything = profile.ranked.filterNot { it.source in spent }
        if (everything.size < MIN_TRACKS) return emptyList()
        val top = profile.artists
        return listOfNotNull(
            daily(
                index = 0,
                label = top.take(LABEL_ARTISTS).joinToString { it.name },
                lead = top.firstOrNull()?.name.orEmpty(),
                genre = SoundGenre.UNKNOWN,
                score = profile.evidence,
                known = everything,
                bridges = bridges,
                spent = spent,
                dayEpoch = dayEpoch,
            ),
        )
    }

    /** One daily mix over an already-chosen pool of known songs. */
    private fun daily(
        index: Int,
        label: String,
        lead: String,
        genre: SoundGenre,
        score: Float,
        known: List<Track>,
        bridges: List<Track>,
        spent: MutableSet<ProviderRef>,
        dayEpoch: Long,
    ): AppMix? {
        val fresh = bridges.filterNot { it.source in spent }.preferring(genre).rotatedBy(dayEpoch)
        // The head is never rotated: it is what makes the mix read as theirs from the first card, and
        // what the tile's collage is drawn from.
        val anchors = known.take(ANCHOR_HEAD) + known.drop(ANCHOR_HEAD).rotatedBy(dayEpoch)
        val tracks = sandwich(anchors, fresh)
        if (tracks.size < MIN_TRACKS) return null
        spent += tracks.map { it.source }
        val anchorSources = anchors.mapTo(HashSet()) { it.source }
        val knownCount = tracks.count { it.source in anchorSources }
        return AppMix(
            kind = MixKind.DAILY,
            index = index,
            subject = label,
            lead = lead,
            tracks = tracks,
            weight = confidence(score),
            artistCount = tracks.artistCount(),
            knownCount = knownCount,
            freshCount = tracks.size - knownCount,
        )
    }

    /**
     * [MixKind.ARTIST] for a most-played artist: their songs in the history plus their songs anywhere on
     * the feed. Needs [MIN_ARTIST_PLAYS] plays — one play is not a taste — and enough songs to be worth
     * starting, which is what stops a barely-known artist from getting a two-song "mix".
     *
     * Artists that already lead a daily mix are skipped: the wall gains nothing from two tiles about the
     * same person, and the next artist down is something the listener has not been offered yet.
     */
    private fun artistMixes(
        profile: TasteProfile,
        chartsByArtist: Map<String, List<Track>>,
        daily: List<AppMix>,
    ): List<AppMix> {
        val leads = daily.mapTo(HashSet()) { it.lead.lowercase() }
        return profile.artists.asSequence()
            .filter { it.plays >= MIN_ARTIST_PLAYS && it.name.lowercase() !in leads }
            .mapNotNull { stat ->
                val tracks = (profile.tracksOf(stat.key) + chartsByArtist[stat.key].orEmpty())
                    .distinctBy { it.source }
                    .take(MIX_TRACKS)
                tracks.takeIf { it.size >= MIN_ARTIST_TRACKS }?.let {
                    AppMix(MixKind.ARTIST, subject = stat.name, lead = stat.name, tracks = it, weight = confidence(stat.score), artistCount = 1)
                }
            }
            .take(ARTIST_MIXES)
            .toList()
    }

    /** [MixKind.ON_REPEAT] — the songs played most often. Pure history, nothing recommended. */
    private fun onRepeat(profile: TasteProfile): AppMix? {
        val tracks = profile.mostPlayed.map { it.track }.take(MIX_TRACKS)
        if (tracks.size < MIN_ON_REPEAT) return null
        return AppMix(
            kind = MixKind.ON_REPEAT,
            tracks = tracks,
            weight = confidence(profile.evidence),
            artistCount = tracks.artistCount(),
        )
    }

    /**
     * [MixKind.REDISCOVER] — songs last heard over [REDISCOVER_AFTER_DAYS] days ago, kept to the ones
     * played more than once. The play filter is what stops this becoming a bin of things tried once and
     * abandoned. Its meter is the share of the history it revives.
     *
     * With no usable timestamps at all — rows written before the log existed — "a while back" can only
     * mean "deeper in the list", which is the rule this replaces and the one it falls back to.
     */
    private fun rediscover(profile: TasteProfile): AppMix? {
        val stats = profile.recentFirst
        if (stats.size < MIN_REDISCOVER * 2) return null
        val timed = stats.filter { profile.ageDays(it) != null }
        val candidates = if (timed.isNotEmpty()) {
            timed.filter { (profile.ageDays(it) ?: 0f) >= REDISCOVER_AFTER_DAYS && profile.wasWorthIt(it) }
        } else {
            stats.drop(stats.size / 2).filter { profile.wasWorthIt(it) }
        }
        val tracks = candidates.map { it.track }.distinctBy { it.source }.take(MIX_TRACKS)
        if (tracks.size < MIN_REDISCOVER) return null
        return AppMix(
            kind = MixKind.REDISCOVER,
            tracks = tracks,
            weight = (tracks.size.toFloat() / stats.size).coerceIn(0f, 1f),
            artistCount = tracks.artistCount(),
        )
    }

    /**
     * [MixKind.DISCOVERY] — feed songs by artists with **no** play at all, minus whatever the daily mixes
     * already used so the two are not the same wall twice.
     *
     * Absent with no history: "new to you" would then be the entire chart, which is what [global] already
     * is — under a name claiming personalization it does not have.
     */
    private fun discovery(profile: TasteProfile, charts: List<Track>, used: Set<ProviderRef>): AppMix? {
        if (profile.isEmpty) return null
        val tracks = charts
            .filterNot { it.source in used || profile.knows(it) }
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

    // ---- The sandwich --------------------------------------------------------------------------

    /**
     * Everything a daily mix can offer as "new": real recommendations first, then the charts, minus
     * anything by an artist this listener already plays.
     *
     * The recommendations come first because they were seeded by the listener's own songs — a chart hit
     * is only new, whereas "similar to something you played" is new *and* aimed.
     */
    private fun bridgePool(profile: TasteProfile, charts: List<Track>, forYou: List<ForYouSection>): List<Track> {
        val recommended = forYou.flatMap { section ->
            when (section) {
                is ForYouSection.Mix -> section.items
                is ForYouSection.BecauseYouLike -> section.items
                else -> emptyList()
            }
        }
        return (recommended + charts).distinctBy { it.source }.filterNot { profile.knows(it) }
    }

    /** Tracks of the facet's own genre first, the rest after — a stable partition, never a filter. */
    private fun List<Track>.preferring(genre: SoundGenre): List<Track> =
        if (genre == SoundGenre.UNKNOWN) this else partition { it.soundGenre() == genre }.let { it.first + it.second }

    /**
     * Lays [anchors] and [bridges] into one mix: familiar by default, with the fresh songs spread evenly
     * through the body and never in the opening [ANCHOR_HEAD].
     *
     * **The anchors decide the length.** A facet with only eight songs of its own makes an eleven-track
     * mix, not a twenty-five-track one padded with seventeen strangers — the ratio is the promise, and a
     * mix that quietly becomes a discovery list is a broken promise with a familiar name on it. It also
     * means a mix's length, and therefore its existence, never depends on how many recommendations have
     * arrived: with no bridges at all the anchors alone still make it.
     */
    private fun sandwich(anchors: List<Track>, bridges: List<Track>, total: Int = MIX_TRACKS): List<Track> {
        if (anchors.isEmpty()) return emptyList()
        // What the known side can carry at this ratio: 17 anchors support 25 slots, 8 support 11.
        val capacity = ceil(anchors.size / (1f - FRESH_SHARE)).toInt()
        val size = minOf(total, anchors.size + bridges.size, capacity)
        val fresh = minOf((size * FRESH_SHARE).roundToInt(), bridges.size)
        val slots = freshSlots(size, fresh)
        val known = ArrayDeque(anchors)
        val new = ArrayDeque(bridges)
        val out = ArrayList<Track>(size)
        for (i in 0 until size) {
            val pick = if (i in slots) {
                new.removeFirstOrNull() ?: known.removeFirstOrNull()
            } else {
                known.removeFirstOrNull() ?: new.removeFirstOrNull()
            }
            out += pick ?: break
        }
        return out
    }

    /**
     * Which positions of a run of [total] belong to the fresh side: the centres of [fresh] equal
     * buckets over the body, so they are spread evenly with no drift and no clumping at the end.
     */
    private fun freshSlots(total: Int, fresh: Int): Set<Int> {
        if (fresh <= 0) return emptySet()
        val body = (total - ANCHOR_HEAD).coerceAtLeast(fresh)
        val offset = (total - body).coerceAtLeast(0)
        return (0 until fresh).mapTo(HashSet()) { k -> offset + ((2 * k + 1) * body) / (2 * fresh) }
    }

    /** How full a meter is: summed evidence against what a well-established taste looks like. */
    private fun confidence(evidence: Float): Float = (evidence / EVIDENCE_FULL).coerceIn(0f, 1f)

    /**
     * Whether an old song is worth bringing back: played more than once, **or** by an artist this
     * listener returns to. Either is evidence; something tried once and dropped is not a memory.
     *
     * Both, rather than the stricter first one alone, because the play counter only started when the
     * listening log did: on the day this ships every song in the history reads as played once, and a
     * rule that only looked there would empty the shelf just as the feature arrived.
     */
    private fun TasteProfile.wasWorthIt(stat: PlayStat): Boolean =
        stat.plays >= MIN_ARTIST_PLAYS || playsOf(stat.track) >= MIN_ARTIST_PLAYS

    private companion object {
        /** How many mixes the wall holds before it stops being a wall and becomes a list. */
        const val MAX_MIXES = 7
        const val MIX_TRACKS = 25

        /** Facets of a taste worth splitting out. Beyond three they stop being facets and become slices. */
        const val MAX_DAILY_MIXES = 3

        /** How many artists name the fallback mix, which is about the whole taste rather than one facet. */
        const val LABEL_ARTISTS = 3

        /** Share of a daily mix the listener has never heard — the reason to open it more than once. */
        const val FRESH_SHARE = 0.3f

        /** How many of the listener's own tracks open a daily mix, never rotated. */
        const val ANCHOR_HEAD = 4

        /** Below this a mix isn't worth starting. */
        const val MIN_TRACKS = 8
        const val MIN_TASTE = 3
        const val MIN_ON_REPEAT = 8
        const val MIN_DISCOVERY = 8
        const val MIN_REDISCOVER = 6

        /** Three weeks away is long enough that hearing it again is a small event. */
        const val REDISCOVER_AFTER_DAYS = 21f

        const val ARTIST_MIXES = 1
        const val MIN_ARTIST_PLAYS = 2
        const val MIN_ARTIST_TRACKS = 5

        /** Summed weight at which a taste-based mix's meter reads full. */
        const val EVIDENCE_FULL = 5f

        /** Sources blended at which the global mix's meter reads full. */
        const val GLOBAL_FULL_SOURCES = 3f
    }
}

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
