package fm.rizx.player.data.repository

import fm.rizx.player.data.local.db.FavoriteDao
import fm.rizx.player.data.local.db.FavoriteEntity
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesRepositoryTest {

    /** In-memory [FavoriteDao] replicating the `IGNORE`-insert (idempotent) semantics. */
    private class FakeFavoriteDao : FavoriteDao {
        val rows = MutableStateFlow<List<FavoriteEntity>>(emptyList())
        private fun matches(e: FavoriteEntity, type: String, provider: String, sourceId: String) =
            e.type == type && e.provider == provider && e.sourceId == sourceId

        override suspend fun insert(entity: FavoriteEntity) {
            if (rows.value.none { matches(it, entity.type, entity.provider, entity.sourceId) }) {
                rows.value = rows.value + entity
            }
        }

        override suspend fun delete(type: String, provider: String, sourceId: String) {
            rows.value = rows.value.filterNot { matches(it, type, provider, sourceId) }
        }

        override fun observeByType(type: String): Flow<List<FavoriteEntity>> =
            rows.map { list -> list.filter { it.type == type }.sortedByDescending { it.addedAtIso } }

        override fun observeExists(type: String, provider: String, sourceId: String): Flow<Boolean> =
            rows.map { list -> list.any { matches(it, type, provider, sourceId) } }

        override suspend fun exists(type: String, provider: String, sourceId: String): Boolean =
            rows.value.any { matches(it, type, provider, sourceId) }
    }

    private var clock = 0
    private fun repo(dao: FavoriteDao) = FavoritesRepositoryImpl(dao, nowIso = { "t${clock++}" })

    private fun track(id: String, provider: String = "meta") =
        Track(title = "Song $id", source = ProviderRef(provider, id))

    @Test
    fun `re-adding a track is idempotent and preserves the original timestamp`() = runTest {
        val dao = FakeFavoriteDao()
        val repo = repo(dao)
        val song = track("tr-1")

        repo.addTrack(song) // t0
        repo.addTrack(song) // ignored

        assertEquals(1, dao.rows.value.size)
        assertEquals("t0", dao.rows.value.single().addedAtIso)
    }

    @Test
    fun `favorites from different providers are distinct`() = runTest {
        val dao = FakeFavoriteDao()
        val repo = repo(dao)

        repo.addTrack(track("same-id", provider = "youtube"))
        repo.addTrack(track("same-id", provider = "bandcamp"))

        assertEquals(2, repo.favoriteTracks().first().size)
    }

    @Test
    fun `toggle adds then removes a track`() = runTest {
        val dao = FakeFavoriteDao()
        val repo = repo(dao)
        val song = track("tr-1")

        assertTrue(repo.toggleTrack(song))
        assertEquals(1, repo.favoriteTracks().first().size)
        assertFalse(repo.toggleTrack(song))
        assertTrue(repo.favoriteTracks().first().isEmpty())
    }

    @Test
    fun `isFavoriteTrack reflects membership`() = runTest {
        val dao = FakeFavoriteDao()
        val repo = repo(dao)
        val song = track("tr-1")

        assertFalse(repo.isFavoriteTrack(song.source).first())
        repo.addTrack(song)
        assertTrue(repo.isFavoriteTrack(song.source).first())
    }
}
