package fm.rizx.player.data.repository

import fm.rizx.player.domain.model.PlaybackQueue
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueItem
import fm.rizx.player.domain.model.QueueItemStatus
import fm.rizx.player.domain.model.RepeatMode
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.QueueRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

/**
 * In-memory [QueueRepository] backed by a [MutableStateFlow]. Per-insertion [QueueItem.id]s and
 * [QueueItem.addedAtIso] timestamps come from the injectable [newId]/[nowIso] functions (real
 * `UUID`/`Instant` by default, deterministic stubs in tests). All mutations preserve the invariants
 * documented on [QueueRepository]. Intended as a process-wide singleton (see `QueueModule`).
 */
class InMemoryQueueRepository(
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowIso: () -> String = { Instant.now().toString() },
) : QueueRepository {

    private val _state = MutableStateFlow(PlaybackQueue())
    override val state: StateFlow<PlaybackQueue> = _state.asStateFlow()

    private fun Track.toQueueItem() = QueueItem(id = newId(), track = this, addedAtIso = nowIso())

    override fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val added = tracks.map { it.toQueueItem() }
        _state.update { q ->
            q.copy(
                items = q.items + added,
                currentIndex = if (q.currentIndex == -1) 0 else q.currentIndex,
            )
        }
    }

    override fun addNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val added = tracks.map { it.toQueueItem() }
        _state.update { q ->
            val at = if (q.items.isEmpty()) 0 else (q.currentIndex + 1).coerceIn(0, q.items.size)
            insert(q, at, added)
        }
    }

    override fun addAt(index: Int, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val added = tracks.map { it.toQueueItem() }
        _state.update { q -> insert(q, index.coerceIn(0, q.items.size), added) }
    }

    private fun insert(q: PlaybackQueue, at: Int, added: List<QueueItem>): PlaybackQueue {
        val currentId = q.current?.id
        val newItems = q.items.toMutableList().apply { addAll(at, added) }
        return q.copy(items = newItems, currentIndex = resolveIndex(currentId, q.currentIndex, newItems))
    }

    override fun removeByIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        _state.update { q ->
            val currentId = q.current?.id
            val newItems = q.items.filterNot { it.id in idSet }
            q.copy(items = newItems, currentIndex = resolveIndex(currentId, q.currentIndex, newItems))
        }
    }

    override fun removeByIndices(indices: Collection<Int>) {
        if (indices.isEmpty()) return
        val idxSet = indices.toSet()
        _state.update { q ->
            val currentId = q.current?.id
            val newItems = q.items.filterIndexed { i, _ -> i !in idxSet }
            q.copy(items = newItems, currentIndex = resolveIndex(currentId, q.currentIndex, newItems))
        }
    }

    override fun clearQueue() {
        // Repeat and shuffle are standing preferences, not properties of the queue being cleared.
        _state.update { PlaybackQueue(repeatMode = it.repeatMode, shuffleOn = it.shuffleOn) }
    }

    override fun reorder(fromIndex: Int, toIndex: Int) {
        _state.update { q ->
            if (fromIndex !in q.items.indices) return@update q
            val to = toIndex.coerceIn(0, q.items.lastIndex)
            if (to == fromIndex) return@update q
            val currentId = q.current?.id
            val newItems = q.items.toMutableList().apply { add(to, removeAt(fromIndex)) }
            q.copy(items = newItems, currentIndex = resolveIndex(currentId, q.currentIndex, newItems))
        }
    }

    override fun updateItemState(id: String, status: QueueItemStatus, error: String?) {
        _state.update { q ->
            q.copy(items = q.items.map { if (it.id == id) it.copy(status = status, error = error) else it })
        }
    }

    override fun goToNext(): Boolean = moveCursorTo(_state.value.currentIndex + 1)

    override fun goToPrevious(): Boolean = moveCursorTo(_state.value.currentIndex - 1)

    override fun goToIndex(index: Int): Boolean = moveCursorTo(index)

    override fun goToId(id: String): Boolean =
        moveCursorTo(_state.value.items.indexOfFirst { it.id == id })

    private fun moveCursorTo(index: Int): Boolean {
        val q = _state.value
        if (index !in q.items.indices || index == q.currentIndex) return false
        _state.value = q.copy(currentIndex = index)
        return true
    }

    override fun advanceOnTrackEnd(): Boolean {
        val q = _state.value
        if (q.items.isEmpty()) return false
        return when (q.repeatMode) {
            RepeatMode.ONE -> true // replay the current item; cursor unchanged
            RepeatMode.ALL -> {
                val next = if (q.currentIndex < q.items.lastIndex) q.currentIndex + 1 else 0
                _state.value = q.copy(currentIndex = next)
                true
            }
            RepeatMode.OFF -> {
                if (q.currentIndex < q.items.lastIndex) {
                    _state.value = q.copy(currentIndex = q.currentIndex + 1)
                    true
                } else {
                    false // stay on the last item; the queue ended
                }
            }
        }
    }

    override fun setRepeatMode(mode: RepeatMode) {
        _state.update { it.copy(repeatMode = mode) }
    }

    override fun setShuffle(enabled: Boolean) {
        _state.update { q ->
            when {
                enabled == q.shuffleOn -> q
                // Nothing to reorder yet — just remember the choice for whatever is played next.
                q.items.isEmpty() -> q.copy(shuffleOn = enabled, unshuffledIds = null)
                enabled -> shuffled(q)
                else -> unshuffled(q)
            }
        }
    }

    /** Moves the current item to the front and shuffles the rest, so the song playing doesn't change. */
    private fun shuffled(q: PlaybackQueue): PlaybackQueue {
        val current = q.current
        val rest = q.items.filterNot { it.id == current?.id }.shuffled()
        val items = if (current == null) rest else listOf(current) + rest
        return q.copy(
            items = items,
            currentIndex = if (current == null) q.currentIndex else 0,
            shuffleOn = true,
            unshuffledIds = q.items.map { it.id },
        )
    }

    /** Puts [PlaybackQueue.items] back in its pre-shuffle order, keeping the cursor on the same item. */
    private fun unshuffled(q: PlaybackQueue): PlaybackQueue {
        val order = q.unshuffledIds ?: return q.copy(shuffleOn = false)
        val known = order.toSet()
        val byId = q.items.associateBy { it.id }
        // Anything queued while shuffled has no place in the saved order, so it goes to the end rather
        // than being dropped.
        val items = order.mapNotNull { byId[it] } + q.items.filterNot { it.id in known }
        return q.copy(
            items = items,
            currentIndex = resolveIndex(q.current?.id, q.currentIndex, items),
            shuffleOn = false,
            unshuffledIds = null,
        )
    }

    override fun setQueue(tracks: List<Track>, startIndex: Int, context: QueueContext) {
        if (tracks.isEmpty()) return
        val items = tracks.map { it.toQueueItem() }
        val fresh = PlaybackQueue(
            items = items,
            currentIndex = startIndex.coerceIn(0, items.lastIndex),
            repeatMode = _state.value.repeatMode,
            context = context,
        )
        // With shuffle on, playing an album shuffles it — starting from the track that was tapped.
        _state.value = if (_state.value.shuffleOn) shuffled(fresh) else fresh
    }

    override fun restore(
        items: List<QueueItem>,
        currentIndex: Int,
        repeatMode: RepeatMode,
        context: QueueContext,
        shuffleOn: Boolean,
        unshuffledIds: List<String>?,
    ) {
        if (items.isEmpty()) return
        _state.value = PlaybackQueue(
            items = items,
            currentIndex = currentIndex.coerceIn(0, items.lastIndex),
            repeatMode = repeatMode,
            context = context,
            shuffleOn = shuffleOn,
            // Items are restored in their shuffled order, so the un-shuffle map must come back with them
            // or the toggle would claim to be on and then do nothing.
            unshuffledIds = unshuffledIds?.takeIf { shuffleOn },
        )
    }

    /**
     * Recomputes the cursor after a list mutation. If the previously-current item survived (by id),
     * follow it; if it was removed, keep the positional cursor (clamped) so the next item slides in;
     * an empty result resets to -1.
     */
    private fun resolveIndex(oldCurrentId: String?, oldIndex: Int, newItems: List<QueueItem>): Int {
        if (newItems.isEmpty()) return -1
        val survived = if (oldCurrentId != null) newItems.indexOfFirst { it.id == oldCurrentId } else -1
        return if (survived >= 0) survived else oldIndex.coerceIn(0, newItems.lastIndex)
    }
}
