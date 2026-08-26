package fm.rizx.player.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fm.rizx.player.domain.sync.SyncReason

/**
 * WorkManager's handle on a sync run. The run itself is [SyncRunner]; this only maps its outcome onto
 * WorkManager's retry policy. Dependencies come through an entry point so no worker factory is needed.
 */
class RizxSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun syncRunner(): SyncRunner
    }

    override suspend fun doWork(): Result {
        val runner = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).syncRunner()
        val reason = inputData.getString(KEY_REASON)?.let { name -> SyncReason.entries.firstOrNull { it.name == name } }
            ?: SyncReason.MANUAL
        return when (runner.run(reason)) {
            SyncRunner.Outcome.SUCCESS -> Result.success()
            SyncRunner.Outcome.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            // The server said this account may not sync: no amount of retrying changes that.
            SyncRunner.Outcome.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val KEY_REASON = "reason"
        private const val MAX_ATTEMPTS = 5
    }
}
