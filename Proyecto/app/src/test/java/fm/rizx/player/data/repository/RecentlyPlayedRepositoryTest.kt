package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.RecentlyPlayedDao
import fm.rizx.player.data.local.db.RecentlyPlayedEntity
import fm.rizx.player.domain.model.Daypart
import fm.rizx.player.domain.model.PlayOutcome
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class RecentlyPlayedRepositoryTest {

    /**
     * Fake DAO mirroring Room semantics: upsert = REPLACE by (provider, sourceId); observe =
     * newest-first; prune = most-played first, then newest, exactly like the query.
     */
    private class FakeDao : RecentlyPlayedDao {
        val rows = MutableStateFlow<Map<String, RecentlyPlayedEntity>>(emptyMap())
        private fun key(e: RecentlyPlayedEntity) = "${e.provider}:${e.sourceId}"
        override suspend fun upsert(entry: RecentlyPlayedEntity) { rows.value = rows.value + (key(entry) to entry) }
        override suspend fun find(provider: String, sourceId: String): RecentlyPlayedEntity? =
            rows.value["$provider:$sourceId"]
        override fun observe(limit: Int): Flow<List<RecentlyPlayedEntity>> =
            rows.map { m -> m.values.sortedByDescending { it.playedAtIso }.take(limit) }
        override suspend fun prune(keep: Int) {
            val kept = rows.value.values
                .sortedWith(compareByDescending<RecentlyPlayedEntity> { it.playCount }.thenByDescending { it.playedAtIso })
                .take(keep).map { key(it) }.toSet()
            rows.value = rows.value.filterKeys { it in kept }
        }
        override suspend fun clear() { rows.value = emptyMap() }
    }

    private var clock = 0L
    private fun repo(dao: RecentlyPlayedDao) =
        RecentlyPlayedRepositoryImpl(dao, nowIso = { "t${clock++}" })

    private fun track(title: String) = Track(title = title, source = ProviderRef("itunes", "id-$title"))

    @Test
    fun `replaying a track dedups by identity and moves it to the front`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)

        repo.record(track("Yellow"))   // t0
        repo.record(track("Clocks"))   // t1
        repo.record(track("Yellow"))   // t2 — same identity, newer timestamp

        val recent = repo.recent().first()
        assertEquals(listOf("Yellow", "Clocks"), recent.map { it.title }) // one Yellow, now first
    }

    @Test
    fun `history is newest first`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)

        repo.record(track("A"))
        repo.record(track("B"))
        repo.record(track("C"))

        assertEquals(listOf("C", "B", "A"), repo.recent().first().map { it.title })
    }

    @Test
    fun `stored track carries no resolution state`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        val resolved = track("Yellow").copy(
            streamCandidates = listOf(
                StreamCandidate(
                    id = "c1", title = "Yellow",
                    stream = Stream("https://ephemeral/x.m4a", StreamProtocol.HTTPS, source = ProviderRef("s", "c1")),
                    source = ProviderRef("s", "c1"),
                ),
            ),
        )

        repo.record(resolved)

        assertTrue(repo.recent().first().single().streamCandidates.isEmpty())
    }

    @Test
    fun `clear empties the history`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        repo.record(track("A"))

        repo.clear()

        assertTrue(repo.recent().first().isEmpty())
    }

    // ---- The listening log (v4) -----------------------------------------------------------------

    @Test
    fun `a replay adds to the count and keeps the first play`() = runTest {
        val repo = repo(FakeDao())

        repeat(3) { repo.record(track("Yellow")) } // t0, t1, t2

        val stat = repo.stats().first().single()
        assertEquals(3, stat.plays)
        assertEquals("t0", stat.firstPlayedAtIso)
        assertEquals("t2", stat.lastPlayedAtIso)
    }

    @Test
    fun `finishing and skipping are counted separately`() = runTest {
        val repo = repo(FakeDao())
        val yellow = track("Yellow")
        repo.record(yellow)

        repo.recordOutcome(yellow.source, listenedMs = 210_000, outcome = PlayOutcome.COMPLETED)
        repo.recordOutcome(yellow.source, listenedMs = 4_000, outcome = PlayOutcome.SKIPPED)

        val stat = repo.stats().first().single()
        assertEquals(1, stat.completions)
        assertEquals(1, stat.skips)
        assertEquals(214_000L, stat.msListened)
        assertEquals(0.5f, stat.completionRate, 0.001f)
    }

    @Test
    fun `an outcome for a track that was never played is ignored`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)

        repo.recordOutcome(track("Ghost").source, listenedMs = 1_000, outcome = PlayOutcome.COMPLETED)

        assertTrue("a stray transition must not invent history", dao.rows.value.isEmpty())
    }

    @Test
    fun `a play is counted in the part of the day it happened`() = runTest {
        val dao = FakeDao()
        val morning = RecentlyPlayedRepositoryImpl(
            dao,
            nowIso = { "2026-07-30T08:30:00Z" },
            zone = { ZoneId.of("UTC") },
        )

        morning.record(track("Yellow"))

        val stat = morning.stats().first().single()
        assertEquals(1f, stat.share(Daypart.MORNING), 0.001f)
        assertEquals(0f, stat.share(Daypart.NIGHT), 0.001f)
    }

    @Test
    fun `pruning keeps a much-played old song over a barely-played new one`() = runTest {
        val dao = FakeDao()
        val repo = repo(dao)
        // "Old" is played five times first, then thirty other songs are played once each.
        repeat(5) { repo.record(track("Old")) }
        repeat(30) { repo.record(track("New $it")) }

        dao.prune(keep = 3)

        assertTrue(
            "the material Rediscover is made of must survive the prune",
            dao.rows.value.values.any { it.sourceId == "id-Old" },
        )
    }
}
