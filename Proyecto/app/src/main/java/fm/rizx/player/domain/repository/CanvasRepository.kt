package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasDiagnostics
import fm.rizx.player.domain.model.CanvasPreferences
import fm.rizx.player.domain.model.CanvasResolution
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one way to ask for a canvas.
 *
 * The UI never touches a provider, and never decides for itself whether the network allows one: it hands
 * over the track and the preferences and gets back either a candidate or a reason there isn't one. That
 * ordering matters — the policy is checked *before* anything is resolved, so a canvas that isn't allowed
 * costs zero bytes rather than being fetched and then not shown.
 */
interface CanvasRepository {

    /** The live preferences, so callers can gate their own work without reassembling them. */
    val preferences: Flow<CanvasPreferences>

    /**
     * How the most recent [resolve] went, for the diagnostics panel in Settings.
     *
     * It lives here rather than on the Now Playing ViewModel because that one is scoped to its
     * back-stack entry and is gone by the time anyone opens Settings to ask what happened.
     */
    val lastDiagnostics: StateFlow<CanvasDiagnostics>

    /**
     * Resolves the canvas for [track] under [preferences], or explains why it didn't.
     *
     * [exclude] names providers to pass over — how the player asks for a second opinion after every
     * candidate the winning provider gave it failed to play. It is part of the cache key, so the first
     * answer stays remembered rather than being overwritten by the fallback.
     *
     * Never throws for a provider failure — a broken canvas source ends in the static cover like any
     * other miss. Cancellation propagates normally, so leaving the screen cancels cleanly.
     */
    suspend fun resolve(
        track: Track,
        preferences: CanvasPreferences,
        preferredAspect: CanvasAspect = CanvasAspect.LANDSCAPE,
        exclude: Set<String> = emptySet(),
    ): CanvasResolution

    /**
     * Records what the *player* found out — the frame size and rate it actually decoded, and how long
     * the first frame took.
     *
     * Resolution can't report those: an HLS master advertises nine variants and which one plays depends
     * on the cap, the decoder and the link. Only Media3 knows, and only after it has drawn something.
     */
    fun report(diagnostics: CanvasDiagnostics)
}
