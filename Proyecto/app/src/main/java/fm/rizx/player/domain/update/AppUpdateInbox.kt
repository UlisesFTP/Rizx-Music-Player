package fm.rizx.player.domain.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * A request to open the update dialog that arrived from outside Compose — the tap on the "new
 * version" notification. Same shape as the recognition inbox: a counter the UI watches, consumed by
 * the screen that acts on it, so a tap while the app is closed and a tap while it is open both land
 * on the same dialog.
 */
class AppUpdateInbox {
    private val _pending = MutableStateFlow(0L)

    val pending: StateFlow<Long> = _pending.asStateFlow()

    fun offer() {
        _pending.update { it + 1 }
    }

    /** True when there was a request to act on; resets it. */
    fun consume(): Boolean = _pending.getAndUpdate { 0L } != 0L
}
