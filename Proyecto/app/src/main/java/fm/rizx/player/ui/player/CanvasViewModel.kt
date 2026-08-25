package fm.rizx.player.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.view.TextureView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasDiagnostics
import fm.rizx.player.domain.model.CanvasNetworkPolicy
import fm.rizx.player.domain.model.CanvasPreferences
import fm.rizx.player.domain.model.CanvasResolution
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.CanvasRepository
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.playback.canvas.CanvasMediaCache
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

/** The two places allowed to borrow the app's single muted video player, in priority order. */
enum class CanvasPlacement { NOW_PLAYING, HOME_HERO }

internal fun canvasPreferencesFor(
    preferences: CanvasPreferences,
    placement: CanvasPlacement,
    motionEnabled: Boolean,
): CanvasPreferences? = when {
    !preferences.enabled || !motionEnabled -> null
    placement == CanvasPlacement.NOW_PLAYING && !preferences.showOnNowPlaying -> null
    placement == CanvasPlacement.HOME_HERO -> preferences.copy(
        network = CanvasNetworkPolicy.UNMETERED_ONLY,
        allowOnBatterySaver = false,
    )
    else -> preferences
}

private data class CanvasRequest(
    val track: Track,
    val aspect: CanvasAspect,
    val motionEnabled: Boolean,
) {
    val key: String = "${track.source.identityKey}|${aspect.name}|$motionEnabled"
}

/**
 * One coordinator above navigation for every decorative video surface. A placement becomes active only
 * while its route and content are visible; Now Playing wins the brief overlap during navigation.
 */
