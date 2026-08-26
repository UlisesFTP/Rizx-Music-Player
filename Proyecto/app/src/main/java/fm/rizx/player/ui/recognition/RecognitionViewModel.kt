package fm.rizx.player.ui.recognition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.recognition.RecognitionHistoryItem
import fm.rizx.player.domain.recognition.RecognitionInbox
import fm.rizx.player.domain.recognition.RecognitionRepository
import fm.rizx.player.domain.recognition.RecognitionState
import fm.rizx.player.domain.usecase.RecognizeAmbientSong
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The recognition screen's window onto the session.
 *
 * Notably thin, and deliberately so: the session itself lives in [RecognitionRepository], which is a
 * singleton. If it lived here, rotating the phone or stepping into Settings to grant the microphone
 * permission would destroy a capture that was already half-recorded. This just observes.
 */
@HiltViewModel
class RecognitionViewModel @Inject constructor(
    private val recognize: RecognizeAmbientSong,
    private val repository: RecognitionRepository,
    private val playback: PlaybackController,
    private val requests: RecognitionInbox = RecognitionInbox(),
) : ViewModel() {

    val state: StateFlow<RecognitionState> = repository.state

    /** A request from a widget's microphone, waiting for this screen to act on it. */
    val pendingRequest: StateFlow<Long> get() = requests.pending

    /** Takes the pending request; true when there was one, so [listen] runs exactly once for it. */
    fun consumeRequest(): Boolean = requests.consume()

    val history: StateFlow<List<RecognitionHistoryItem>> = repository.history()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Pauses this app's own playback first — see [RecognizeAmbientSong]. */
    fun listen() = recognize()

    fun cancel() = repository.cancel()

    fun dismiss() = repository.reset()

    /** Straight into the normal pipeline: the resolver's job ended when it produced a [Track]. */
    fun play(track: Track) = playback.playAutoRadio(track)

    fun forget(id: String) {
        viewModelScope.launch { repository.deleteFromHistory(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
