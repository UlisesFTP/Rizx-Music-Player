package fm.rizx.player.data.sync

import fm.rizx.player.data.local.db.SyncDao
import fm.rizx.player.data.local.db.SyncStateEntity
import fm.rizx.player.data.local.store.SyncPrefsStore
import fm.rizx.player.domain.account.AccountRepository
import fm.rizx.player.domain.account.AccountState
import fm.rizx.player.domain.sync.SyncCoordinator
import fm.rizx.player.domain.sync.SyncReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Decides *when* sync runs, so the user never has to.
 *
 * Five moments, in the order a day brings them: the session becomes signed in (which includes opening
 * the app with a stored session), a local edit settles ([debounce]), another device pushes something
 * while this one is on screen (the invalidation channel, answered inline within [invalidationSettle]),
 * the app comes back to the foreground after a while, and — for a phone that sat still — the periodic
 * backstop the coordinator schedules. The channel is a shortcut, never the guarantee: it is open only
 * while a screen is visible, and the other four moments cover everything it misses.
 *
 * It is also the gate spec 021 asks for: a device that last synced with a different account and still
 * holds a library does not upload it. It waits in [mergeRequired] for the user to choose.
 */
class SyncScheduler(
    private val account: AccountRepository,
    private val sync: SyncCoordinator,
    private val syncDao: SyncDao,
    private val prefs: SyncPrefsStore,
    private val journal: LibraryJournal,
    private val invalidations: SyncInvalidations = NoInvalidations,
    private val debounce: Duration = Duration.ofSeconds(20),
    private val foregroundQuiet: Duration = Duration.ofMinutes(5),
    private val invalidationSettle: Duration = Duration.ofSeconds(1),
    /** A foreground this soon after any other request is the same moment, not a new one. */
    private val foregroundCooldown: Duration = Duration.ofSeconds(30),
    private val now: () -> Instant = { Instant.now() },
    private val newDeviceId: () -> String = { UUID.randomUUID().toString() },
) {
    data class MergeRequired(val previousAccountId: String, val accountId: String)

    enum class MergeChoice { UNION, CLOUD, KEEP_LOCAL }

    private val _mergeRequired = MutableStateFlow<MergeRequired?>(null)
    val mergeRequired: StateFlow<MergeRequired?> = _mergeRequired.asStateFlow()

    private var scope: CoroutineScope? = null

    @Volatile
    private var accountId: String? = null

    @Volatile
    private var foreground = false

    @Volatile
    private var lastRequestAt: Instant? = null

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        scope.launch {
            account.state.map { signedInId(it) }.distinctUntilChanged().collect { onAccount(it) }
        }
        scope.launch {
            syncDao.observePendingCount().filter { it > 0 }.debounce(debounce.toMillis())
                .collect { if (canSync()) request(SyncReason.LOCAL_EDIT) }
        }
        scope.launch {
            // Filtered before the debounce so a burst of own echoes cannot delay a real nudge.
            invalidations.events.filter { relevant(it) }.debounce(invalidationSettle.toMillis())
                .collect {
                    if (canSync()) {
                        lastRequestAt = now()
                        sync.syncInline(SyncReason.REALTIME)
                    }
                }
        }
    }

    /** The app is on screen again: catch up if it has been a while, or if something waited offline. */
    fun onForeground() {
        foreground = true
        listen()
        val id = accountId ?: return
        if (!canSync()) return
        scope?.launch {
            val last = syncDao.state(id)?.lastSyncedAtIso?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val quiet = last == null || Duration.between(last, now()) >= foregroundQuiet
            // A run was asked for moments ago — the sign-in run, or a cold start's replayed foreground —
            // and has not had time to stamp `lastSyncedAtIso`. It is about to do this work; asking again
            // only queued a second, empty run behind it.
            val requested = lastRequestAt?.let { Duration.between(it, now()) < foregroundCooldown } == true
            if ((quiet && !requested) || syncDao.pendingCount() > 0) request(SyncReason.FOREGROUND)
        }
    }

    private fun request(reason: SyncReason) {
        lastRequestAt = now()
        sync.syncNow(reason)
    }

    /** No screen, no channel: the scheduled triggers carry sync from here. */
    fun onBackground() {
        foreground = false
        invalidations.disconnect()
    }

    suspend fun resolveMerge(choice: MergeChoice) {
        val merge = _mergeRequired.value ?: return
        when (choice) {
            MergeChoice.UNION -> {
                // The backfill is what pushes the local library; clearing its stamp asks for it again.
                syncDao.state(merge.accountId)?.let { syncDao.saveState(it.copy(backfilledAtIso = null)) }
                _mergeRequired.value = null
                request(SyncReason.MERGE)
                sync.schedulePeriodic()
                listen()
            }
            MergeChoice.CLOUD -> {
                journal.wipeLocalLibrary()
                syncDao.clearOutbox()
                // Nothing local to push, so the backfill is marked done and the cursor rewound to pull all.
                syncDao.saveState(
                    SyncStateEntity(
                        accountId = merge.accountId,
                        deviceId = syncDao.anyDeviceId() ?: newDeviceId(),
                        cursor = 0,
                        backfilledAtIso = now().toString(),
                    ),
                )
                _mergeRequired.value = null
                request(SyncReason.MERGE)
                sync.schedulePeriodic()
                listen()
            }
            MergeChoice.KEEP_LOCAL -> {
                _mergeRequired.value = null
                account.signOut()
            }
        }
    }

    private suspend fun onAccount(id: String?) {
        accountId = id
        if (id == null) {
            _mergeRequired.value = null
            invalidations.disconnect()
            sync.stop()
            return
        }
        val previous = prefs.lastAccountId()
        if (previous != null && previous != id && journal.hasLocalLibrary()) {
            _mergeRequired.value = MergeRequired(previous, id)
            invalidations.disconnect()
            return
        }
        _mergeRequired.value = null
        request(SyncReason.SIGN_IN)
        sync.schedulePeriodic()
        listen()
    }

    /** Opens the channel when there is someone to listen for and a screen to listen on. */
    private fun listen() {
        val id = accountId ?: return
        if (foreground && canSync()) invalidations.connect(id)
    }

    private suspend fun relevant(invalidation: SyncInvalidation): Boolean {
        val id = accountId ?: return false
        val state = syncDao.state(id)
        return invalidation.isRelevant(ownDeviceId = state?.deviceId, localCursor = state?.cursor ?: 0L)
    }

    private fun canSync(): Boolean = accountId != null && _mergeRequired.value == null

    private fun signedInId(state: AccountState): String? =
        (state as? AccountState.SignedIn)?.takeUnless { it.profile.isAnonymous }?.profile?.id
}
