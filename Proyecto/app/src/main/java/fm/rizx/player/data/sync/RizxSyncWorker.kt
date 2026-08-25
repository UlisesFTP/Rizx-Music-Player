package fm.rizx.player.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

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
        return when (runner.run()) {
            SyncRunner.Outcome.SUCCESS -> Result.success()
            SyncRunner.Outcome.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object { const val MAX_ATTEMPTS = 5 }
}
