package fm.rizx.player.domain.sync

import kotlinx.coroutines.flow.Flow

interface SyncCoordinator {
    val pendingCount: Flow<Int>
    fun syncNow()
    fun stop()
}
