package fm.rizx.player.domain.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** What one sync run wrote into the local library from the cloud. */
data class SyncApplied(
    val playlists: Int = 0,
    val favorites: Int = 0,
    val taste: Int = 0,
) {
    val total: Int get() = playlists + favorites + taste

    /** Whether the personalized feed's inputs moved — favorites and listening history feed "For you". */
    val touchesTaste: Boolean get() = favorites + taste > 0
}

/** Where sync stands, for the account screen. */
data class SyncStatus(
    val running: Boolean = false,
    val lastSyncedAtIso: String? = null,
    val pending: Int = 0,
    /** The last run gave up (network or server); WorkManager retries on its own. */
    val failed: Boolean = false,
)

/**
 * Schedules cloud sync. Runs are automatic while signed in; [syncNow] is the explicit request on top.
 *
 * [applied] and [status] default to empty so a fake that only needs to record [syncNow] stays small.
 */
interface SyncCoordinator {
    val pendingCount: Flow<Int>

    /** Emits after a run applied at least one remote change. */
    val applied: Flow<SyncApplied> get() = emptyFlow()

    val status: Flow<SyncStatus> get() = flowOf(SyncStatus())

    fun syncNow()

    /** The periodic backstop. Idempotent: scheduling twice keeps the first. */
    fun schedulePeriodic() = Unit

    /** Cancels pending and periodic work. The outbox is kept. */
    fun stop()
}

/** For construction sites that do not sync (tests, previews). */
object NoSyncCoordinator : SyncCoordinator {
    override val pendingCount: Flow<Int> = flowOf(0)
    override fun syncNow() = Unit
    override fun stop() = Unit
}
