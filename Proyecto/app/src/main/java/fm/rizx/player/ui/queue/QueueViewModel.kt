package fm.rizx.player.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.domain.model.PlaybackQueue
import fm.rizx.player.domain.model.RepeatMode
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.PlaylistRepository
import fm.rizx.player.domain.repository.QueueRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Screen-integration seam for the queue: re-exposes the repository's observable [queue] state and
 * delegates user actions to it. The visible Queue screen (Compose) and results→queue wiring arrive
 * in Phase 7; this ViewModel is the stable point they plug into so the UI never touches the
 * repository — or providers/ExoPlayer — directly.
 */
@HiltViewModel
class QueueViewModel @Inject constructor(
    private val queueRepository: QueueRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    val queue: StateFlow<PlaybackQueue> = queueRepository.state

    /** Save the current queue (including duplicates) as a new playlist, resolution-stripped. */
    fun saveAsPlaylist(name: String) {
        val tracks = queueRepository.state.value.items.map { it.track }
        val trimmed = name.trim()
        if (trimmed.isEmpty() || tracks.isEmpty()) return
        viewModelScope.launch { playlistRepository.saveQueueAsPlaylist(trimmed, tracks) }
    }

    /** Append a track (e.g. a tapped search result) to the end of the queue. */
    fun addToQueue(track: Track) {
        queueRepository.addToQueue(listOf(track))
    }

    /** Insert a track right after the current item ("Play next"). */
    fun addNext(track: Track) {
        queueRepository.addNext(listOf(track))
    }

    /** Make the tapped queue item the current one. */
    fun playItem(id: String) {
        queueRepository.goToId(id)
    }

    /** Remove a single queue item. */
    fun removeItem(id: String) {
        queueRepository.removeByIds(listOf(id))
    }

    /** Drag-reorder within the queue. */
    fun move(fromIndex: Int, toIndex: Int) {
        queueRepository.reorder(fromIndex, toIndex)
    }

    fun clear() {
        queueRepository.clearQueue()
    }

    fun next() {
        queueRepository.goToNext()
    }

    fun previous() {
        queueRepository.goToPrevious()
    }

    /** Cycle the repeat toggle: OFF → ALL → ONE → OFF. */
    fun cycleRepeatMode() {
        val next = when (queue.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        queueRepository.setRepeatMode(next)
    }

    /** Shuffle the rest of the queue, or put it back in order. The song playing is never interrupted. */
    fun toggleShuffle() {
        queueRepository.setShuffle(!queue.value.shuffleOn)
    }
}
