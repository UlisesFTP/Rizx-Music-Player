package fm.rizx.player.ui.player

import android.content.Context
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.rizx.player.core.network.NetworkMonitor
import fm.rizx.player.data.canvas.CanvasSource
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the muted, looping video behind the Now Playing artwork.
 *
 * **A second, separate ExoPlayer, deliberately.** The service's player is the audio player — it drives
 * the MediaSession, audio focus, the notification, the queue timeline and the visualiser tap. Putting a
 * decorative video through it would break every one of those. This one is muted, video-only in effect,
 * scoped to the Now Playing back-stack entry, and released in [onCleared] the moment you navigate away.
 *
 * It lives in a ViewModel rather than a Composable because the project forbids touching ExoPlayer from
 * Compose — [attach] takes the surface so the screen never holds the player itself.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class CanvasViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val canvas: CanvasSource,
    private val settings: SettingsRepository,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _state = MutableStateFlow(CanvasState())
    val state: StateFlow<CanvasState> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var loadedKey: String? = null

    /** Whether the canvas toggle is on. Persisted, so it survives the screen and the process. */
    val enabled: StateFlow<Boolean> =
        settings.canvasEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Points the video at [track], or clears it. Safe to call on every recomposition. */
    fun show(track: Track?) {
        viewModelScope.launch {
            val on = settings.canvasEnabled.first()
            if (!on || track == null || !allowedOnThisNetwork()) {
                clear()
                return@launch
            }
            val key = track.source.identityKey
            if (key == loadedKey) return@launch
            loadedKey = key
            _state.value = CanvasState(loading = true)
            val url = canvas.videoUrlFor(track)
            // The track may have changed while the extraction was in flight.
            if (loadedKey != key) return@launch
            if (url == null) {
                _state.value = CanvasState() // nothing to show; the artwork just stays
                return@launch
            }
            startPlaying(url)
        }
    }

    fun toggle() {
        viewModelScope.launch {
            val next = !settings.canvasEnabled.first()
            settings.setCanvasEnabled(next)
            if (!next) clear()
            loadedKey = null // so re-enabling reloads the current song
        }
    }

    /**
     * Hands the video surface to the player. The screen never touches the player itself.
     *
     * A [TextureView], not a `SurfaceView`: a SurfaceView lives on its own hardware layer *behind* the
     * window and ignores view alpha, so it would both be hidden by the artwork's gradient and refuse to
     * cross-fade. A TextureView composites in order like any other view, which is what a backdrop needs.
     */
    fun attach(view: TextureView) {
        player?.setVideoTextureView(view)
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        if (_state.value.playing) player?.play()
    }

    private fun startPlaying(url: String) {
        val p = player ?: buildPlayer().also { player = it }
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.play()
        _state.value = CanvasState(playing = true)
    }

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(context).build().apply {
        // Muted and looping: the song's own audio is playing from the service, and a canvas repeats.
        volume = 0f
        repeatMode = Player.REPEAT_MODE_ONE
        playWhenReady = true
        addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Decoration must never surface an error or stop the music — just fall back to artwork.
                clear()
            }
        })
    }

    private fun clear() {
        player?.stop()
        player?.clearMediaItems()
        _state.value = CanvasState()
    }

    /**
     * The same gate the streaming provider uses for quality: a canvas is optional bytes, so it stays off
     * when the user asked to save data on cellular, or the signal is too weak to afford a second stream.
     */
    private suspend fun allowedOnThisNetwork(): Boolean {
        val net = networkMonitor.snapshot()
        return !((settings.dataSaver.first() && net.isCellular) || net.isBadSignal)
    }

    override fun onCleared() {
        player?.release()
        player = null
        super.onCleared()
    }
}

/** [playing] gates the video layer's fade-in; [loading] is the extraction round-trip. */
data class CanvasState(val playing: Boolean = false, val loading: Boolean = false)
