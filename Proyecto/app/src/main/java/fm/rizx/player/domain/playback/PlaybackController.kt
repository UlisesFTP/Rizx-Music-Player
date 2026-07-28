package fm.rizx.player.domain.playback

import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * The app-facing playback seam. UI and ViewModels issue commands here and observe [state]; they never
 * touch ExoPlayer directly (AGENTS.md). The implementation owns the single real player instance —
 * off-Compose. In the final architecture (Phase 9) that player moves into a
 * `PlaybackService : MediaSessionService`, but this contract stays the same.
 *
 * Command / engine-truth split (§6.8): these methods are *intents*; [state] reflects the engine's
 * actual truth once it applies them.
 */
interface PlaybackController {

    /** The observable engine truth. */
    val state: StateFlow<PlaybackState>

    /** Make [queueItemId] current, resolve its stream just-in-time, and start playing it. */
    fun playQueueItem(queueItemId: String)

    /** Append [track] to the queue and start playing it. */
    fun playTrack(track: Track)

    /**
     * **Replace** the queue with [tracks] (recording [context]), then play the item at [startIndex].
     * This is how a song is played *from a context* — an album/artist/playlist/liked list — so
     * next/previous traverse the whole list.
     */
    fun playContext(tracks: List<Track>, startIndex: Int, context: QueueContext)

    /**
     * Start a **radio** seeded from [track]: play it now as a 1-item `RADIO` queue; the playback service
     * then auto-fills similar tracks so next keeps going (Spotify-style). Used for feed plays.
     */
    fun playRadio(track: Track)

    /**
     * [playRadio], but refilled from the **YouTube Mix** — YT Music's own autoplay recommendations —
     * instead of the metadata provider's artist radio. Used for search-originated plays. Defaults to
     * [playRadio] so test fakes and simple implementations keep compiling unchanged.
     */
    fun playYoutubeRadio(track: Track) = playRadio(track)

    /**
     * Start a radio seeded from [track] using **whichever algorithm the user chose**
     * (`SettingsRepository.radioAlgorithm`). This is what every "play one song" entry point should
     * call — the Home feed, Search, the player's radio button — so the choice is honoured everywhere
     * from one place instead of each caller hard-coding an engine.
     *
     * Defaults to [playRadio] so test fakes keep compiling unchanged.
     */
    fun playAutoRadio(track: Track) = playRadio(track)

    /** Resume playback (also restarts from 0 if the queue had ended). */
    fun play()

    /** Pause without tearing down the current media. */
    fun pause()

    /** Toggle play/pause (handles the ended→replay case, §6.8). */
    fun toggle()

    /** Pause and rewind to the start of the current item. */
    fun stop()

    /** Seek to an absolute [positionMs] within the current item. */
    fun seekTo(positionMs: Long)

    /** Skip to the next queue item and play it. */
    fun skipNext()

    /** Skip to the previous queue item and play it. */
    fun skipPrevious()

    /** Release the underlying player. */
    fun release()
}
