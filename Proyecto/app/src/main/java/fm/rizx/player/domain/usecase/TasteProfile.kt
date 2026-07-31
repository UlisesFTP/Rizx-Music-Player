package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.Daypart
import fm.rizx.player.domain.model.PlayStat
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.domain.model.Track
import java.time.Instant
import kotlin.math.ln
import kotlin.math.pow

/**
 * What the app knows about a listener's taste, as one distribution every recommendation asks questions of.
 *
 * Pure, deterministic and Android-free: same inputs, same answer. It is built from the listening log
 * ([PlayStat]) and the likes — data the Home already holds — so nothing here costs a network call.
 *
 * ### The weight of a track
 *
 * ```
 * w = recency × engagement × daypart      (+ LIKE_WEIGHT if it is liked)
 * ```
 *
 * - **recency** halves every [RECENCY_HALF_LIFE_H] hours. This is the difference between this profile
 *   and the one it replaces: the old statistics had no timestamps and used *position in the list* as a
 *   clock, so "yesterday" and "last March" were the same thing if you had not played much in between.
 * - **engagement** is retention, the way every streaming service ranks a known song: replays raise it
 *   (with diminishing returns — the tenth play says less than the second), finishing raises it, skipping
 *   lowers it. A track with **no outcomes recorded** scores exactly 1.0, so history written before the
 *   log existed is neither rewarded nor punished.
 * - **daypart** gives a mild lift to what this listener actually plays at this hour. Mild on purpose:
 *   the time of day should tilt an order, never decide it.
 *
 * ### Clusters
 *
 * [clusters] splits the taste into a few facets — the same idea as a Daily Mix. Two artists belong
 * together when they are **played in the same sitting** or share a genre; the co-play evidence comes
 * from grouping the log into sessions ([SESSION_GAP_MS]). It is an approximation, and knowingly so: the
 * log keeps only each track's *last* play, so a session is "things last heard around the same time",
 * not a replay of that evening. For deciding that corridos and lo-fi are different facets, it is enough.
 */
