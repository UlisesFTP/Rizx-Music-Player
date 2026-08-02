package fm.rizx.player.ui.player

import android.content.Context
import android.view.TextureView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasDiagnostics
import fm.rizx.player.domain.model.CanvasPreferences
import fm.rizx.player.domain.model.CanvasResolution
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.CanvasRepository
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.playback.canvas.CanvasPlaybackController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Orchestrates the Now Playing canvas: ask the repository, hand the answer to the player, keep the two
 * from getting out of step when songs change quickly.
 *
 * It owns no policy and no ExoPlayer of its own. The repository decides whether a canvas is allowed and
 * finds one; [CanvasPlaybackController] plays it. What is left here is the part that genuinely belongs to
 * the screen's lifetime — a request generation, one fallback across providers, and releasing the player
 * on the way out.
 */
@HiltViewModel
class CanvasViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val canvas: CanvasRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val controller = CanvasPlaybackController(context).apply {
        onState = { playerState ->
            _state.update { it.copy(player = playerState) }
        }
        onVideoInfo = { info -> recordVideoInfo(info) }
        onExhausted = { providerId -> retryWithout(providerId) }
    }

    private val _state = MutableStateFlow(CanvasState())
    val state: StateFlow<CanvasState> = _state.asStateFlow()

    /** Whether the canvas toggle is on. Persisted, so it survives the screen and the process. */
    val enabled: StateFlow<Boolean> =
        settings.canvasEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Every resolution carries the generation it was started in. A slow answer for the previous song must
     * not land on the current one — the brief calls it stale-result protection, and a cancelled job alone
     * doesn't guarantee it, because cancellation is cooperative and the last statement may already be
     * running when the next song arrives.
     */
    private var generation = 0
    private var inFlight: Job? = null
    private var loadedKey: String? = null

    /** Kept for the one cross-provider retry, which happens long after [show] has returned. */
    private var currentTrack: Track? = null
    private var currentPreferences: CanvasPreferences? = null
    private var excluded: Set<String> = emptySet()

    /** Points the video at [track], or clears it. Safe to call on every recomposition. */
    fun show(track: Track?) {
        val key = track?.source?.identityKey
        if (key != null && key == loadedKey) return

        inFlight?.cancel()
        loadedKey = key
        currentTrack = track
        excluded = emptySet()
        val mine = ++generation
        controller.clear()
        _state.value = CanvasState()

        if (track == null) return
        inFlight = viewModelScope.launch {
            val prefs = canvas.preferences.first()
            currentPreferences = prefs
            // The gate that costs nothing: with the canvas off, or this screen not enabled for it, we
            // never reach the repository at all — no lookup, no player, no bytes.
            if (!prefs.enabled || !prefs.showOnNowPlaying) {
                if (mine == generation) _state.value = CanvasState()
                return@launch
            }
            _state.update { it.copy(resolving = true) }
            // Square, because that is the shape of the Now Playing artwork block. Apple publishes a
            // portrait cut too, which is what a full-bleed backdrop would ask for.
            apply(canvas.resolve(track, prefs, CanvasAspect.SQUARE), mine)
        }
    }

    /**
     * The second opinion, asked once and only once.
     *
     * Every candidate the winning provider offered has failed to play — a dead link, an unsupported
     * codec. Rather than leaving the cover up when another source might have something, the same track is
     * resolved again with that provider excluded. Bounded by [excluded] growing: each provider gets one
     * turn, and when the set covers them all the repository returns immediately.
     */
    private fun retryWithout(providerId: String?) {
        val track = currentTrack ?: return
        val prefs = currentPreferences ?: return
        val provider = providerId?.takeIf { it !in excluded } ?: return
        excluded = excluded + provider
        val mine = ++generation
        inFlight?.cancel()
        inFlight = viewModelScope.launch {
            apply(canvas.resolve(track, prefs, CanvasAspect.SQUARE, excluded), mine)
        }
    }

    private fun apply(resolution: CanvasResolution, mine: Int) {
        if (mine != generation) return
        _state.value = CanvasState(diagnostics = resolution.diagnostics)
        controller.prepare(resolution.candidates, resolution.quality)
    }

    /**
     * Folds what the player actually decoded back into the diagnostics.
     *
     * Reported rather than predicted: an HLS master advertises nine variants, and which one plays depends
     * on the cap, the decoder and the link. It goes to the **repository** because that is the singleton
     * Settings reads from — this ViewModel is gone by the time anyone opens the panel to look.
     */
    private fun recordVideoInfo(info: CanvasPlaybackController.VideoInfo) {
        val merged = _state.value.diagnostics.copy(
            width = info.width,
            height = info.height,
            frameRate = info.frameRate,
            firstFrameMs = info.firstFrameMs,
        )
        _state.update { it.copy(diagnostics = merged) }
        canvas.report(merged)
    }

    /**
     * Flips the preference. The screen re-runs [show] by itself, because its `LaunchedEffect` keys on
     * [enabled] — so all this has to do is make sure that re-run isn't skipped.
     */
    fun toggle() {
        viewModelScope.launch {
            val next = !settings.canvasEnabled.first()
            // Cleared *before* the write, not after: the enabled flow and this coroutine race, and a
            // `loadedKey` still set when `show` re-runs would make it decide the song was already loaded.
            loadedKey = null
            if (!next) {
                generation++
                inFlight?.cancel()
                controller.clear()
                _state.value = CanvasState()
            }
            settings.setCanvasEnabled(next)
        }
    }

    /**
     * Hands the video surface to the player. The screen never touches the player itself.
     *
     * A [TextureView], not a `SurfaceView`: a SurfaceView lives on its own hardware layer *behind* the
     * window and ignores view alpha, so it would both be hidden by the artwork's gradient and refuse to
     * cross-fade. A TextureView composites in order like any other view, which is what a backdrop needs.
     */
    fun attach(view: TextureView) = controller.attach(view)

    /**
     * Whether Now Playing is actually in front.
     *
     * Driven from the screen's lifecycle. Before this existed the canvas kept decoding and buffering
     * video after the user pressed Home — `pause()`/`resume()` were written but never called.
     */
    fun setVisible(visible: Boolean) = controller.setVisible(visible)

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }
}

/**
 * What the screen needs to know.
 *
 * [player] gates the video layer's fade-in — it reaches [CanvasPlaybackController.State.PLAYING] only
 * once a real frame has been rendered. [hasCandidate] is separate and earlier: the surface has to exist
 * *before* a frame can be drawn on it, so the screen creates the `TextureView` on this and animates only
 * the alpha on [playing]. Gating the view itself on the fade deadlocks — no surface, no frame, no fade.
 */
data class CanvasState(
    val player: CanvasPlaybackController.State = CanvasPlaybackController.State.IDLE,
    val resolving: Boolean = false,
    val diagnostics: CanvasDiagnostics = CanvasDiagnostics(),
) {
    val playing: Boolean get() = player == CanvasPlaybackController.State.PLAYING
    val hasCandidate: Boolean get() = player != CanvasPlaybackController.State.IDLE
}
