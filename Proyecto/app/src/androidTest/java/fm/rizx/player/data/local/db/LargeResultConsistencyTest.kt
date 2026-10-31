package fm.rizx.player.data.local.db

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A result larger than one CursorWindow (about 2 MB) must survive the table shrinking under it.
 *
 * Android caches the row count on the first window and re-runs the query for each later one. Outside a
 * transaction a delete between two windows leaves the cursor positioned past the new end, and the read
 * throws `Couldn't read row N, col 0 from CursorWindow` (N counts from the window's start, not the
 * table's). That took the app down on 2026-10-30: Home was observing `taste_contributions` while sync
 * upserted and pruned another device's rows, so one prune was enough.
 *
 * Instrumented because it needs real SQLite in WAL mode, where a reader and a writer really overlap.
 * The race is timing-dependent, so each test keeps reading for [RACE_MILLIS].
 */
@RunWith(AndroidJUnit4::class)
class LargeResultConsistencyTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: RizxDatabase

    @Before
    fun openDatabase() {
        context.deleteDatabase(TEST_DB)
        db = Room.databaseBuilder(context, RizxDatabase::class.java, TEST_DB)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }

    @After
    fun closeDatabase() {
        db.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun tasteContributions_surviveUpsertAndPruneDuringARead() = runBlocking {
        val dao = db.tasteContributionDao()
        repeat(CAP) { dao.upsert(contribution(WEB_DEVICE, "seed-$it")) }
        repeat(146) { dao.upsert(contribution(OTHER_DEVICE, "other-$it")) }

        keepReadingWhile(dao.observeAll()) { round ->
            dao.upsert(contribution(WEB_DEVICE, "new-$round"))
            dao.prune(WEB_DEVICE, CAP)
        }
    }

    @Test
    fun playlistDigests_surviveItemsBeingRemovedDuringARead() = runBlocking {
        val dao = db.playlistDao()
        dao.insertPlaylist(
            PlaylistEntity(PLAYLIST, "Long", null, ISO, ISO, isReadOnly = false, parentId = null, originProvider = null, originId = null),
        )
        repeat(CAP) { dao.insertItem(item("seed-$it", it)) }

        keepReadingWhile(dao.observeItemDigests()) { round ->
            dao.insertItem(item("new-$round", CAP + round))
            dao.deleteItem("new-$round")
        }
    }

    /** Fails with the read's own exception if a window swap ever lands past the end of the table. */
    private suspend fun <T> keepReadingWhile(reads: Flow<List<T>>, write: suspend (round: Int) -> Unit) {
        kotlinx.coroutines.coroutineScope {
            val writer = launch(Dispatchers.IO) {
                var round = 0
                while (isActive) write(round++)
            }
            var completed = 0
            val deadline = System.currentTimeMillis() + RACE_MILLIS
            try {
                while (System.currentTimeMillis() < deadline) {
                    reads.first()
                    completed++
                }
            } finally {
                writer.cancelAndJoin()
            }
            assertTrue("no read finished in ${RACE_MILLIS} ms", completed > 0)
        }
    }

    private fun contribution(device: String, id: String) = TasteContributionEntity(
        deviceId = device,
        provider = "deezer",
        sourceId = id,
        trackJson = LARGE_JSON,
        playedAtIso = ISO,
        playCount = 1,
    )

    private fun item(id: String, order: Int) = PlaylistItemEntity(id, PLAYLIST, order, LARGE_JSON, null, ISO)

    private companion object {
        const val TEST_DB = "large-result-test.db"
        const val WEB_DEVICE = "web"
        const val OTHER_DEVICE = "other"
        const val PLAYLIST = "p1"
        const val ISO = "2026-10-30T00:00:00Z"
        const val CAP = 300
        const val RACE_MILLIS = 20_000L

        /** 300 rows of this are 2.4 MB: every read crosses at least one window swap. */
        val LARGE_JSON = "x".repeat(8 * 1024)
    }
}
