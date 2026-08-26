package fm.rizx.player.data.sync

import fm.rizx.player.data.local.db.InMemorySyncDao
import fm.rizx.player.data.local.db.SyncOutboxEntity
import fm.rizx.player.data.local.db.SyncStateEntity
import fm.rizx.player.data.local.store.SyncPrefsStore
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.domain.repository.PlaylistExportArtifact
import fm.rizx.player.domain.repository.PlaylistExportFormat
import fm.rizx.player.domain.repository.PlaylistExportRepository
import fm.rizx.player.domain.sync.SyncApplied
import fm.rizx.player.domain.sync.SyncReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * The runner against an in-memory replica of the edge function. The server echoes every push back in
 * the same response and knows nothing about devices; the runner is what has to be careful.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val api = FakeSyncApi()
    private val syncDao = InMemorySyncDao()
    private val applier = FakeApplier()
    private val events = SyncEvents()
    private val journal = FakeJournal()
    private val exportedPlaylists = mutableMapOf<String, String>()
    private val exports = object : PlaylistExportRepository {
        override suspend fun export(playlistId: String, format: PlaylistExportFormat): PlaylistExportArtifact? =
            exportedPlaylists[playlistId]?.let { PlaylistExportArtifact("$playlistId.json", "application/json", it, 1, 0) }
    }
    private var paused = false
    private var stamp = 0
    private val logs = mutableListOf<String>()

    private fun prefs() = SyncPrefsStore(File(tmp.root, "sync_prefs.json"))

    private fun runner(account: FakeAccount = FakeAccount()) = SyncRunner(
        account = account, syncDao = syncDao, api = api, exports = exports, applier = applier, journal = journal,
        prefs = prefs(), events = events, json = json,
        tasteUploadsPaused = { paused }, now = { Instant.parse("2026-08-21T12:00:00Z") }, newDeviceId = { "dev-A" },
        log = { logs += it },
    )

    /** Most tests start with the account already set up on this device: the backfill is not the subject. */
    private fun alreadyBackfilled() {
        syncDao.states["acct-1"] = SyncStateEntity("acct-1", "dev-A", cursor = 0, backfilledAtIso = "2026-08-01T00:00:00Z")
    }

    private fun op(
        entityId: String,
        type: String = SyncDocuments.FAVORITE,
        operation: String = "UPSERT",
        payload: String? = """{"json":"$entityId"}""",
        attempts: Int = 0,
    ) = SyncOutboxEntity(
        operationId = java.util.UUID.nameUUIDFromBytes("$type:$entityId:$operation".toByteArray()).toString(),
        entityType = type, entityId = entityId, operation = operation, payloadJson = payload,
        createdAtIso = "2026-08-21T00:00:00.%06dZ".format(stamp++),
        attemptCount = attempts,
    )

    private fun doc(text: String): JsonElement = json.parseToJsonElement(text)

    @Test
    fun `drains the outbox in batches until it is empty`() = runTest {
        alreadyBackfilled()
        repeat(120) { syncDao.outbox.insert(op("TRACK:deezer:$it")) }

        assertEquals(SyncRunner.Outcome.SUCCESS, runner().run())

        assertEquals(listOf(100, 20), api.requests.map { it.operations.size })
        assertEquals(0, syncDao.pendingCount())
        assertEquals(120, api.records.size)
        assertTrue("its own writes never come back as changes", applier.applied.isEmpty())
    }

    @Test
    fun `pulls every page the server has, not just the first`() = runTest {
        alreadyBackfilled()
        repeat(1200) { api.seed(SyncDocuments.FAVORITE, "TRACK:deezer:$it", doc("""{"i":$it}""")) }

        runner().run()

        assertEquals(3, api.requests.size)
        assertEquals(1200, applier.applied.size)
        assertEquals(1200L, syncDao.states["acct-1"]!!.cursor)
    }

    @Test
    fun `an echo of what was just pushed is skipped, a real remote change is applied`() = runTest {
        alreadyBackfilled()
        syncDao.outbox.insert(op("TRACK:deezer:mine"))
        api.seed(SyncDocuments.FAVORITE, "TRACK:deezer:theirs", doc("""{"json":"theirs"}"""))

        runner().run()

        assertEquals(listOf("TRACK:deezer:theirs"), applier.applied.map { it.entityId })
    }

    @Test
    fun `a remote playlist loses to a dirty local edit and is kept as a snapshot`() = runTest {
        alreadyBackfilled()
        exportedPlaylists["p1"] = """{"name":"local"}"""
        syncDao.outbox.insert(op("p1", type = SyncDocuments.PLAYLIST, payload = null))
        val remote = api.seed(SyncDocuments.PLAYLIST, "p1", doc("""{"name":"remote"}"""))
        // The server refuses our edit this round, so the key stays dirty when the remote one arrives.
        api.accepts = { it.entityId != "p1" }

        runner().run()

        assertTrue(applier.applied.none { it.entityId == "p1" })
        assertEquals(listOf("p1" to remote.document), applier.snapshots)
        assertEquals("still pending, will win next round", 1, syncDao.pendingCount())
    }

    @Test
    fun `operations the server keeps refusing are dropped after eight attempts`() = runTest {
        alreadyBackfilled()
        syncDao.outbox.insert(op("TRACK:deezer:poison", attempts = SyncRunner.MAX_ATTEMPTS))
        syncDao.outbox.insert(op("TRACK:deezer:fine"))

        runner().run()

        assertEquals(listOf("TRACK:deezer:fine"), api.requests.single().operations.map { it.entityId })
        assertEquals(0, syncDao.pendingCount())
    }

    @Test
    fun `an operation this client can see is invalid never leaves`() = runTest {
        alreadyBackfilled()
        syncDao.outbox.insert(op("TRACK:deezer:" + "x".repeat(600)))
        syncDao.outbox.insert(op("TRACK:deezer:broken", payload = "{not json"))
        syncDao.outbox.insert(op("gone", type = SyncDocuments.PLAYLIST, payload = null))

        runner().run()

        assertTrue(api.requests.single().operations.isEmpty())
        assertEquals(0, syncDao.pendingCount())
    }

    @Test
    fun `data saver holds taste back and lets favorites through`() = runTest {
        alreadyBackfilled()
        paused = true
        syncDao.outbox.insert(op("deezer:1", type = SyncDocuments.TASTE, payload = """{"provider":"deezer","sourceId":"1","trackJson":"{}","playedAtIso":"t"}"""))
        syncDao.outbox.insert(op("TRACK:deezer:1"))

        runner().run()

        assertEquals(listOf(SyncDocuments.FAVORITE), api.requests.single().operations.map { it.entityType })
        assertEquals(1, syncDao.pendingCount())
        assertEquals(SyncDocuments.TASTE, syncDao.pending().single().entityType)
    }

    @Test
    fun `taste goes out under this device, comes back skipped, and other devices' rows are applied`() = runTest {
        alreadyBackfilled()
        val taste = """{"provider":"deezer","sourceId":"1","trackJson":"{}","playedAtIso":"t","playCount":4}"""
        syncDao.outbox.insert(op("deezer:1", type = SyncDocuments.TASTE, payload = taste))
        syncDao.outbox.insert(op("legacy|deezer:1", type = SyncDocuments.TASTE, operation = "DELETE", payload = null))
        api.seed(SyncDocuments.TASTE, "dev-B|deezer:1", doc(taste))

        runner().run()

        val sent = api.requests.single().operations
        assertEquals(setOf("dev-A|deezer:1", "deezer:1"), sent.map { it.entityId }.toSet())
        assertEquals("DELETE", sent.single { it.entityId == "deezer:1" }.operation)
        assertEquals(listOf("dev-B|deezer:1"), applier.applied.map { it.entityId })
    }

    @Test
    fun `not signed in means no request at all`() = runTest {
        syncDao.outbox.insert(op("TRACK:deezer:1"))

        assertEquals(SyncRunner.Outcome.SUCCESS, runner(FakeAccount(AccountState.LocalOnly)).run())

        assertTrue(api.requests.isEmpty())
        assertEquals(1, syncDao.pendingCount())
    }

    @Test
    fun `a server error asks for a retry and the status says so`() = runTest {
        alreadyBackfilled()
        api.failuresLeft = 1
        syncDao.outbox.insert(op("TRACK:deezer:1"))

        assertEquals(SyncRunner.Outcome.RETRY, runner().run())

        assertTrue(events.failed.value)
        assertFalse(events.running.value)
        assertEquals(1, syncDao.pending().single().attemptCount)
        assertNull("no successful run yet, so no account is remembered", prefs().lastAccountId())
    }

    @Test
    fun `the first run for an account journals the whole library, once`() = runTest {
        val runner = runner()

        runner.run()
        runner.run()

        assertEquals(1, journal.journaled)
        val state = syncDao.states["acct-1"]!!
        assertEquals("dev-A", state.deviceId)
        assertNotNull(state.backfilledAtIso)
        assertEquals("acct-1", prefs().lastAccountId())
    }

    @Test
    fun `a second account on the same install keeps the device id`() = runTest {
        syncDao.states["acct-0"] = SyncStateEntity("acct-0", "dev-original", backfilledAtIso = "x")

        runner().run()

        assertEquals("dev-original", syncDao.states["acct-1"]!!.deviceId)
    }

    @Test
    fun `what was applied reaches the rest of the app`() = runTest {
        alreadyBackfilled()
        api.seed(SyncDocuments.FAVORITE, "TRACK:deezer:1", doc("""{"json":"1"}"""))
        api.seed(SyncDocuments.TASTE, "dev-B|deezer:1", doc("""{"provider":"deezer","sourceId":"1","trackJson":"{}","playedAtIso":"t"}"""))
        val received = mutableListOf<SyncApplied>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { events.applied.collect { received += it } }

        runner().run()

        assertEquals(listOf(SyncApplied(playlists = 0, favorites = 1, taste = 1)), received)
        assertFalse(events.running.value)
    }

    @Test
    fun `a round that gets nothing acknowledged ends the run instead of spinning`() = runTest {
        alreadyBackfilled()
        syncDao.outbox.insert(op("TRACK:deezer:1"))
        api.accepts = { false }

        runner().run()

        assertEquals(1, api.requests.size)
        assertEquals(1, syncDao.pending().single().attemptCount)
    }

    @Test
    fun `the cursor only ever moves forward`() = runTest {
        syncDao.states["acct-1"] = SyncStateEntity("acct-1", "dev-A", cursor = 50, backfilledAtIso = "x")

        runner().run()

        assertEquals(50L, syncDao.states["acct-1"]!!.cursor)
        assertEquals(50L, api.requests.first().cursor)
        assertNotNull(syncDao.states["acct-1"]!!.lastSyncedAtIso)
        assertEquals(events.running.first(), false)
    }

    @Test
    fun `a 403 is final - no retry, and the status says failed`() = runTest {
        alreadyBackfilled()
        api.refuseWith = 403
        syncDao.outbox.insert(op("TRACK:deezer:1"))

        assertEquals(SyncRunner.Outcome.FAILURE, runner().run())

        assertTrue(events.failed.value)
        assertFalse(events.running.value)
        assertEquals("kept for a later, permitted run", 1, syncDao.pendingCount())
        assertTrue(logs.any { it.startsWith("run refused") })
    }

    @Test
    fun `every run leaves one summary line with its reason and what moved`() = runTest {
        alreadyBackfilled()
        api.seed(SyncDocuments.FAVORITE, "TRACK:deezer:1", doc("""{"json":"1"}"""))
        syncDao.outbox.insert(op("TRACK:deezer:2"))

        runner().run(SyncReason.REALTIME)

        val line = logs.single { it.startsWith("run ok") }
        assertTrue(line, line.startsWith("run ok reason=REALTIME cursor=0->2 sent=1 acked=1 applied=0/1/0 rounds=1"))
    }
}
