package fm.rizx.player.playback.cache

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import fm.rizx.player.BuildConfig
import fm.rizx.player.core.network.NetworkMonitor
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import fm.rizx.player.domain.usecase.CandidateResult
import fm.rizx.player.domain.usecase.StreamingResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Finishes caching songs the user left half-played, so they become fully offline-available.
 *
 * The byte cache only ever holds what actually streamed, and ExoPlayer buffers roughly a minute ahead —
 * so a song you skipped near the end sits at 80% forever and still needs the network to play. This walks
 * those partial songs on an unmetered connection and pulls the remaining bytes.
 *
 * **Deliberately not a pre-fetcher.** A song with *zero* cached bytes is skipped: downloading music the
 * user never chose to hear is a different feature with a different bandwidth bill. Everything here was
 * already, at least partly, deliberately played.
 *
 * **Not WorkManager**, for the reason already established for downloads (`DownloadService`): it would
 * persist resolved stream URLs — ephemeral tokens the project forbids writing to disk — into its own job
 * database, and would still need a foreground service on API 34+. This runs inside the playback service
 * instead, which is already alive and already foreground.
 *
 * [shouldWait] lets the caller stall it while the player is buffering, so completing an old song never
 * competes for bandwidth with the one actually being listened to.
 */
@UnstableApi
class CacheCompleter(
    private val audioCache: AudioCache,
    private val resolver: StreamingResolver,
    private val recents: RecentlyPlayedRepository,
    private val favorites: FavoritesRepository,
    private val network: NetworkMonitor,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Completes what it can, then returns. Safe to call repeatedly; failures are swallowed per song. */
    suspend fun completePending(shouldWait: () -> Boolean = { false }) {
        withContext(io) {
            if (!network.snapshot().isUnmetered) {
                log("skipped: connection is metered")
                return@withContext
            }
            val (candidates, likedKeys) = candidates()
            log("${candidates.size} song(s) to finish")
            for (track in candidates) {
                // Re-check every song: the user can walk out of Wi-Fi mid-run.
                if (!network.snapshot().isUnmetered) return@withContext
                var waited = 0L
                while (shouldWait() && waited < MAX_WAIT_MS) {
                    delay(WAIT_STEP_MS)
                    waited += WAIT_STEP_MS
                }
                complete(track, liked = track.source.identityKey in likedKeys)
            }
        }
    }

    /** Partly-cached songs worth finishing, plus the liked-key set used to relax the threshold. */
    private suspend fun candidates(): Pair<List<Track>, Set<String>> {
        val liked = runCatching { favorites.favoriteTracks().first() }.getOrDefault(emptyList())
        val recent = runCatching { recents.recent(RECENT_LIMIT).first() }.getOrDefault(emptyList())
        val likedKeys = liked.mapTo(HashSet()) { it.source.identityKey }
        val pool = (liked + recent).distinctBy { it.source.identityKey }
        val candidates = pool
            .filter { worthCompleting(it, it.source.identityKey in likedKeys) }
            .take(MAX_PER_RUN)
        // Guarded at the call site, not inside log(): building this line re-reads the cache for five
        // tracks, which a release build has no reason to pay for.
        if (BuildConfig.DEBUG) {
            log(
                "pool=${pool.size} (liked=${liked.size} recent=${recent.size}) " +
                    pool.take(5).joinToString { "${it.title}@${(audioCache.cachedFraction(it.source.identityKey) * 100).toInt()}%" },
            )
        }
        return candidates to likedKeys
    }

    private fun worthCompleting(track: Track, liked: Boolean): Boolean {
        val fraction = audioCache.cachedFractionFor(track.source.identityKey)
        if (fraction <= 0f || fraction >= 1f) return false // never played, or already complete
        // A song abandoned after ten seconds isn't one the user "left half-finished" — finishing it would
        // spend a few megabytes on a track they actively skipped. Liked songs get the benefit of the doubt.
        return liked || fraction >= MIN_FRACTION
    }

    private suspend fun complete(track: Track, liked: Boolean) {
        try {
            val stream = resolveStream(track)
            if (stream == null) {
                log("no stream for “${track.title}”")
                return
            }
            // HLS is a playlist of segment URLs, each fetched under its own key, so there is no single
            // resource to complete here. Local files are already whole.
            if (stream.protocol != StreamProtocol.HTTP && stream.protocol != StreamProtocol.HTTPS) {
                log("skipping “${track.title}”: ${stream.protocol} can't be completed as one resource")
                return
            }

            val dataSpec = DataSpec.Builder()
                .setUri(Uri.parse(stream.url))
                // The identity key again — without it these bytes would land under a URL nothing will
                // ever ask for again, and the song would still not be cached. Bucketed by format so this
                // completes the copy it actually resolved, not a different codec's half-file.
                .setKey(audioCacheKey(track.source.identityKey, stream.codec))
                .setPosition(0)
                .setLength(C.LENGTH_UNSET.toLong())
                .build()

            val source = CacheDataSource.Factory()
                .setCache(audioCache.cache)
                .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
                .createDataSource()
            CacheWriter(source, dataSpec, null, null).cache()
            log("finished “${track.title}” → ${(audioCache.cachedFractionFor(track.source.identityKey) * 100).toInt()}%")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("could not finish “${track.title}”: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            // An expired URL, a dead provider, a full disk. This is opportunistic work: the song simply
            // stays partial and playback is untouched.
        }
    }

    private suspend fun resolveStream(track: Track): Stream? {
        val candidates = when (val r = resolver.resolveCandidatesForTrack(track)) {
            is CandidateResult.Success -> r.candidates
            is CandidateResult.Failure -> return null
        }
        for (candidate in candidates.filterNot { it.failed }) {
            resolver.resolveStreamForCandidate(candidate).stream?.let { return it }
        }
        return null
    }

    /** Debug-only: this work is invisible by design, so there has to be some way to confirm it ran. */
    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "RizxCacheCompleter"

        /** How far back through play history to look. */
        const val RECENT_LIMIT = 40

        /** Bounded so one pass can't spend the evening downloading. */
        const val MAX_PER_RUN = 12

        /** Below this, the song reads as "skipped", not "left half-finished". */
        const val MIN_FRACTION = 0.25f

        const val WAIT_STEP_MS = 2_000L

        /** Give up waiting for a quiet moment rather than stalling the whole pass forever. */
        const val MAX_WAIT_MS = 60_000L
    }
}
