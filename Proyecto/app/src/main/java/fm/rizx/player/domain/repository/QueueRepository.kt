package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.PlaybackQueue
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueItem
import fm.rizx.player.domain.model.QueueItemStatus
import fm.rizx.player.domain.model.RepeatMode
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * The single source of truth for playback order. UI and use-cases observe [state] and issue
 * operations here; nothing else mutates the queue directly. Mutations are synchronous — the
 * in-memory store updates instantly and [state] drives the UI reactively.
 *
 * Invariants (see `docs/specs/006-queue-spec.md`):
 * - each added [Track] becomes a [fm.rizx.player.domain.model.QueueItem] with a fresh per-insertion
 *   id — the same track may appear multiple times with distinct ids;
 * - `currentIndex == -1` iff the queue is empty; otherwise it is always valid;
 * - add/remove/reorder keep the *same* item current when it survives (tracked by id).
 */
interface QueueRepository {

    /** The observable queue state. */
    val state: StateFlow<PlaybackQueue>

    /** Appends [tracks] to the end. Selecting index 0 if the queue was empty. */
    fun addToQueue(tracks: List<Track>)

    /** Inserts [tracks] immediately after the current item (or at the start if empty). */
    fun addNext(tracks: List<Track>)

    /** Inserts [tracks] at [index] (clamped to `0..size`), keeping the current item current. */
    fun addAt(index: Int, tracks: List<Track>)

    /** Removes items whose id is in [ids]. */
    fun removeByIds(ids: Collection<String>)

    /** Removes items at the given [indices]. */
    fun removeByIndices(indices: Collection<Int>)

    /** Removes every item, resetting the cursor (repeat mode is preserved). */
    fun clearQueue()

    /** Moves the item at [fromIndex] to [toIndex]; the current item stays current. */
    fun reorder(fromIndex: Int, toIndex: Int)

    /** Updates the per-item resolution [status]/[error] of the item with [id]. */
    fun updateItemState(id: String, status: QueueItemStatus, error: String? = null)

    /** Bounded manual advance. Returns `true` if the cursor moved. */
    fun goToNext(): Boolean

    /** Bounded manual retreat. Returns `true` if the cursor moved. */
    fun goToPrevious(): Boolean

    /** Jumps to [index] if valid. Returns `true` if the cursor moved. */
    fun goToIndex(index: Int): Boolean

    /** Jumps to the item with [id] if present. Returns `true` if the cursor moved. */
    fun goToId(id: String): Boolean

    /**
     * Automatic, repeat-aware advance for when a track finishes on its own. Returns `true` if a
     * track is now current and playback should continue, `false` if the queue ended (stop).
     */
    fun advanceOnTrackEnd(): Boolean

    /** Sets the [RepeatMode] governing [advanceOnTrackEnd]. */
    fun setRepeatMode(mode: RepeatMode)

    /**
     * Turns shuffle on or off, reordering [PlaybackQueue.items] in place.
     *
     * The song playing never changes: enabling moves it to the front and shuffles the rest, so
     * "shuffle" means "play the remainder in random order". Disabling restores the original order via
     * [PlaybackQueue.unshuffledIds] and keeps the cursor on the same item.
     *
     * On an empty queue this only records the preference — whatever is played next starts shuffled.
     */
    fun setShuffle(enabled: Boolean)

    /**
     * **Replaces** the whole queue with [tracks] (fresh per-insertion ids), positions the cursor at
     * [startIndex] (clamped into range), and records the [context] the queue was started from — the
     * primitive behind contextual playback (an album/playlist/artist/liked list, or a radio seed).
     * Preserves the current [RepeatMode] and shuffle preference — with shuffle on, the new queue starts
     * shuffled from [startIndex]'s track. No-op when [tracks] is empty. Unlike [addToQueue], this sets
     * the [context]; a later [addToQueue] (e.g. a radio refill) leaves the context intact.
     */
    fun setQueue(tracks: List<Track>, startIndex: Int, context: QueueContext)

    /**
     * Replaces the whole queue in a single emission — used to **restore a persisted session** at
     * startup so playback can resume on the last track. [currentIndex] is clamped into [items]; the
     * cursor, [repeatMode] and shuffle state are honored. No-op when [items] is empty. Restored items
     * should carry a fresh [QueueItemStatus.IDLE] status (streams are re-resolved just-in-time, never
     * from disk).
     */
    fun restore(
        items: List<QueueItem>,
        currentIndex: Int,
        repeatMode: RepeatMode,
        context: QueueContext = QueueContext(),
        shuffleOn: Boolean = false,
        unshuffledIds: List<String>? = null,
    )
}