@HiltViewModel
class CanvasViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val canvas: CanvasRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CanvasState())
    val state: StateFlow<CanvasState> = _state.asStateFlow()

    // The byte cache is Media3's own unstable surface; opting in here keeps it to this one property
    // instead of spreading the marker across the ViewModel.
    @OptIn(UnstableApi::class)
    private val controller = CanvasPlaybackController(
        context,
        dataSourceFactory = CanvasMediaCache.dataSourceFactory(context),
    ).apply {
        setVisible(false)
        onState = { playerState -> _state.update { it.copy(player = playerState) } }
        onVideoInfo = { info -> recordVideoInfo(info) }
        onExhausted = { providerId -> retryWithout(providerId) }
    }

    /** Whether the master Canvas toggle is on. Home never overrides an explicit off. */
    val enabled: StateFlow<Boolean> =
        settings.canvasEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val requests = mutableMapOf<CanvasPlacement, CanvasRequest>()
    private val visiblePlacements = mutableSetOf<CanvasPlacement>()
    private var activePlacement: CanvasPlacement? = null

    /** Stale-result protection across song, page and placement changes. */
    private var generation = 0
    private var inFlight: Job? = null
    private var loadedKey: String? = null

    /** Kept for the one bounded cross-provider retry. */
    private var currentTrack: Track? = null
    private var currentPreferences: CanvasPreferences? = null
    private var currentAspect: CanvasAspect = CanvasAspect.SQUARE
    private var excluded: Set<String> = emptySet()

    fun show(
        track: Track?,
        placement: CanvasPlacement = CanvasPlacement.NOW_PLAYING,
        aspect: CanvasAspect = CanvasAspect.SQUARE,
        motionEnabled: Boolean = true,
    ) {
        if (track == null) requests.remove(placement)
        else requests[placement] = CanvasRequest(track, aspect, motionEnabled)
        selectActivePlacement()
    }

    fun setVisible(placement: CanvasPlacement, visible: Boolean) {
        if (visible) visiblePlacements += placement else visiblePlacements -= placement
        selectActivePlacement()
    }

    /** Compatibility for the Now Playing call shape used before placement ownership existed. */
    fun setVisible(visible: Boolean) = setVisible(CanvasPlacement.NOW_PLAYING, visible)

    private fun selectActivePlacement() {
        val desired = listOf(CanvasPlacement.NOW_PLAYING, CanvasPlacement.HOME_HERO)
            .firstOrNull { it in visiblePlacements && requests[it] != null }
        if (desired == null) {
            deactivate()
            return
        }

        val request = requests.getValue(desired)
        val requestKey = "${desired.name}|${request.key}"
        if (desired == activePlacement && requestKey == loadedKey) {
            controller.setVisible(true)
            return
        }

        activePlacement = desired
        resolve(desired, request, requestKey)
    }

    private fun resolve(placement: CanvasPlacement, request: CanvasRequest, requestKey: String) {
        inFlight?.cancel()
        loadedKey = requestKey
        currentTrack = request.track
        currentAspect = request.aspect
        currentPreferences = null
        excluded = emptySet()
        val mine = ++generation
        controller.clear()
        controller.setVisible(true)
        _state.value = CanvasState(
            activePlacement = placement,
            trackKey = request.track.source.identityKey,
        )

        inFlight = viewModelScope.launch {
            val persisted = canvas.preferences.first()
            val prefs = canvasPreferencesFor(persisted, placement, request.motionEnabled)
            if (prefs == null) {
                if (mine == generation) {
                    _state.value = CanvasState(
                        activePlacement = placement,
                        trackKey = request.track.source.identityKey,
                    )
                }
                return@launch
            }
            currentPreferences = prefs
            _state.update { it.copy(resolving = true, activePlacement = placement) }
            apply(canvas.resolve(request.track, prefs, request.aspect), mine, placement)
        }
    }

    /** Ask the next provider once after every candidate from the first provider failed to decode. */
    private fun retryWithout(providerId: String?) {
        val placement = activePlacement ?: return
        if (placement !in visiblePlacements) return
        val track = currentTrack ?: return
        val prefs = currentPreferences ?: return
        val provider = providerId?.takeIf { it !in excluded } ?: return
        excluded = excluded + provider
        val mine = ++generation
        inFlight?.cancel()
        inFlight = viewModelScope.launch {
            apply(canvas.resolve(track, prefs, currentAspect, excluded), mine, placement)
        }
    }

    private fun apply(resolution: CanvasResolution, mine: Int, placement: CanvasPlacement) {
        if (mine != generation || placement != activePlacement || placement !in visiblePlacements) return
        _state.value = CanvasState(
            activePlacement = placement,
            trackKey = currentTrack?.source?.identityKey,
            diagnostics = resolution.diagnostics,
        )
        controller.prepare(resolution.candidates, resolution.quality)
    }

    private fun deactivate() {
        if (activePlacement == null && loadedKey == null) return
        generation++
        inFlight?.cancel()
        inFlight = null
        loadedKey = null
        currentTrack = null
        currentPreferences = null
        excluded = emptySet()
        activePlacement = null
        controller.clear()
        controller.setVisible(false)
        _state.value = CanvasState()
    }

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

    /** The last identity handed to [prefetch], so a song on repeat costs one resolve, not many. */
    private var prefetchedKey: String? = null

    /**
     * Warms the resolution cache for the song that just started playing, so opening Now Playing shows
     * its canvas immediately instead of after an iTunes search and an album-page scrape.
     *
     * Fills the repository cache and nothing else — the player, the surface and the state machine are
     * untouched, and every policy gate (data saver, metered, battery) is applied inside `resolve`, so
     * a blocked canvas costs zero requests here too. Failures are swallowed: this is an optimization,
     * and the real resolve when the screen opens remains the authority.
     */
    fun prefetch(track: Track) {
        val key = track.source.identityKey
        if (key == prefetchedKey) return
        prefetchedKey = key
        viewModelScope.launch {
            val prefs = canvasPreferencesFor(
                canvas.preferences.first(),
                CanvasPlacement.NOW_PLAYING,
                motionEnabled = true,
            ) ?: return@launch
            runCatching { canvas.resolve(track, prefs, CanvasAspect.SQUARE) }
        }
    }

    /** The Now Playing overflow menu controls the existing master preference. */
    fun toggle() {
        viewModelScope.launch {
            val next = !settings.canvasEnabled.first()
            loadedKey = null
            if (!next) deactivate()
            settings.setCanvasEnabled(next)
        }
    }

    /** The screen supplies a TextureView; neither screen ever sees the ExoPlayer. */
    fun attach(placement: CanvasPlacement, view: TextureView) {
        if (placement == activePlacement) controller.attach(view)
    }

    fun attach(view: TextureView) = attach(CanvasPlacement.NOW_PLAYING, view)

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }
}

data class CanvasState(
    val player: CanvasPlaybackController.State = CanvasPlaybackController.State.IDLE,
    val resolving: Boolean = false,
    val activePlacement: CanvasPlacement? = null,
    val trackKey: String? = null,
    val diagnostics: CanvasDiagnostics = CanvasDiagnostics(),
) {
    val playing: Boolean get() = player == CanvasPlaybackController.State.PLAYING
    val hasCandidate: Boolean get() = player != CanvasPlaybackController.State.IDLE
    fun playingFor(placement: CanvasPlacement): Boolean = activePlacement == placement && playing
    fun hasCandidateFor(placement: CanvasPlacement): Boolean = activePlacement == placement && hasCandidate
}
