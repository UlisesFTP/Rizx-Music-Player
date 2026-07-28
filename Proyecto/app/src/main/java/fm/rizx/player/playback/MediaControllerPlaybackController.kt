package fm.rizx.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.RadioMode
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.playback.PlaybackController
import fm.rizx.player.domain.playback.PlaybackState
import fm.rizx.player.domain.repository.QueueRepository
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.playback.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-side [PlaybackController] that drives the [PlaybackService]'s player through a Media3
 * [MediaController] (§6.8 command/engine-truth split): commands forward to the session, and the
 * session's player is the source of truth, observed back into [state]. The service owns the ExoPlayer;
 * this class never touches it directly.
 */
@Singleton
class MediaControllerPlaybackController @Inject constructor(
    @ApplicationContext context: Context,
    private val queue: QueueRepository,
    settings: SettingsRepository,
) : PlaybackController {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null

    /**
     * The user's chosen radio engine, mirrored so [playAutoRadio] can read it synchronously — the
     * transport commands are fire-and-forget by design and must not suspend on a DataStore read.
     */
    private var radioAlgorithm: RadioMode = RadioMode.YOUTUBE

    init {
        scope.launch { settings.radioAlgorithm.collect { radioAlgorithm = it } }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                controller = future.get().also { c ->
                    c.addListener(ControllerListener())
                    pushState()
                    if (c.isPlaying) startTicker()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    // ---- Commands ----

    override fun playQueueItem(queueItemId: String) {
        val c = controller ?: return
        val index = queue.state.value.items.indexOfFirst { it.id == queueItemId }
        if (index < 0) return
        c.seekTo(index, 0L)
        c.play()
    }

    override fun playTrack(track: Track) {
        queue.addToQueue(listOf(track))
        val added = queue.state.value.items.lastOrNull() ?: return
        playQueueItem(added.id)
    }

    override fun playContext(tracks: List<Track>, startIndex: Int, context: QueueContext) {
        if (tracks.isEmpty()) return
        queue.setQueue(tracks, startIndex, context)
        // The service rebuilds the whole timeline positioned at the new currentIndex; playQueueItem just
        // makes sure playWhenReady flips on (the seek is a harmless best-effort — same idiom as playTrack).
        val current = queue.state.value.current ?: return
        playQueueItem(current.id)
    }

    override fun playRadio(track: Track) {
        val artist = track.artists.firstOrNull()
        val seed = artist?.source ?: track.source
        val label = artist?.name?.takeIf { it.isNotBlank() }?.let { "Radio · $it" } ?: "Radio"
        playContext(listOf(track), 0, QueueContext(kind = QueueSourceKind.RADIO, label = label, radioSeed = seed))
    }

    override fun playYoutubeRadio(track: Track) {
        val label = track.title.takeIf { it.isNotBlank() }?.let { "Mix · $it" } ?: "Mix"
        playContext(
            listOf(track),
            0,
            QueueContext(
                kind = QueueSourceKind.RADIO,
                label = label,
                radioSeed = track.source,
                radioMode = RadioMode.YOUTUBE,
            ),
        )
    }

    override fun playAutoRadio(track: Track) = when (radioAlgorithm) {
        RadioMode.YOUTUBE -> playYoutubeRadio(track)
        RadioMode.ARTIST -> playRadio(track)
    }

    override fun play() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0L)
        c.play()
    }

    override fun pause() {
        controller?.pause()
    }

    override fun toggle() {
        val c = controller ?: return
        when {
            c.playbackState == Player.STATE_ENDED -> { c.seekTo(0L); c.play() }
            c.isPlaying -> c.pause()
            else -> c.play()
        }
    }

    override fun stop() {
        val c = controller ?: return
        c.pause()
        c.seekTo(0L)
    }

    override fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun skipNext() {
        controller?.seekToNextMediaItem()
    }

    override fun skipPrevious() {
        controller?.seekToPreviousMediaItem()
    }

    override fun release() {
        ticker?.cancel()
        controller?.release()
        controller = null
        scope.cancel()
    }

    // ---- Engine truth → observable state ----

    private inner class ControllerListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            pushState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicker() else stopTicker()
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                pushState()
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun pushState() {
        val c = controller ?: return
        val error = c.playerError
        val rawDuration = c.duration
        _state.value = PlaybackState(
            status = playbackStatusOf(c.playbackState, c.playWhenReady, error != null),
            currentQueueItemId = c.currentMediaItem?.mediaId,
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = if (rawDuration == androidx.media3.common.C.TIME_UNSET) 0L else rawDuration,
            bufferedPositionMs = c.bufferedPosition.coerceAtLeast(0L),
            isPlaying = c.isPlaying,
            playWhenReady = c.playWhenReady,
            error = error?.errorCodeName,
        )
    }

    private companion object {
        // 250 ms (4 Hz) keeps the scrubber + time readouts feeling responsive without meaningful cost.
        const val POSITION_POLL_MS = 250L
    }
}
