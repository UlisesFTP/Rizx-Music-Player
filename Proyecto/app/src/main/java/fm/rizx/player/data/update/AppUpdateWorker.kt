package fm.rizx.player.data.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.domain.update.AppUpdateFailure
import fm.rizx.player.domain.update.AppUpdateState
import java.util.concurrent.TimeUnit

/**
 * WorkManager's handle on an update check: ask the coordinator, and if a new version turned up that
 * has not been announced yet, post the notification. Dependencies come through an entry point, like
 * the sync worker's, so no worker factory is needed.
 */
class AppUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun coordinator(): AppUpdateCoordinator
        fun notifier(): AppUpdateNotifier
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        val coordinator = deps.coordinator()
        val update = coordinator.check(force = false)
        if (update != null && coordinator.claimNotification(update)) deps.notifier().notify(update)
        val failed = coordinator.state.value as? AppUpdateState.Failed
        return when {
            failed == null -> Result.success()
            failed.reason == AppUpdateFailure.NETWORK && runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * When the check runs: once shortly after every app start (the coordinator's own 12 h window keeps
 * that from becoming a request per launch) and once a day in the background on any connection, so a
 * phone that stays in a pocket still hears about a release.
 */
class AppUpdateScheduler(context: Context) {
    private val work = WorkManager.getInstance(context)

    fun onAppStart() {
        val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val now = OneTimeWorkRequestBuilder<AppUpdateWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        work.enqueueUniqueWork(START_NAME, ExistingWorkPolicy.KEEP, now)
        val daily = PeriodicWorkRequestBuilder<AppUpdateWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, daily)
    }

    private companion object {
        const val START_NAME = "rizx-app-update-check"
        const val PERIODIC_NAME = "rizx-app-update-check-periodic"
        const val PERIOD_HOURS = 24L
    }
}
