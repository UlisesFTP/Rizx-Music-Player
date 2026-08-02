package fm.rizx.player.data.repository

import android.os.SystemClock
import fm.rizx.player.core.error.toSafeMessage
import fm.rizx.player.data.canvas.CanvasPolicy
import fm.rizx.player.data.canvas.CanvasProviderRegistry
import fm.rizx.player.data.canvas.CanvasResolutionCache
import fm.rizx.player.core.network.DataSaverState
import fm.rizx.player.domain.canvas.CanvasGate
import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.model.CanvasBlockReason
import fm.rizx.player.domain.model.CanvasDiagnostics
import fm.rizx.player.domain.model.CanvasPreferences
import fm.rizx.player.domain.model.CanvasResolution
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.CanvasRepository
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * The one road to a canvas: policy → cache → providers → matcher → cache.
 *
 * The order is the feature. Checking the policy first is what makes "canvas off" and "not on mobile data"
 * cost literally nothing rather than being resolved and then thrown away, and consulting the cache before
 * the providers is what makes skipping back to the previous song free.
 *
 * Nothing here throws for a provider failure. A canvas is decoration: every failure path ends in a
 * [CanvasResolution] with no candidate and a reason, and the screen keeps showing the cover.
 */
class CanvasRepositoryImpl(
    private val registry: CanvasProviderRegistry,
    private val cache: CanvasResolutionCache,
    private val policy: CanvasGate.Sampler,
    private val settings: SettingsRepository,
    /** Injectable so tests can assert the reported resolve time without a real clock. */
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    /** Folds Rizx's data-saver switch with Android's. Null in tests that drive the gate directly. */
    private val dataSaver: DataSaverState? = null,
) : CanvasRepository {

    override val preferences: Flow<CanvasPreferences> = combine(
        settings.canvasEnabled,
        settings.canvasNetworkPolicy,
        settings.canvasOnBatterySaver,
        settings.canvasQuality,
        combine(settings.canvasAppleEnabled, settings.canvasYoutubeEnabled) { apple, youtube -> apple to youtube },
    ) { enabled, network, onBatterySaver, quality, sources ->
        CanvasPreferences(
            enabled = enabled,
            network = network,
            allowOnBatterySaver = onBatterySaver,
            quality = quality,
            appleEnabled = sources.first,
            youtubeEnabled = sources.second,
        )
    }

    private val _lastDiagnostics = MutableStateFlow(CanvasDiagnostics())
    override val lastDiagnostics: StateFlow<CanvasDiagnostics> = _lastDiagnostics.asStateFlow()

    override suspend fun resolve(
        track: Track,
        preferences: CanvasPreferences,
        preferredAspect: CanvasAspect,
        exclude: Set<String>,
    ): CanvasResolution = attempt(track, preferences, preferredAspect, exclude)
        .also { _lastDiagnostics.value = it.diagnostics }

    override fun report(diagnostics: CanvasDiagnostics) {
        _lastDiagnostics.value = diagnostics
    }

    private suspend fun attempt(
        track: Track,
        preferences: CanvasPreferences,
        preferredAspect: CanvasAspect,
        exclude: Set<String>,
    ): CanvasResolution {
        // `dataSaver.saving`, not `settings.dataSaver`: the second is only Rizx's own switch, and Android's
        // own Data Saver has to count too — the user turned something on and meant it.
        val conditions = policy.sample(dataSaver = dataSaver?.saving?.first() ?: false)
        CanvasGate.blockedBy(preferences, conditions)?.let { reason ->
            return blocked(reason, preferences)
        }

        val quality = CanvasGate.quality(preferences.quality, conditions)
        // A source the user switched off is skipped exactly like one whose candidates just failed.
        val skip = exclude + disabledSources(preferences)
        if (skip.size >= ALL_SOURCES) return blocked(CanvasBlockReason.DISABLED, preferences)

        // The exclusion set is part of the key: the fallback answer must not overwrite the first one,
        // or going back to the song would serve the second-choice provider forever.
        val key = cacheKey(track.source.identityKey, skip)
        cache.get(key)?.let { cached ->
            return CanvasResolution(
                quality = quality,
                candidates = listOfNotNull(cached.candidate) + cached.spares,
                diagnostics = CanvasDiagnostics(
                    providerId = cached.candidate?.providerId,
                    score = cached.candidate?.score,
                    aspect = cached.candidate?.aspect,
                    cacheHit = true,
                    network = preferences.network,
                    // A remembered miss is still a miss — say so rather than showing a blank panel.
                    blockedBy = if (cached.candidate == null) CanvasBlockReason.NO_CANDIDATE else null,
                ),
            )
        }

        val startedAt = elapsedMs()
        var failure: Throwable? = null
        val candidates = try {
            registry.resolve(
                track = track,
                preferredAspect = preferredAspect,
                quality = quality,
                skip = skip,
                onError = { _, e -> failure = e },
            )
        } catch (e: CancellationException) {
            throw e // the caller changed song or left the screen; nothing to remember
        } catch (e: Exception) {
            failure = e
            emptyList()
        }
        val took = elapsedMs() - startedAt
        val candidate = candidates.firstOrNull()

        if (candidate == null) {
            // A failure is remembered for two minutes, a genuine "no video" for twenty: whatever broke
            // is usually already over, whereas a song without a video will still not have one later.
            if (failure != null) cache.putError(key) else cache.putMiss(key)
            return CanvasResolution(
                quality = quality,
                diagnostics = CanvasDiagnostics(
                    resolveMs = took,
                    network = preferences.network,
                    blockedBy = if (failure != null) {
                        CanvasBlockReason.PROVIDER_ERROR
                    } else {
                        CanvasBlockReason.NO_CANDIDATE
                    },
                    // Provider detail (hostnames, HTTP codes) stays in the logs; this string is shown.
                    error = failure?.toSafeMessage(GENERIC_ERROR),
                ),
            )
        }

        cache.putHit(key, candidate, candidates.drop(1))
        return CanvasResolution(
            quality = quality,
            candidates = candidates,
            diagnostics = CanvasDiagnostics(
                providerId = candidate.providerId,
                score = candidate.score,
                aspect = candidate.aspect,
                cacheHit = false,
                resolveMs = took,
                network = preferences.network,
            ),
        )
    }

    private fun blocked(reason: CanvasBlockReason, preferences: CanvasPreferences) = CanvasResolution(
        diagnostics = CanvasDiagnostics(blockedBy = reason, network = preferences.network),
    )

    /** The sources the user switched off in Settings. */
    private fun disabledSources(preferences: CanvasPreferences): Set<String> = buildSet {
        if (!preferences.appleEnabled) add(APPLE)
        if (!preferences.youtubeEnabled) add(YOUTUBE)
    }

    private fun cacheKey(identityKey: String, skip: Set<String>): String =
        if (skip.isEmpty()) identityKey else identityKey + "|" + skip.sorted().joinToString(",")

    private companion object {
        const val GENERIC_ERROR = "Couldn't load a canvas for this song."

        const val APPLE = "apple"
        const val YOUTUBE = "youtube"

        /** Both sources off is the same outcome as the feature being off, and costs no round trip. */
        const val ALL_SOURCES = 2
    }
}
