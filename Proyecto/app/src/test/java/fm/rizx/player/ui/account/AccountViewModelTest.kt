package fm.rizx.player.ui.account

import fm.rizx.player.MainDispatcherRule
import fm.rizx.player.domain.account.AccountProfile
import fm.rizx.player.domain.account.AccountRepository
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.domain.sync.SyncCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * Signing in must start the first sync by itself: the owner's report was that after a Google
 * sign-in nothing appeared on the new device until the buried "Sync now" button was found.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private class FakeAccount : AccountRepository {
        override val state: StateFlow<AccountState> = MutableStateFlow(AccountState.LocalOnly)
        override val configured: Boolean = true
        var otpRequested: String? = null
        override suspend fun requestEmailOtp(email: String) { otpRequested = email }
        override suspend fun verifyEmailOtp(email: String, code: String) = Unit
        override suspend fun signInWithGoogle(idToken: String, nonce: String) = Unit
        override suspend fun ensureGuestSession(captchaToken: String?) = AccountProfile("g", null, true)
        override suspend fun accessToken(): String? = null
        override suspend fun signOut() = Unit
        override suspend fun deleteCloudAccount() = Unit
    }

    private class FakeSync : SyncCoordinator {
        var syncNowCalls = 0
        var stopCalls = 0
        override val pendingCount = flowOf(0)
        override fun syncNow() { syncNowCalls++ }
        override fun stop() { stopCalls++ }
    }

    @Test
    fun `google sign-in triggers the first sync automatically`() = runTest(mainDispatcher.dispatcher.scheduler) {
        val sync = FakeSync()
        val vm = AccountViewModel(FakeAccount(), sync)
        vm.signInGoogle("token", "nonce")
        mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, sync.syncNowCalls)
        assertNotNull(vm.ui.value.message)
    }

    @Test
    fun `a failed sign-in does not sync`() = runTest(mainDispatcher.dispatcher.scheduler) {
        val sync = FakeSync()
        val failing = object : AccountRepository by FakeAccount() {
            override suspend fun signInWithGoogle(idToken: String, nonce: String) = error("boom")
        }
        val vm = AccountViewModel(failing, sync)
        vm.signInGoogle("token", "nonce")
        mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, sync.syncNowCalls)
        assertNotNull(vm.ui.value.error)
    }

    @Test
    fun `sign out stops sync and keeps the local library message`() = runTest(mainDispatcher.dispatcher.scheduler) {
        val sync = FakeSync()
        val vm = AccountViewModel(FakeAccount(), sync)
        vm.signOut()
        mainDispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, sync.stopCalls)
        assertEquals(0, sync.syncNowCalls)
    }
}
