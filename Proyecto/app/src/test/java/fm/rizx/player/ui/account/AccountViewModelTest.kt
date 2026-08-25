package fm.rizx.player.ui.account

import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.data.local.db.InMemorySyncDao
import fm.rizx.player.data.local.store.SyncPrefsStore
import fm.rizx.player.data.sync.FakeAccount
import fm.rizx.player.data.sync.FakeJournal
import fm.rizx.player.data.sync.FakeSyncCoordinator
import fm.rizx.player.data.sync.SyncScheduler
import fm.rizx.player.data.sync.signedIn
import fm.rizx.player.domain.account.AccountRepository
import fm.rizx.player.domain.account.AccountState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Signing in must start the first sync by itself: the owner's report was that after a Google
 * sign-in nothing appeared on the new device until the buried "Sync now" button was found. The
 * scheduler owns that now, so these tests run one alongside the view model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    @get:Rule val tmp = TemporaryFolder()

    /** A sign-in that actually changes the session, the way the real repository does. */
    private class SigningAccount : AccountRepository by FakeAccount(AccountState.LocalOnly) {
        val inner = FakeAccount(AccountState.LocalOnly)
        override val state get() = inner.state
        override suspend fun signInWithGoogle(idToken: String, nonce: String) { inner.state.value = signedIn("acct-1") }
        override suspend fun signOut() = inner.signOut()
    }

    private fun TestScope.vm(account: AccountRepository, sync: FakeSyncCoordinator): AccountViewModel {
        val scheduler = SyncScheduler(account, sync, InMemorySyncDao(), SyncPrefsStore(File(tmp.root, "p.json"), io = Dispatchers.Unconfined), FakeJournal())
        scheduler.start(backgroundScope)
        return AccountViewModel(account, sync, scheduler)
    }

    @Test
    fun `google sign-in triggers the first sync automatically`() = runTest(mainDispatcher.dispatcher.scheduler) {
        val sync = FakeSyncCoordinator()
        val vm = vm(SigningAccount(), sync)
        vm.signInGoogle("token", "nonce")
        mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, sync.syncNowCalls)
        assertEquals("and the periodic backstop with it", 1, sync.periodicCalls)
        assertNotNull(vm.ui.value.message)
    }

    @Test
    fun `a failed sign-in does not sync`() = runTest(mainDispatcher.dispatcher.scheduler) {
        val sync = FakeSyncCoordinator()
        val failing = object : AccountRepository by FakeAccount(AccountState.LocalOnly) {
            override suspend fun signInWithGoogle(idToken: String, nonce: String) = error("boom")
        }
        val vm = vm(failing, sync)
        vm.signInGoogle("token", "nonce")
        mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, sync.syncNowCalls)
        assertNotNull(vm.ui.value.error)
    }

    @Test
    fun `sign out stops sync and keeps the local library message`() = runTest(mainDispatcher.dispatcher.scheduler) {
        val sync = FakeSyncCoordinator()
        val vm = vm(SigningAccount(), sync)
        vm.signOut()
        mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertTrue(sync.stopCalls >= 1)
        assertEquals(0, sync.syncNowCalls)
        assertNotNull(vm.ui.value.message)
    }
}
