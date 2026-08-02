package fm.rizx.player

import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasDiagnostics
import fm.rizx.player.domain.model.CanvasPreferences
import fm.rizx.player.domain.model.CanvasResolution
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.CanvasRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An in-memory [CanvasRepository] for tests that only need one to exist.
 *
 * [answer] decides what a lookup returns, and [calls] records what was asked — including the excluded
 * providers, which is how the cross-provider fallback is asserted.
 */
class FakeCanvasRepository(
    var answer: (Track, Set<String>) -> CanvasResolution = { _, _ -> CanvasResolution() },
) : CanvasRepository {

    val preferencesFlow = MutableStateFlow(CanvasPreferences())
    override val preferences: Flow<CanvasPreferences> = preferencesFlow

    private val _lastDiagnostics = MutableStateFlow(CanvasDiagnostics())
    override val lastDiagnostics: StateFlow<CanvasDiagnostics> = _lastDiagnostics

    /** Every lookup, as (track, excluded providers). */
    val calls = mutableListOf<Pair<Track, Set<String>>>()

    override suspend fun resolve(
        track: Track,
        preferences: CanvasPreferences,
        preferredAspect: CanvasAspect,
        exclude: Set<String>,
    ): CanvasResolution {
        calls += track to exclude
        return answer(track, exclude).also { _lastDiagnostics.value = it.diagnostics }
    }

    override fun report(diagnostics: CanvasDiagnostics) {
        _lastDiagnostics.value = diagnostics
    }
}
