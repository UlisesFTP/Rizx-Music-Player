package fm.rizx.player.domain.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Where a share link that arrived from *outside* — a scanned QR, a tapped link — waits for the Library.
 *
 * The Activity receives the intent; the Library screen is the thing that imports. They can't see each
 * other, and the screen may not even exist yet when the link lands (cold start from a QR), so the link
 * waits here until the screen collects it. One slot, latest wins: two links in a row mean the user
 * changed their mind, not that they want two imports.
 */
class ShareLinkInbox {
    private val _pending = MutableStateFlow<String?>(null)

    /** The HTTPS share URL waiting to be imported, or null. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun offer(shareUrl: String) {
        _pending.value = shareUrl
    }

    /** Takes the pending link, leaving the inbox empty so nothing imports it twice. */
    fun consume(): String? = _pending.getAndUpdate { null }
}
