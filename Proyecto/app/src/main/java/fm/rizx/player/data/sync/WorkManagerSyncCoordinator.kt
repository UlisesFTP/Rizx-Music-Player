package fm.rizx.player.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.rizx.player.data.local.db.SyncDao
import fm.rizx.player.domain.account.AccountRepository
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.domain.sync.SyncApplied
import fm.rizx.player.domain.sync.SyncCoordinator
import fm.rizx.player.domain.sync.SyncReason
import fm.rizx.player.domain.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerSyncCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    syncDao: SyncDao,
    account: AccountRepository,
    events: SyncEvents,
    private val runner: SyncRunner,
) : SyncCoordinator {
    private val work = WorkManager.getInstance(context)
    override val pendingCount = syncDao.observePendingCount()
    override val applied: Flow<SyncApplied> = events.applied

    @OptIn(ExperimentalCoroutinesApi::class)
    override val status: Flow<SyncStatus> = combine(
        events.running,
        events.failed,
        pendingCount,
        account.state.flatMapLatest { state ->
            (state as? AccountState.SignedIn)?.profile?.id?.let { syncDao.observeState(it) } ?: flowOf(null)
        },
    ) { running, failed, pending, state ->
        SyncStatus(running = running, lastSyncedAtIso = state?.lastSyncedAtIso, pending = pending, failed = failed)
    }

    /**
     * `APPEND_OR_REPLACE`, not `KEEP`: a request that arrives while a run is under way is queued behind
     * it rather than dropped, so an edit made mid-run still goes up. (The scheduler's debounce is what
     * keeps the chain short.)
     */
    override fun syncNow(reason: SyncReason) {
        val request = OneTimeWorkRequestBuilder<RizxSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(RizxSyncWorker.KEY_REASON to reason.name))
            .build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Straight to the runner, no WorkManager between: an invalidation from another device deserves an
     * answer in seconds, not whenever the scheduler gets around to it. The runner's own gate serializes
     * this with a worker that may already be running. A run that needs a retry is handed to
     * WorkManager, which knows how to wait for a network and back off.
     */
    override suspend fun syncInline(reason: SyncReason) {
        if (runner.run(reason) == SyncRunner.Outcome.RETRY) syncNow(reason)
    }

    /** The backstop for a phone that sat in a drawer: every six hours, on any connection. */
    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<RizxSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(RizxSyncWorker.KEY_REASON to SyncReason.PERIODIC.name))
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun stop() {
        work.cancelUniqueWork(WORK_NAME)
        work.cancelUniqueWork(PERIODIC_NAME)
    }

    private companion object {
        const val WORK_NAME = "rizx-account-sync"
        const val PERIODIC_NAME = "rizx-account-sync-periodic"
        const val PERIOD_HOURS = 6L
    }
}
