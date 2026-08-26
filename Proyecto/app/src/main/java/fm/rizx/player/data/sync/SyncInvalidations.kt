package fm.rizx.player.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A nudge from the account's private channel: some device — possibly this one — just pushed, and the
 * cloud now stands at [revision]. It is a hint to pull, never data: the run that follows reads by
 * cursor exactly as it would have anyway.
 */
data class SyncInvalidation(val revision: Long, val deviceId: String?) {
    /**
     * Worth a run when it is news: past what this device already pulled, and not the echo of what this
     * device itself just wrote (the server nudges everyone, the writer included).
     */
    fun isRelevant(ownDeviceId: String?, localCursor: Long): Boolean =
        revision > localCursor && (deviceId == null || deviceId != ownDeviceId)
}

/**
 * The account's invalidation channel. Connected only while the app is on screen: a phone in a pocket
 * keeps syncing through the scheduler's own triggers, and the channel is what makes a visible screen
 * catch up in seconds instead of minutes.
 */
interface SyncInvalidations {
    val events: Flow<SyncInvalidation>

    /** Listens on [userId]'s channel. Calling again for the same user is a no-op; another user re-targets. */
    fun connect(userId: String)

    fun disconnect()
}

/** No channel: an unconfigured build, or a test that only counts sync runs. */
object NoInvalidations : SyncInvalidations {
    override val events: Flow<SyncInvalidation> = emptyFlow()
    override fun connect(userId: String) = Unit
    override fun disconnect() = Unit
}
