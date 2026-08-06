package fm.rizx.player.domain.recognition

import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The contracts recognition is built from. Pure Kotlin: no Android, no HTTP, no Room, no Compose.
 *
 * The recognition backend is **not** registered in `ProviderRegistry`. That registry models "several
 * interchangeable catalogues, one active, the rest as fallbacks" — semantics recognition doesn't
 * have — and its `ProviderKind` enum is mirrored by the plugin bridge, so widening it would drag a
 * subsystem that has nothing to do with microphones into this feature. A plain injected
 * [RecognitionProvider] keeps the backend just as replaceable without any of that.
 */

/**
 * A capture that could not happen, carrying the reason as a [RecognitionError] rather than a message.
 *
 * Thrown, not returned, because it interrupts a suspending capture — but it is still a *category*, so
 * nothing downstream ends up parsing English out of an exception to decide what to show.
 */
class RecordingFailure(val error: RecognitionError) : Exception(error.name)

/** Captures ambient audio. Implementations must release the microphone the moment they are cancelled. */
interface MicrophoneRecorder {

    /** True when the device actually has an input this can record from. */
    fun isAvailable(): Boolean

    /**
     * Records for roughly [targetDurationMs], reporting normalised amplitude through [onAmplitude] as
     * it goes.
     *
     * Cancelling the calling coroutine stops the recording and frees the hardware; it does not return
     * a partial buffer.
     */
    suspend fun record(targetDurationMs: Long, onAmplitude: (Float) -> Unit): RecognitionAudio
}

/** Turns captured audio into an identification. */
interface RecognitionProvider {
    val id: String

    suspend fun recognize(audio: RecognitionAudio): RecognitionOutcome
}

/**
 * Finds the recognised recording inside this app's own catalogue, so it can be played through the
 * ordinary pipeline.
 *
 * Returns `null` rather than a guess: showing the match with a "search for it" action is better than
 * playing a live cover of the right song.
 */
interface RecognitionTrackResolver {
    suspend fun resolve(match: RecognitionMatch): Track?
}

/**
 * Owns the one recognition session that may be in flight, and the history of past ones.
 *
 * [state] is the single source of truth for the screen. The repository outlives any screen, so a
 * rotation, a trip to Settings for the microphone permission, or navigating away and back all rejoin
 * the same session instead of restarting it.
 */
interface RecognitionRepository {
    val state: StateFlow<RecognitionState>

    /**
     * Starts listening. Returns immediately — progress arrives through [state].
     *
     * A second call while one is running is ignored rather than queued: this is a button a person
     * presses, and two microphones' worth of audio is never what they meant.
     */
    fun start()

    /** Stops the microphone and abandons any request already in flight. */
    fun cancel()

    /** Back to [RecognitionState.Idle], discarding the last result. */
    fun reset()

    fun history(): Flow<List<RecognitionHistoryItem>>

    suspend fun deleteFromHistory(id: String)

    suspend fun clearHistory()
}
