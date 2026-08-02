package fm.rizx.player.data.lossless

import fm.rizx.player.core.network.DataSaverState
import fm.rizx.player.core.network.NetworkMonitor
import fm.rizx.player.domain.lossless.FlacInspector
import fm.rizx.player.domain.lossless.LosslessIndexSource
import fm.rizx.player.domain.lossless.LosslessMatcher
import fm.rizx.player.domain.lossless.LosslessResolver
import fm.rizx.player.domain.lossless.ValidatedLosslessStream
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * The whole feature behind one call, and a great many ways to say no.
 *
 * Every "no" returns `null`, and the caller's response to `null` is always the same: carry on with the
 * ordinary resolver. That uniformity is the feature — a song that isn't in the index, an index whose
 * host is down, a phone on mobile data, a file that turned out to be an MP3: none of them are errors
 * the listener should ever find out about, they are just songs that play normally.
 *
 * **Nothing here runs unless the mode is `LOSSLESS_PREFERRED`.** The first two checks are ordered so
 * that Standard and Best-available make no network call and no plugin invocation at all — the promise
 * that switching this off costs nothing is enforced by those two lines rather than by hoping.
 */
class DefaultLosslessResolver(
    private val settings: SettingsRepository,
    private val source: LosslessIndexSource,
    private val matcher: LosslessMatcher,
    private val inspector: FlacInspector,
    private val cache: LosslessResolutionCache,
    private val network: NetworkMonitor,
    private val dataSaver: DataSaverState,
) : LosslessResolver {

    override suspend fun resolve(track: Track): ValidatedLosslessStream? {
        // The **effective** mode, not the stored one: with data saving in force this is STANDARD and the
        // whole feature stops on this line — no plugin call, no index, no ranged request. The user's
        // saved choice is untouched and comes back the moment they switch saving off.
        if (!dataSaver.effectiveQualityMode().allowsLossless) return null
        if (!source.isAvailable()) return null
        if (!networkAllows()) return null

        val key = cache.keyFor(track.source.identityKey)
        cache.get(key)?.let { return it.stream }

        // Somebody else is already resolving this track — the prefetch warming the next queue item and
        // the player reaching it are the usual pair. Their answer will be in the cache; ours would be a
        // duplicate round trip against three hosts.
        if (!cache.beginResolve(key)) return null
        return try {
            withTimeout(TOTAL_BUDGET_MS) { resolveUncached(track, key) }
        } catch (e: CancellationException) {
            // A timeout is our own cancellation, not the caller's: remember it briefly and fall back.
            // Rethrowing the caller's cancellation is what keeps a skipped track from finishing its work.
            if (e is TimeoutCancellationException) {
                cache.putError(key)
                null
            } else {
                throw e
            }
        } catch (_: Exception) {
            cache.putError(key)
            null
        } finally {
            cache.endResolve(key)
        }
    }

    override suspend fun invalidate(trackKey: String) {
        cache.invalidate(cache.keyFor(trackKey))
    }

    private suspend fun resolveUncached(track: Track, key: String): ValidatedLosslessStream? {
        val items = source.lookup(track)
        if (items.isEmpty()) {
            cache.putMiss(key)
            return null
        }

        val candidates = matcher.candidates(track, items)
        if (candidates.isEmpty()) {
            cache.putMiss(key)
            return null
        }

        // The ambiguity rule (§8). Two rows scoring within a hair of each other and pointing at
        // *different files* is not an identification, it is a coin flip — so both get inspected, and if
        // the file's own duration doesn't separate them, neither is used. Refusing costs a normal
        // stream; guessing costs playing the wrong recording at higher fidelity.
        val contested = candidates.size > 1 &&
            candidates[0].item.url != candidates[1].item.url &&
            matcher.tooCloseToCall(candidates[0].matchScore, candidates[1].matchScore)

        // Each pass costs one ranged request, and the header it brings back is kept — the duration in it
        // is both what confirms the match and what the technical readout reports, so inspecting twice
        // for the same file would be paying a round trip for something already known.
        val verified = mutableListOf<ValidatedLosslessStream>()
        for (candidate in candidates.take(MAX_INSPECTIONS)) {
            // A row pointing at something that isn't a FLAC: try the next one. If they all look like
            // this the track is remembered as invalid for an hour.
            val info = inspector.inspect(candidate.item.url) ?: continue
            val settled = matcher.confirmWithDuration(track, candidate, info.durationMs) ?: continue
            verified += ValidatedLosslessStream(candidate = settled, url = candidate.item.url, info = info)
            // Uncontested, so the first file that verifies is the answer. Contested, so we need to see
            // whether the rival fits too before either can be trusted.
            if (!contested || verified.size >= 2) break
        }

        if (verified.isEmpty()) {
            cache.putInvalid(key)
            return null
        }

        val ranked = verified.sortedByDescending { it.candidate.matchScore }
        val best = ranked[0]
        val runnerUp = ranked.getOrNull(1)
        val stillTied = contested && runnerUp != null && runnerUp.url != best.url &&
            matcher.tooCloseToCall(best.candidate.matchScore, runnerUp.candidate.matchScore)
        if (stillTied) {
            // Two different files, both plausibly this song. The static answer is the right one.
            cache.putMiss(key)
            return null
        }

        cache.putHit(key, best)
        return best
    }

    /**
     * Whether the connection is one worth spending 25-27 MB on.
     *
     * Wi-Fi-only is on by default, and that is not caution for its own sake: a FLAC from this kind of
     * index is roughly an order of magnitude larger than the compressed stream it replaces, on top of
     * whatever the phone was already doing.
     */
    private suspend fun networkAllows(): Boolean {
        val snapshot = runCatching { network.snapshot() }.getOrNull() ?: return true
        // `!isUnmetered`, **not** `isCellular`. A phone hotspot reports Wi-Fi transport while billing
        // somebody's data plan, so keying on the radio made "only look for Lossless on Wi-Fi" quietly
        // untrue in exactly the case it exists for — a 24 MB file over a tethered connection.
        return !(settings.losslessWifiOnly.first() && !snapshot.isUnmetered)
    }

    private companion object {
        /** How many rows may be verified before giving up. Each is one ranged request. */
        const val MAX_INSPECTIONS = 3

        /** The whole attempt, start to finish. Playback is waiting behind this. */
        const val TOTAL_BUDGET_MS = 8_000L
    }
}