class TasteProfile(
    stats: List<PlayStat>,
    liked: List<Track> = emptyList(),
    private val nowMs: Long = System.currentTimeMillis(),
    /** The part of the day it is *now*, or null to skip the time-of-day tilt (tests, unknown zone). */
    private val daypart: Daypart? = null,
) {

    private val statsBySource = LinkedHashMap<ProviderRef, PlayStat>()
    private val tracks = LinkedHashMap<ProviderRef, Track>()
    private val weights = HashMap<ProviderRef, Float>()
    private val playedAt = HashMap<ProviderRef, Long>()
    private val scores = HashMap<String, Float>()
    private val plays = HashMap<String, Int>()
    private val names = HashMap<String, String>()
    private val byArtist = HashMap<String, MutableList<Track>>()

    init {
        stats.forEachIndexed { rank, stat ->
            statsBySource[stat.track.source] = stat
            epochMs(stat.lastPlayedAtIso)?.let { playedAt[stat.track.source] = it }
            add(stat.track, weight(stat, rank))
        }
        liked.forEach { add(it, LIKE_WEIGHT) }
    }

    val isEmpty: Boolean get() = tracks.isEmpty()
    val size: Int get() = tracks.size
    val artistCount: Int get() = scores.size

    /** Every known track, heaviest first. Ties keep input order, so the result is stable. */
    val ranked: List<Track> get() = tracks.values.sortedByDescending { weights[it.source] ?: 0f }

    /** The logged plays, heaviest first. Likes with no play of their own are not here — they were never played. */
    val rankedStats: List<PlayStat>
        get() = statsBySource.values.sortedByDescending { weights[it.track.source] ?: 0f }

    /** The logged plays in the order the log gave them: most recently played first. */
    val recentFirst: List<PlayStat> get() = statsBySource.values.toList()

    /** The logged plays, most-played first — "on repeat" asks for counts, not for freshness. */
    val mostPlayed: List<PlayStat>
        get() = statsBySource.values.sortedWith(
            compareByDescending<PlayStat> { it.plays }.thenByDescending { weights[it.track.source] ?: 0f },
        )

    /** Total weight of the top few tracks — the evidence a taste-based mix rests on. */
    val evidence: Float get() = weights.values.sortedDescending().take(EVIDENCE_TRACKS).sum()

    val artists: List<ArtistStat>
        get() = scores.entries
            .map { (key, score) -> ArtistStat(key, names[key] ?: key, score, plays[key] ?: 0) }
            // Name as the tie-break, so equal scores always order the same way.
            .sortedWith(compareByDescending<ArtistStat> { it.score }.thenBy { it.name })

    fun weightOf(source: ProviderRef): Float = weights[source] ?: 0f

    fun knows(track: Track): Boolean = track.artistKeys().any { it in scores }

    /** True when this exact recording has been played (a liked-but-never-played track is not). */
    fun hasPlayed(track: Track): Boolean = track.source in statsBySource

    /** How many plays the best-known artist credited on this track has. */
    fun playsOf(track: Track): Int = track.artistKeys().maxOfOrNull { plays[it] ?: 0 } ?: 0

    fun tracksOf(artistKey: String): List<Track> = byArtist[artistKey].orEmpty()

    /** Days since [stat] was last played, or null when it carries no usable timestamp. */
    fun ageDays(stat: PlayStat): Float? = playedAt[stat.track.source]
        ?.let { (nowMs - it).coerceAtLeast(0L).toFloat() / DAY_MS }

    /** The cluster's tracks that this listener actually knows, heaviest first. */
    fun tracksOf(cluster: TasteCluster): List<Track> =
        cluster.artistKeys.flatMap { byArtist[it].orEmpty() }
            .distinctBy { it.source }
            .sortedByDescending { weights[it.source] ?: 0f }

    /**
     * Up to [max] facets of this taste, strongest first.
     *
     * Seeds are the top-scoring artists that are **not linked to one another** — that is what makes the
     * facets different rather than three views of the same thing. Everyone else joins the seed they link
     * to best; an artist with no link at all joins the strongest cluster, which keeps the split total:
     * a cluster nobody falls into would quietly drop that music from every mix.
     */
    fun clusters(max: Int = MAX_CLUSTERS): List<TasteCluster> {
        if (max <= 0 || scores.isEmpty()) return emptyList()
        val ordered = artists
        val genres = artistGenres()
        val coPlay = coPlay()

        val seeds = mutableListOf<ArtistStat>()
        for (candidate in ordered) {
            if (seeds.size >= max) break
            val linked = seeds.any { link(candidate.key, it.key, coPlay, genres) >= LINK_MIN }
            if (!linked) seeds += candidate
        }
        if (seeds.isEmpty()) return emptyList()

        val members = seeds.map { mutableSetOf(it.key) }
        ordered.filter { stat -> seeds.none { it.key == stat.key } }.forEach { stat ->
            val best = seeds.indices.maxByOrNull { i -> link(stat.key, seeds[i].key, coPlay, genres) } ?: 0
            val home = if (link(stat.key, seeds[best].key, coPlay, genres) >= LINK_MIN) best else 0
            members[home] += stat.key
        }

        return seeds.mapIndexed { index, seed ->
            val keys = members[index]
            val named = ordered.filter { it.key in keys }
            TasteCluster(
                index = index,
                artistKeys = keys,
                artistNames = named.map { it.name },
                score = named.sumOf { it.score.toDouble() }.toFloat(),
                // The facet's genre is whatever most of its artists are, when anything is known at all.
                genre = named.mapNotNull { genres[it.key] }
                    .groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.key ?: SoundGenre.UNKNOWN,
                lead = seed.name,
            )
        }
    }

    // ---- Building -----------------------------------------------------------------------------

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

    private fun weight(stat: PlayStat, rank: Int): Float {
        val playedMs = epochMs(stat.lastPlayedAtIso)
        val recency = if (playedMs != null) {
            val hours = (nowMs - playedMs).coerceAtLeast(0L).toFloat() / HOUR_MS
            0.5f.pow(hours / RECENCY_HALF_LIFE_H)
        } else {
            // No usable timestamp (a row written before the log existed, or a stub): fall back to the
            // old clock — position in the list — rather than treating it as infinitely old.
            0.5f.pow(rank / RANK_HALF_LIFE)
        }
        val engagement = (
            (1f + REPLAY_WEIGHT * ln(1f + stat.plays.coerceAtLeast(0).toFloat())) *
                (COMPLETION_FLOOR + COMPLETION_SPAN * stat.completionRate) *
                (1f - SKIP_PENALTY * stat.skipRate)
            ).coerceIn(MIN_ENGAGEMENT, MAX_ENGAGEMENT)
        val hour = daypart?.let { 1f + DAYPART_BOOST * stat.share(it) } ?: 1f
        return recency * engagement * hour
    }

    /** The dominant [SoundGenre] per artist, from whatever tags their tracks happen to carry. */
    private fun artistGenres(): Map<String, SoundGenre> {
        val votes = HashMap<String, MutableMap<SoundGenre, Int>>()
        tracks.values.forEach { track ->
            val genre = track.soundGenre()
            if (genre == SoundGenre.UNKNOWN) return@forEach
            track.artistKeys().forEach { key ->
                val tally = votes.getOrPut(key) { mutableMapOf() }
                tally[genre] = (tally[genre] ?: 0) + 1
            }
        }
        return votes.mapNotNull { (key, tally) -> tally.maxByOrNull { it.value }?.let { key to it.key } }.toMap()
    }

    /**
     * How many sittings each pair of artists shared.
     *
     * Tracks are ordered by when they were last played and cut wherever the gap exceeds
     * [SESSION_GAP_MS]; every artist in a sitting is counted with every other. Tracks with no
     * timestamp take part in nothing — they cannot be placed in time.
     */
    private fun coPlay(): Map<String, Map<String, Int>> {
        val timed = statsBySource.values
            .mapNotNull { stat -> playedAt[stat.track.source]?.let { it to stat.track } }
            .sortedBy { it.first }
        if (timed.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableMap<String, Int>>()
        var session = mutableSetOf<String>()
        var previous = timed.first().first
        fun close() {
            val keys = session.toList()
            for (i in keys.indices) {
                for (j in i + 1 until keys.size) {
                    out.getOrPut(keys[i]) { mutableMapOf() }.merge(keys[j], 1, Int::plus)
                    out.getOrPut(keys[j]) { mutableMapOf() }.merge(keys[i], 1, Int::plus)
                }
            }
            session = mutableSetOf()
        }
        timed.forEach { (at, track) ->
            if (at - previous > SESSION_GAP_MS) close()
            session += track.artistKeys()
            previous = at
        }
        close()
        return out
    }

    private fun link(
        a: String,
        b: String,
        coPlay: Map<String, Map<String, Int>>,
        genres: Map<String, SoundGenre>,
    ): Int {
        if (a == b) return Int.MAX_VALUE
        val together = coPlay[a]?.get(b) ?: 0
        val sameGenre = genres[a] != null && genres[a] == genres[b]
        return CO_PLAY_WEIGHT * together + if (sameGenre) GENRE_WEIGHT else 0
    }

    private fun epochMs(iso: String): Long? =
        iso.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private companion object {
        const val HOUR_MS = 3_600_000f
        const val DAY_MS = 86_400_000f

        /** A play counts half as much every three days — the pace at which a listener's mood moves. */
        const val RECENCY_HALF_LIFE_H = 72f

        /** The old clock, kept for rows that carry no timestamp: positions back, not hours. */
        const val RANK_HALF_LIFE = 8f

        const val REPLAY_WEIGHT = 0.35f
        const val COMPLETION_FLOOR = 0.4f
        const val COMPLETION_SPAN = 1.2f
        const val SKIP_PENALTY = 0.5f
        const val MIN_ENGAGEMENT = 0.15f
        const val MAX_ENGAGEMENT = 3f

        /** At most a third more weight for music played at this hour. A tilt, not a verdict. */
        const val DAYPART_BOOST = 0.35f

        /** Songs last heard within half an hour of each other were one sitting. */
        const val SESSION_GAP_MS = 30 * 60 * 1000L

        const val CO_PLAY_WEIGHT = 2
        const val GENRE_WEIGHT = 1

        /** Below this two artists are strangers, and one of them may open a new facet. */
        const val LINK_MIN = 1

        const val MAX_CLUSTERS = 3
        const val EVIDENCE_TRACKS = 6
    }
}

/** One artist's standing in the taste distribution. */
data class ArtistStat(val key: String, val name: String, val score: Float, val plays: Int)

/**
 * One facet of a listener's taste — the artists behind a single daily mix.
 *
 * [lead] is the artist the facet formed around, which is what the mosaic tile is named after; [label]
 * is the fuller "Tyla, Doja Cat, The Weeknd" line the hero card shows.
 */
data class TasteCluster(
    val index: Int,
    val artistKeys: Set<String>,
    /** Display names, strongest first. */
    val artistNames: List<String>,
    val score: Float,
    val genre: SoundGenre,
    val lead: String,
) {
    val label: String get() = artistNames.take(LABEL_ARTISTS).joinToString(", ")

    private companion object {
        const val LABEL_ARTISTS = 3
    }
}

/** What an explicit like is worth — a little under a just-played track. */
const val LIKE_WEIGHT = 0.8f

/**
 * Rotates a list by [by], so a pool's turn comes round instead of always starting at the top.
 *
 * Fed the day number, it is what makes a mix or a row differ from yesterday's while staying identical
 * all day — variety without a lottery that would reshuffle the screen under the reader.
 */
internal fun <T> List<T>.rotatedBy(by: Long): List<T> {
    if (size <= 1) return this
    val k = (((by % size) + size) % size).toInt()
    return drop(k) + take(k)
}

/** The folded artist keys credited on a track, in credit order and without repeats. */
internal fun Track.artistKeys(): List<String> = artists.asSequence()
    .map { it.name }
    .filter { it.isNotBlank() }
    .map { ArtistNameMatching.key(it) }
    .filter { it.isNotEmpty() }
    .distinct()
    .toList()

/**
 * The genre family a track declares, from the first tag anything recognises.
 *
 * Most catalogue tracks carry no tags at all (Deezer publishes genres on the album, not the song), so
 * [SoundGenre.UNKNOWN] is the ordinary case rather than a failure — the clustering simply leans on
 * co-play instead.
 */
internal fun Track.soundGenre(): SoundGenre = tags.asSequence()
    .map { GenreClassifier.classify(it) }
    .firstOrNull { it != SoundGenre.UNKNOWN }
    ?: SoundGenre.UNKNOWN
