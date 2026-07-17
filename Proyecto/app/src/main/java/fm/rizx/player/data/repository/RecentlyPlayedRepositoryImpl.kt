package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.RecentlyPlayedDao
import fm.rizx.player.data.local.db.RecentlyPlayedEntity
import fm.rizx.player.data.local.store.TrackJson
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Room-backed [RecentlyPlayedRepository]. Each play upserts by `ProviderRef` identity (so replays bump
 * the timestamp instead of duplicating), stores the resolution-stripped track, and prunes to
 * [MAX_ENTRIES]. [nowIso] is injectable for tests.
 */
class RecentlyPlayedRepositoryImpl(
    private val dao: RecentlyPlayedDao,
    private val nowIso: () -> String = { Instant.now().toString() },
) : RecentlyPlayedRepository {

    override fun recent(limit: Int): Flow<List<Track>> =
        dao.observe(limit).map { rows -> rows.map { TrackJson.decodeTrack(it.trackJson) } }

    override suspend fun record(track: Track) {
        dao.upsert(
            RecentlyPlayedEntity(
                provider = track.source.provider,
                sourceId = track.source.id,
                trackJson = TrackJson.encodeTrack(track), // strips ephemeral stream state
                playedAtIso = nowIso(),
            ),
        )
        dao.prune(MAX_ENTRIES)
    }

    override suspend fun clear() = dao.clear()

    companion object {
        const val MAX_ENTRIES = 50
    }
}
