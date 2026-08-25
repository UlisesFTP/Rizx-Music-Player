package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.RecentlyPlayedDao
import fm.rizx.player.data.local.db.RecentlyPlayedEntity
import fm.rizx.player.data.local.db.SyncOutboxEntity
import fm.rizx.player.data.local.db.TasteContributionDao
import fm.rizx.player.data.local.db.TasteContributionEntity
import fm.rizx.player.data.local.store.TrackJson
import fm.rizx.player.data.local.store.PortableTrackSanitizer
import fm.rizx.player.domain.model.Daypart
import fm.rizx.player.domain.model.PlayOutcome
import fm.rizx.player.domain.model.PlayStat
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Room-backed [RecentlyPlayedRepository]. Each play upserts by `ProviderRef` identity (so replays bump
 * the same row instead of duplicating), stores the resolution-stripped track, and prunes to
 * [MAX_ENTRIES]. [nowIso] and [zone] are injectable for tests.
 *
 * **Counters are summed here, not in SQL.** The DAO upserts with `REPLACE`, which rewrites the whole
 * row, so every write reads the existing one first and adds to it. Doing the arithmetic in Kotlin also
 * keeps "what counts as a play" testable without a database.
 *
 * **What is read is more than what is written.** `recently_played` is this device's own count and the
 * only thing it publishes; [contributions] holds every other signed-in device's row for the same
 * tracks. [recent] and [stats] serve the sum, so a song played on the phone and the tablet counts on
 * both — and a song played only on the tablet still shows up here.
 */
class RecentlyPlayedRepositoryImpl(
    private val dao: RecentlyPlayedDao,
    private val contributions: TasteContributionDao,
    private val nowIso: () -> String = { Instant.now().toString() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val json: Json = Json { encodeDefaults = true },
) : RecentlyPlayedRepository {

    override fun recent(limit: Int): Flow<List<Track>> =
        merged(limit).map { rows -> rows.map { TrackJson.decodeTrack(it.trackJson) } }

    override fun stats(limit: Int): Flow<List<PlayStat>> =
        merged(limit).map { rows -> rows.map { it.toPlayStat() } }

    /** This device's rows plus every other device's, one per track, newest first. */
    private fun merged(limit: Int): Flow<List<RecentlyPlayedEntity>> =
        combine(dao.observe(MAX_ENTRIES), contributions.observeAll()) { local, remote -> merge(local, remote) }
            .map { rows -> rows.sortedByDescending { it.playedAtIso }.take(limit) }

    /**
     * Counters add up across devices; the first play is the earliest anywhere, the last the latest.
     * The stored track is this device's when it has one (it is refreshed on every local play), else
     * the most recently playing device's.
     */
    internal fun merge(local: List<RecentlyPlayedEntity>, remote: List<TasteContributionEntity>): List<RecentlyPlayedEntity> {
        if (remote.isEmpty()) return local
        val byKey = LinkedHashMap<String, RecentlyPlayedEntity>()
        local.forEach { byKey["${it.provider}:${it.sourceId}"] = it }
        remote.groupBy { "${it.provider}:${it.sourceId}" }.forEach { (key, rows) ->
            val newest = rows.maxBy { it.playedAtIso }
            val seed = byKey[key] ?: RecentlyPlayedEntity(
                provider = newest.provider, sourceId = newest.sourceId, trackJson = newest.trackJson,
                playedAtIso = "", playCount = 0, firstPlayedAtIso = "",
            )
            byKey[key] = rows.fold(seed) { acc, row ->
                acc.copy(
                    playCount = acc.playCount + row.playCount,
                    completedCount = acc.completedCount + row.completedCount,
                    skipCount = acc.skipCount + row.skipCount,
                    msListened = acc.msListened + row.msListened,
                    partNight = acc.partNight + row.partNight,
                    partMorning = acc.partMorning + row.partMorning,
                    partAfternoon = acc.partAfternoon + row.partAfternoon,
                    partEvening = acc.partEvening + row.partEvening,
                    playedAtIso = maxOf(acc.playedAtIso, row.playedAtIso),
                    firstPlayedAtIso = listOf(acc.firstPlayedAtIso, row.firstPlayedAtIso)
                        .filter(String::isNotBlank).minOrNull().orEmpty(),
                )
            }
        }
        return byKey.values.toList()
    }

    override suspend fun record(track: Track) {
        val now = nowIso()
        val part = daypartOf(now)
        val existing = dao.find(track.source.provider, track.source.id)
        val updated =
            existing?.bump(now, part, TrackJson.encodeTrack(PortableTrackSanitizer.sanitize(track)))
                ?: RecentlyPlayedEntity(
                    provider = track.source.provider,
                    sourceId = track.source.id,
                    trackJson = TrackJson.encodeTrack(PortableTrackSanitizer.sanitize(track)),
                    playedAtIso = now,
                    playCount = 1,
                    firstPlayedAtIso = now,
                ).withDaypart(part, 1)
        dao.upsertWithJournal(updated, journal(updated, now))
        dao.prune(MAX_ENTRIES)
    }

    /**
     * Adds an outcome to a row that already exists. A track with no row was never recorded as playing,
     * and inventing one here would let a stray transition create history the listener never made.
     */
    override suspend fun recordOutcome(source: ProviderRef, listenedMs: Long, outcome: PlayOutcome) {
        val existing = dao.find(source.provider, source.id) ?: return
        val now = nowIso()
        val updated = existing.copy(
                msListened = existing.msListened + listenedMs.coerceAtLeast(0),
                completedCount = existing.completedCount + if (outcome == PlayOutcome.COMPLETED) 1 else 0,
                skipCount = existing.skipCount + if (outcome == PlayOutcome.SKIPPED) 1 else 0,
            )
        dao.upsertWithJournal(updated, journal(updated, now))
    }

    /**
     * Clears what *this device* played and tells the cloud, one delete per row. Other devices' rows are
     * theirs to clear: they stay in [contributions] and keep showing.
     */
    override suspend fun clear() {
        val now = nowIso()
        val deletes = dao.all().map { row ->
            SyncOutboxEntity(
                operationId = newId(), entityType = "TASTE", entityId = "${row.provider}:${row.sourceId}",
                operation = "DELETE", payloadJson = null, createdAtIso = now,
            )
        }
        dao.clearWithJournal(deletes)
    }

    private fun journal(entry: RecentlyPlayedEntity, now: String) = SyncOutboxEntity(
        operationId = newId(), entityType = "TASTE", entityId = "${entry.provider}:${entry.sourceId}",
        operation = "UPSERT", payloadJson = json.encodeToString(RecentlyPlayedEntity.serializer(), entry),
        createdAtIso = now,
    )

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
