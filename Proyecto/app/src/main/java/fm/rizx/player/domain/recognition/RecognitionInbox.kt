package fm.rizx.player.domain.recognition

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * A request to identify a song that arrived from outside the app — the microphone button of a home
 * screen widget. One slot, like [fm.rizx.player.domain.share.ShareLinkInbox]: the activity drops the
 * request in, the app navigates to the recognition screen when it sees one, and the screen takes it out
 * and starts listening. Counted rather than boolean so a second tap while the screen is already open is
 * a new request, not a stale one.
 */
class RecognitionInbox {
    private val _pending = MutableStateFlow(0L)

    /** Non-zero while a request waits; each request is a distinct value. */
    val pending: StateFlow<Long> = _pending.asStateFlow()

    fun offer() {
        _pending.update { it + 1 }
    }

    /** Takes the request, if any, leaving the inbox empty so nothing acts on it twice. */
    fun consume(): Boolean = _pending.getAndUpdate { 0L } != 0L
}
