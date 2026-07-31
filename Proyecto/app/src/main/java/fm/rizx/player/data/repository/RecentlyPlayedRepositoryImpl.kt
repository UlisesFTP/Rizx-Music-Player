package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.RecentlyPlayedDao
import fm.rizx.player.data.local.db.RecentlyPlayedEntity
import fm.rizx.player.data.local.store.TrackJson
import fm.rizx.player.domain.model.Daypart
import fm.rizx.player.domain.model.PlayOutcome
import fm.rizx.player.domain.model.PlayStat
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId

/**
 * Room-backed [RecentlyPlayedRepository]. Each play upserts by `ProviderRef` identity (so replays bump
 * the same row instead of duplicating), stores the resolution-stripped track, and prunes to
 * [MAX_ENTRIES]. [nowIso] and [zone] are injectable for tests.
 *
 * **Counters are summed here, not in SQL.** The DAO upserts with `REPLACE`, which rewrites the whole
 * row, so every write reads the existing one first and adds to it. Doing the arithmetic in Kotlin also
 * keeps "what counts as a play" testable without a database.
 */
class RecentlyPlayedRepositoryImpl(
    private val dao: RecentlyPlayedDao,
    private val nowIso: () -> String = { Instant.now().toString() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : RecentlyPlayedRepository {

    override fun recent(limit: Int): Flow<List<Track>> =
        dao.observe(limit).map { rows -> rows.map { TrackJson.decodeTrack(it.trackJson) } }

    override fun stats(limit: Int): Flow<List<PlayStat>> =
        dao.observe(limit).map { rows -> rows.map { it.toPlayStat() } }

    override suspend fun record(track: Track) {
        val now = nowIso()
        val part = daypartOf(now)
        val existing = dao.find(track.source.provider, track.source.id)
        dao.upsert(
            existing?.bump(now, part, TrackJson.encodeTrack(track))
                ?: RecentlyPlayedEntity(
                    provider = track.source.provider,
                    sourceId = track.source.id,
                    trackJson = TrackJson.encodeTrack(track), // strips ephemeral stream state
                    playedAtIso = now,
                    playCount = 1,
                    firstPlayedAtIso = now,
                ).withDaypart(part, 1),
        )
        dao.prune(MAX_ENTRIES)
    }

    /**
     * Adds an outcome to a row that already exists. A track with no row was never recorded as playing,
     * and inventing one here would let a stray transition create history the listener never made.
     */
    override suspend fun recordOutcome(source: ProviderRef, listenedMs: Long, outcome: PlayOutcome) {
        val existing = dao.find(source.provider, source.id) ?: return
        dao.upsert(
            existing.copy(
                msListened = existing.msListened + listenedMs.coerceAtLeast(0),
                completedCount = existing.completedCount + if (outcome == PlayOutcome.COMPLETED) 1 else 0,
                skipCount = existing.skipCount + if (outcome == PlayOutcome.SKIPPED) 1 else 0,
            ),
        )
    }

    override suspend fun clear() = dao.clear()

    // ---- Row arithmetic ---------------------------------------------------------------------------

    private fun RecentlyPlayedEntity.bump(nowIso: String, part: Daypart, trackJson: String) = copy(
        // The stored track is refreshed on every play: artwork and credits improve over time, and the
        // row is what the Home renders.
        trackJson = trackJson,
        playedAtIso = nowIso,
        playCount = playCount + 1,
        // Rows written before v4 carry no first play; the first bump after upgrading adopts their last.
        firstPlayedAtIso = firstPlayedAtIso.ifBlank { playedAtIso },
    ).withDaypart(part, 1)

    private fun RecentlyPlayedEntity.withDaypart(part: Daypart, delta: Int) = when (part) {
        Daypart.NIGHT -> copy(partNight = partNight + delta)
        Daypart.MORNING -> copy(partMorning = partMorning + delta)
        Daypart.AFTERNOON -> copy(partAfternoon = partAfternoon + delta)
        Daypart.EVENING -> copy(partEvening = partEvening + delta)
    }

    private fun RecentlyPlayedEntity.toPlayStat() = PlayStat(
        track = TrackJson.decodeTrack(trackJson),
        plays = playCount.coerceAtLeast(1),
        completions = completedCount,
        skips = skipCount,
        msListened = msListened,
        firstPlayedAtIso = firstPlayedAtIso,
        lastPlayedAtIso = playedAtIso,
        dayparts = listOf(partNight, partMorning, partAfternoon, partEvening),
    )

    /** The part of the day [iso] falls in, in the device's own zone — 8 a.m. means 8 a.m. here. */
    private fun daypartOf(iso: String): Daypart =
        runCatching { Daypart.ofHour(Instant.parse(iso).atZone(zone()).hour) }
            .getOrDefault(Daypart.EVENING)

    companion object {
        /**
         * Deep enough for the statistics to mean something: the recency decay, "on repeat" and
         * "rediscover" all need a distribution, and fifty rows is a fortnight of listening.
         */
        const val MAX_ENTRIES = 300
    }
}
