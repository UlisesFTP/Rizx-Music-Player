package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.RecentlyPlayedDao
import fm.rizx.player.data.local.db.RecentlyPlayedEntity
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

class RecentlyPlayedRepositoryTest {

    /** Fake DAO mirroring Room semantics: upsert = REPLACE by (provider, sourceId); observe = newest-first. */
    private class FakeDao : RecentlyPlayedDao {
        val rows = MutableStateFlow<Map<String, RecentlyPlayedEntity>>(emptyMap())
        private fun key(e: RecentlyPlayedEntity) = "${e.provider}:${e.sourceId}"
        override suspend fun upsert(entry: RecentlyPlayedEntity) { rows.value = rows.value + (key(entry) to entry) }
        override fun observe(limit: Int): Flow<List<RecentlyPlayedEntity>> =
            rows.map { m -> m.values.sortedByDescending { it.playedAtIso }.take(limit) }
        override suspend fun prune(keep: Int) {
            val kept = rows.value.values.sortedByDescending { it.playedAtIso }.take(keep).map { key(it) }.toSet()
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
}
