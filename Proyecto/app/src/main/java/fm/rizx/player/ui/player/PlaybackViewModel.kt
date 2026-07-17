package fm.rizx.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.playback.AudioVisualizer
import fm.rizx.player.domain.model.QueueItem
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.QueueRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI seam for real playback: re-exposes the [PlaybackController]'s engine truth ([state]) plus the
 * currently-playing [currentItem] (from the queue cursor the controller keeps in sync), and forwards
 * transport intents. The mini-player and full player observe this; neither touches ExoPlayer.
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val favorites: FavoritesRepository,
    visualizer: AudioVisualizer,
    queue: QueueRepository,
) : ViewModel() {

    val state: StateFlow<PlaybackState> = controller.state

    /** Live audio spectrum (0..1 per bar) for the Now Playing waveform visualizer. */
    val levels: StateFlow<FloatArray> = visualizer.levels

    val currentItem: StateFlow<QueueItem?> = queue.state
        .map { it.current }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether the currently-playing track is favorited (live, so the heart updates immediately). */
    val currentIsFavorite: StateFlow<Boolean> = currentItem
        .flatMapLatest { item -> item?.let { favorites.isFavoriteTrack(it.track.source) } ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleCurrentFavorite() {
        val track = currentItem.value?.track ?: return
        viewModelScope.launch { favorites.toggleTrack(track) }
    }

    fun playQueueItem(id: String) = controller.playQueueItem(id)

    /** Play [track] as a radio (feed/search): the service auto-fills similar tracks for next/prev. */
    fun playRadio(track: Track) = controller.playRadio(track)

    fun toggle() = controller.toggle()

    fun next() = controller.skipNext()

    fun previous() = controller.skipPrevious()

    fun stop() = controller.stop()

    /** Seek to a 0..1 fraction of the current item's duration. */
    fun seekToFraction(fraction: Float) {
        val durationMs = state.value.durationMs
        if (durationMs > 0L) controller.seekTo((fraction.coerceIn(0f, 1f) * durationMs).toLong())
    }
}
