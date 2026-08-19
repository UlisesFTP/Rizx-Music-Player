package fm.rizx.player.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.rizx.player.data.local.db.SyncDao
import fm.rizx.player.domain.sync.SyncCoordinator
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerSyncCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    syncDao: SyncDao,
) : SyncCoordinator {
    private val work = WorkManager.getInstance(context)
    override val pendingCount = syncDao.observePendingCount()

    override fun syncNow() {
        val request = OneTimeWorkRequestBuilder<RizxSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun stop() {
        work.cancelUniqueWork(WORK_NAME)
    }

    private companion object { const val WORK_NAME = "rizx-account-sync" }
}
