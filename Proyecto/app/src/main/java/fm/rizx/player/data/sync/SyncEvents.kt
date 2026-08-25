package fm.rizx.player.data.sync

import fm.rizx.player.domain.sync.SyncApplied
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a sync run tells the rest of the app while it happens — the worker runs in WorkManager's process
 * space with no caller to return to, so this is how the Home learns that new favorites arrived and the
 * account screen that a run is under way.
 */
@Singleton
class SyncEvents @Inject constructor() {
    private val _applied = MutableSharedFlow<SyncApplied>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val applied: SharedFlow<SyncApplied> = _applied.asSharedFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    fun applied(result: SyncApplied) {
        if (result.total > 0) _applied.tryEmit(result)
    }

    fun started() {
        _running.value = true
    }

    fun finished(failed: Boolean) {
        _running.value = false
        _failed.value = failed
    }
}
