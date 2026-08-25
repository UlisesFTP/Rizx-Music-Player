package fm.rizx.player.data.sync

import fm.rizx.player.data.local.db.InMemorySyncDao
import fm.rizx.player.data.local.db.SyncOutboxEntity
import fm.rizx.player.data.local.db.SyncStateEntity
import fm.rizx.player.data.local.store.SyncPrefsStore
import fm.rizx.player.domain.account.AccountState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * When sync runs, and when it must not. Virtual time: the debounce and the quiet period are real durations.
 *
 * The scheduler is driven through `testScheduler` directly: `TestScope.advanceUntilIdle()` deliberately
 * skips `backgroundScope` work, which is exactly where the scheduler's collectors live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val account = FakeAccount(AccountState.LocalOnly)
    private val sync = FakeSyncCoordinator()
    private val syncDao = InMemorySyncDao()
    private val journal = FakeJournal()
    private val now = Instant.parse("2026-08-21T12:00:00Z")
    private var clock = now

    private fun prefs() = SyncPrefsStore(File(tmp.root, "sync_prefs.json"), io = Dispatchers.Unconfined)

    private fun scheduler() = SyncScheduler(
        account, sync, syncDao, prefs(), journal,
        debounce = Duration.ofSeconds(20), foregroundQuiet = Duration.ofMinutes(5), now = { clock }, newDeviceId = { "dev" },
    )

    private fun pendingEdit(id: String) = SyncOutboxEntity(id, "FAVORITE", "TRACK:deezer:$id", "UPSERT", "{}", "t$id")

    /**
     * The app scope the scheduler lives in, on the test's clock. Not `backgroundScope`: in coroutines-test
     * 1.8 its coroutines are not run by `advanceUntilIdle`, so nothing would happen until the test ended.
     */
    private val scopes = mutableListOf<CoroutineScope>()
    private fun TestScope.appScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    @Test
    fun `a stored session syncs on start and schedules the backstop`() = runTest {
        account.state.value = signedIn("acct-1")

        scheduler().start(appScope())
        testScheduler.advanceUntilIdle()

        assertEquals(1, sync.syncNowCalls)
        assertEquals(1, sync.periodicCalls)
    }

    @Test
    fun `local edits push once the burst settles`() = runTest {
        account.state.value = signedIn("acct-1")
        scheduler().start(appScope())
        testScheduler.advanceUntilIdle()

        syncDao.enqueue(pendingEdit("1"))
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        syncDao.enqueue(pendingEdit("2"))
        testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        syncDao.enqueue(pendingEdit("3"))
        testScheduler.advanceTimeBy(10_000); testScheduler.runCurrent()
        assertEquals("still inside the debounce", 1, sync.syncNowCalls)
        testScheduler.advanceTimeBy(20_001); testScheduler.runCurrent()

        assertEquals("one push for the whole burst", 2, sync.syncNowCalls)
    }

    @Test
    fun `signing in later starts sync, signing out stops it`() = runTest {
        scheduler().start(appScope())
        testScheduler.advanceUntilIdle()
        assertEquals(0, sync.syncNowCalls)

        account.state.value = signedIn("acct-1")
        testScheduler.advanceUntilIdle()
        assertEquals(1, sync.syncNowCalls)

        val stopsBefore = sync.stopCalls
        account.state.value = AccountState.LocalOnly
        testScheduler.advanceUntilIdle()
        assertEquals(stopsBefore + 1, sync.stopCalls)
    }

    @Test
    fun `a guest session never syncs`() = runTest {
        account.state.value = AccountState.Guest(fm.rizx.player.domain.account.AccountProfile("g", null, isAnonymous = true))
        scheduler().start(appScope())
        testScheduler.advanceUntilIdle()

        assertEquals(0, sync.syncNowCalls)
    }

    @Test
    fun `a different account on a device with a library waits for the merge choice`() = runTest {
        prefs().setLastAccountId("acct-0")
        journal.hasLibrary = true
        val scheduler = scheduler()
        scheduler.start(appScope())
        account.state.value = signedIn("acct-1")
        testScheduler.advanceUntilIdle()

        assertEquals(SyncScheduler.MergeRequired("acct-0", "acct-1"), scheduler.mergeRequired.value)
        assertEquals(0, sync.syncNowCalls)

        syncDao.enqueue(pendingEdit("1"))
        testScheduler.advanceTimeBy(60_000); testScheduler.runCurrent()
        assertEquals("edits wait too", 0, sync.syncNowCalls)
    }

    @Test
    fun `the same account again, or an empty device, does not ask`() = runTest {
        prefs().setLastAccountId("acct-1")
        journal.hasLibrary = true
        val scheduler = scheduler()
        scheduler.start(appScope())
        account.state.value = signedIn("acct-1")
        testScheduler.advanceUntilIdle()
        assertNull(scheduler.mergeRequired.value)
        assertEquals(1, sync.syncNowCalls)

        prefs().setLastAccountId("acct-0")
        journal.hasLibrary = false
        account.state.value = signedIn("acct-2")
        testScheduler.advanceUntilIdle()
        assertNull(scheduler.mergeRequired.value)
        assertEquals(2, sync.syncNowCalls)
    }

    @Test
    fun `merging asks for the backfill again and syncs`() = runTest {
        prefs().setLastAccountId("acct-0")
        journal.hasLibrary = true
        syncDao.states["acct-1"] = SyncStateEntity("acct-1", "dev", cursor = 9, backfilledAtIso = "done")
        val scheduler = scheduler()
        scheduler.start(appScope())
        account.state.value = signedIn("acct-1")
        testScheduler.advanceUntilIdle()

        scheduler.resolveMerge(SyncScheduler.MergeChoice.UNION)

        assertNull(scheduler.mergeRequired.value)
        assertNull(syncDao.states["acct-1"]!!.backfilledAtIso)
        assertEquals(9L, syncDao.states["acct-1"]!!.cursor)
        assertEquals(1, sync.syncNowCalls)
        assertEquals(1, sync.periodicCalls)
    }

    @Test
    fun `taking the cloud copy wipes the library and pulls from the start`() = runTest {
        prefs().setLastAccountId("acct-0")
        journal.hasLibrary = true
        syncDao.enqueue(pendingEdit("1"))
        val scheduler = scheduler()
        scheduler.start(appScope())
        account.state.value = signedIn("acct-1")
        testScheduler.advanceUntilIdle()

        scheduler.resolveMerge(SyncScheduler.MergeChoice.CLOUD)

        assertEquals(1, journal.wiped)
        assertEquals(0, syncDao.pendingCount())
        val state = syncDao.states["acct-1"]!!
        assertEquals(0L, state.cursor)
        assertNotNull("nothing local to push, so the backfill is already done", state.backfilledAtIso)
        assertEquals(1, sync.syncNowCalls)
    }

    @Test
    fun `staying signed out signs out and stops`() = runTest {
        prefs().setLastAccountId("acct-0")
        journal.hasLibrary = true
        val scheduler = scheduler()
        scheduler.start(appScope())
        account.state.value = signedIn("acct-1")
        testScheduler.advanceUntilIdle()

        scheduler.resolveMerge(SyncScheduler.MergeChoice.KEEP_LOCAL)
        testScheduler.advanceUntilIdle()

        assertEquals(1, account.signOuts)
        assertNull(scheduler.mergeRequired.value)
        assertEquals(1, sync.stopCalls)
        assertEquals(0, sync.syncNowCalls)
    }

    @Test
    fun `coming to the foreground syncs after a quiet spell or with edits waiting, not otherwise`() = runTest {
        account.state.value = signedIn("acct-1")
        val scheduler = scheduler()
        scheduler.start(appScope())
        testScheduler.advanceUntilIdle()
        assertEquals(1, sync.syncNowCalls)

        syncDao.states["acct-1"] = SyncStateEntity("acct-1", "dev", lastSyncedAtIso = now.minus(Duration.ofMinutes(1)).toString())
        scheduler.onForeground()
        testScheduler.advanceUntilIdle()
        assertEquals("just synced, nothing pending", 1, sync.syncNowCalls)

        syncDao.states["acct-1"] = SyncStateEntity("acct-1", "dev", lastSyncedAtIso = now.minus(Duration.ofMinutes(6)).toString())
        scheduler.onForeground()
        testScheduler.advanceUntilIdle()
        assertEquals("quiet for six minutes", 2, sync.syncNowCalls)

        syncDao.states["acct-1"] = SyncStateEntity("acct-1", "dev", lastSyncedAtIso = now.toString())
        syncDao.enqueue(pendingEdit("1"))
        scheduler.onForeground()
        // runCurrent, not advanceUntilIdle: the latter would also run the edit's 20 s debounce.
        testScheduler.runCurrent()
        assertEquals("something waited offline", 3, sync.syncNowCalls)
    }
}
