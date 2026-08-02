package fm.rizx.player.playback.service

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import fm.rizx.player.core.network.DataSaverState
import fm.rizx.player.data.lossless.toAudioFormatUi
import fm.rizx.player.data.lossless.toStream
import fm.rizx.player.domain.lossless.LosslessResolver
import fm.rizx.player.domain.model.AudioFormatUi
import fm.rizx.player.domain.model.PlaybackResolverSettings
import fm.rizx.player.domain.model.QueueItem
import fm.rizx.player.domain.model.QueueItemStatus
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.repository.DownloadRepository
import fm.rizx.player.domain.repository.LocalLibraryRepository
import fm.rizx.player.domain.repository.QueueRepository
import fm.rizx.player.domain.usecase.CandidateResult
import fm.rizx.player.domain.usecase.StreamingResolver
import fm.rizx.player.domain.playback.NowPlayingFormat
import fm.rizx.player.playback.cache.AudioCache
import fm.rizx.player.playback.cache.KEY_FORMAT_SEPARATOR
import fm.rizx.player.playback.cache.audioCacheKey
import fm.rizx.player.playback.queueItemIdFromPlaceholder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Resolves a queue item's opaque placeholder URI (`rizx://queue/<id>`) to its real, ephemeral stream
 * URL. Two things make playback feel near-instant:
 *
 * 1. **A content cache keyed by [fm.rizx.player.domain.model.ProviderRef.identityKey].** Once a track's
 *    stream URL is resolved it's reused (until it expires) for re-play, backward seek, and skip — no
 *    repeat of the slow two-phase network resolve. Keyed by content identity (not `QueueItem.id`), so it
 *    survives timeline rebuilds and the same track re-tapped in a fresh queue.
 * 2. **[warm] pre-resolves upcoming items** off the loader thread, so ExoPlayer's own next-item
 *    pre-buffer (and a manual skip) hit a warm cache instead of a cold 2-4 round-trip extraction.
 *
 * A **downloaded** track skips all of that: [localOrCached] hands back a `file://` stream and no network
 * is touched at all. That is what makes offline playback work — and it is why the check is asked of
 * [DownloadRepository] rather than read from `Track.localFile`, which no copy of a track is guaranteed
 * to carry (see the repository's docs).
 *
 * The blocking resolve on an ExoPlayer loader thread ([resolveDataSpec]) remains as the cold-miss path.
 * Resolved URLs are never persisted (still ephemeral). The clock uses [System.currentTimeMillis]; tests
 * drive expiry via [PlaybackResolverSettings.streamExpiryMs] instead of a mock clock.
 */
@UnstableApi
class QueueStreamResolver @Inject constructor(
    private val queue: QueueRepository,
    private val resolver: StreamingResolver,
    private val settings: PlaybackResolverSettings,
    private val downloads: DownloadRepository,
    private val library: LocalLibraryRepository,
    /** The streamed-byte cache. Null in unit tests, which exercise the URL-resolution core only. */
    private val audioCache: AudioCache? = null,
    /**
     * The optional community-FLAC upgrade. Null in the tests that don't exercise it, which is also the
     * shape production takes whenever the feature is off — the same code path either way.
     */
    private val lossless: LosslessResolver? = null,
    /** Where the screen reads "what is playing" from. Null in the tests that don't assert on it. */
    private val nowPlayingFormat: NowPlayingFormat? = null,
    /** Silences the prefetch while saving data. Null in tests that don't exercise it. */
    private val dataSaver: DataSaverState? = null,
) : ResolvingDataSource.Resolver {

    /** `<identityKey>#<codec>` — see `audioCacheKey`. "raw" is its stand-in for an unknown codec. */
    private fun codecFromCacheKey(cacheKey: String): String? =
        cacheKey.substringAfterLast(KEY_FORMAT_SEPARATOR, "").takeIf { it.isNotBlank() && it != "raw" }

    private data class CachedStream(val stream: Stream, val resolvedAtMs: Long)

    /** Last resolved stream per queue item, so the service can detect an HLS stream (which the default
     *  progressive source can't play) and swap in an HLS-typed MediaItem. */
    private val resolvedStreams = ConcurrentHashMap<String, Stream>()

    /** Resolved stream per **content identity** (`ProviderRef.identityKey`), the reuse cache that makes
     *  re-play / skip / backward-seek skip the network resolve while the URL is still fresh. */
    private val streamCache = ConcurrentHashMap<String, CachedStream>()

    /** Content keys the background prefetch is currently resolving, so we never double-fetch one. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Keys whose "play straight from the byte cache" shortcut failed once (evicted mid-read, corrupt
     * entry). Without this the retry would take the same broken shortcut and loop; with it, the song
     * falls back to a normal network resolve.
     */
    private val cacheBypassed = ConcurrentHashMap.newKeySet<String>()

    /** Content keys whose community FLAC failed to play; they stay on the ordinary stream (see [suppressLossless]). */
    private val losslessSuppressed = ConcurrentHashMap.newKeySet<String>()

    /** Background scope for [warm]; off the ExoPlayer loader thread. Cancelled in [release]. */
    private val prefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** The stream last resolved for [queueItemId], or null if not resolved yet. */
    fun resolvedStreamFor(queueItemId: String): Stream? = resolvedStreams[queueItemId]

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val id = queueItemIdFromPlaceholder(dataSpec.uri.toString()) ?: return dataSpec
        val item = queue.state.value.items.firstOrNull { it.id == id } ?: return dataSpec
        val key = item.track.source.identityKey

        // Every byte already on disk? Then don't resolve at all. Skipping that round trip is what makes a
        // cached song play offline — resolving first would fail with no network and the cache would only
        // ever have saved bandwidth, not enabled playback.
        val cachedKey = if (key in cacheBypassed) null else audioCache?.fullyCachedKeyFor(key)
        if (cachedKey != null) {
            queue.updateItemState(id, QueueItemStatus.SUCCESS)
            // Nothing was resolved, so the only thing known about the format is what the cache key
            // records — the codec, which is the part the readout is actually about. Reporting that alone
            // beats an empty line on every song the user has already heard once.
            nowPlayingFormat?.publish(id, AudioFormatUi(codec = codecFromCacheKey(cachedKey)), trackKey = key)
            return dataSpec.buildUpon().setUri(cachedUri(cachedKey)).setKey(cachedKey).build()
        }

        // Show LOADING only for a genuine cold miss — a cache hit (prior resolve or prefetch) is instant,
        // and a downloaded file is never a miss at all.
        if (localOrCached(item) == null) {
            queue.updateItemState(id, QueueItemStatus.LOADING)
        }
        val stream = runBlocking { resolveCached(item) }
        if (stream == null) {
            queue.updateItemState(id, QueueItemStatus.ERROR, "No playable stream")
            throw IOException("No playable stream for “${item.track.title}”")
        }
        resolvedStreams[id] = stream
        nowPlayingFormat?.publish(id, stream.toAudioFormatUi(), trackKey = key)
        queue.updateItemState(id, QueueItemStatus.SUCCESS)
        // The key is what makes the byte cache work at all. Media3 keys on the URI by default, and ours
        // are ephemeral — the same song resolves to a different URL tomorrow, so a URI-keyed cache would
        // miss every time and fill the disk with bytes it could never reuse. The format goes in the key
        // too, so AAC and Opus copies of one song never share a resource (see [audioCacheKey]).
        return dataSpec.buildUpon()
            .setUri(Uri.parse(stream.url))
            .setKey(audioCacheKey(key, stream.codec))
            .build()
    }

    /**
     * Stand-in URI for a fully-cached song. Never fetched — [androidx.media3.datasource.cache.CacheDataSource]
     * serves the whole read from disk and only consults the upstream on a miss — but it has to be an
     * `https` URI so `DefaultDataSource` routes it to the cache-wrapped source rather than the file path.
     */
    private fun cachedUri(key: String): Uri = Uri.parse("https://cached.rizx.invalid/${Uri.encode(key)}")

    /**
     * Cache-aware resolution and the **testable core** (no Android/Media3 deps): returns the downloaded
     * file or a fresh cached URL when there is one, else resolves the first playable candidate and caches
     * it by content identity. Shared by [resolveDataSpec] (cold + warm play) and [warm] (background
     * prefetch).
     */
    suspend fun resolveCached(item: QueueItem): Stream? {
        val key = item.track.source.identityKey
        // Returning here, before cachePut, is what keeps a local file out of the expiring cache below —
        // otherwise a downloaded track would go back to the network every few hours for no reason.
        localOrCached(item)?.let { return it }

        // The community-lossless step, between "already on this device" and the ordinary chain.
        //
        // It has to be *here* rather than inside the provider chain: [StreamingRepositoryImpl] resolves a
        // track against its **native owner** first, so a track that came from YouTube would never reach a
        // lossless step placed further down. It also returns null instantly in every mode but
        // LOSSLESS_PREFERRED, so this line costs nothing for anyone who hasn't asked for it.
        losslessStream(item)?.let { cachePut(key, it); return it }

        val stream = resolveFirstPlayable(item) ?: return null
        cachePut(key, stream)
        return stream
    }

    /**
     * A verified FLAC for this track, or null — which is the answer for almost every track, and is not
     * an error in any of the cases: mode off, no index plugin installed, on mobile data, not in the
     * index, or in it but pointing at something that failed verification.
     */
    private suspend fun losslessStream(item: QueueItem): Stream? {
        val resolver = lossless ?: return null
        if (item.track.source.identityKey in losslessSuppressed) return null
        val validated = try {
            resolver.resolve(item.track)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // An optional upgrade must never be able to stop a song from playing.
            null
        } ?: return null
        return validated.toStream(item.track.source.identityKey)
    }

    /**
     * The downloaded file's stream if there is one, else a fresh cached URL, else null.
     *
     * Synchronous and non-blocking by design: it is called from [resolveDataSpec] *outside* its
     * `runBlocking`, on an ExoPlayer loader thread. [DownloadRepository.localStream] is an in-memory map
     * lookup plus one `exists()` stat.
     *
     * A missing file makes this fall through to the network path with nothing for the user to see — that
     * absence *is* the fallback for a deleted download or cleared app data.
     */
    private fun localOrCached(item: QueueItem): Stream? =
        library.localStream(item.track) ?: downloads.localStream(item.track) ?: cachedFresh(item.track.source.identityKey)

    /**
     * Pre-resolves [items] (typically the next 1-2 queue entries and the previous one) into the cache off
     * the loader thread, so a skip or ExoPlayer's own next-item pre-buffer hits a warm cache. Cheap:
     * metadata/extraction calls only — the audio itself is still pre-buffered by ExoPlayer natively. A
     * failed prefetch is silently ignored; it just leaves that item to resolve cold on demand.
     */
    fun warm(items: List<QueueItem>) {
        // Nothing is warmed while saving data. Each prefetch is a 2-4 round-trip NewPipe extraction for a
        // song that may never be reached, and the payoff is latency the listener only notices on a skip.
        // The song still resolves cold when it is actually its turn.
        if (dataSaver?.savingNow() == true) return
        for (item in items) {
            val key = item.track.source.identityKey
            if (localOrCached(item) != null) continue // downloaded or already warm: nothing to prefetch
            if (!inFlight.add(key)) continue // already being prefetched
            prefetchScope.launch {
                try {
                    resolveCached(item)
                } catch (_: Exception) {
                    // Never let a background prefetch failure touch playback.
                } finally {
                    inFlight.remove(key)
                }
            }
        }
    }

    /**
     * Drops a content key's cached URL (e.g. after a playback error from an expired URL) so it
     * re-resolves, and stops trusting the byte cache for it — a playback error is exactly the signal that
     * the "it's all on disk" shortcut was wrong.
     */
    fun invalidate(key: String) {
        streamCache.remove(key)
        cacheBypassed.add(key)
    }

    /**
     * Drops every reused stream URL so the next play resolves fresh. Used when a setting that changes
     * *which* stream gets picked (Hi-Res) flips: the cached URLs point at the old codec's file.
     */
    fun clearUrlCache() {
        streamCache.clear()
    }

    /**
     * Stops offering the community FLAC for [key] for the rest of this session.
     *
     * Called when one verified and then failed to play. Distinct from [invalidate], which only drops the
     * *URL*: the lossless verdict itself is still correct (it really is that recording), so re-resolving
     * would hand back the same dead host and the automatic fallback would become a loop. This is what
     * makes "FLAC fails → ordinary stream" a one-way step.
     */
    fun suppressLossless(key: String) {
        losslessSuppressed.add(key)
        streamCache.remove(key)
    }

    /** Cancels the background prefetch scope. Call from the service's `onDestroy`. */
    fun release() {
        prefetchScope.cancel()
    }

    /** The cached stream for [key] if still within the expiry window, else null (and evicts a stale one). */
    private fun cachedFresh(key: String): Stream? {
        val cached = streamCache[key] ?: return null
        if (System.currentTimeMillis() - cached.resolvedAtMs >= settings.streamExpiryMs) {
            streamCache.remove(key)
            return null
        }
        return cached.stream
    }

    private fun cachePut(key: String, stream: Stream) {
        streamCache[key] = CachedStream(stream, System.currentTimeMillis())
    }

    /** Resolves the first working candidate's stream for [item]'s track, or null if none play. */
    private suspend fun resolveFirstPlayable(item: QueueItem): Stream? {
        val candidates = when (val r = resolver.resolveCandidatesForTrack(item.track)) {
            is CandidateResult.Success -> r.candidates
            is CandidateResult.Failure -> return null
        }
        for (candidate in candidates.filterNot { it.failed }) {
            val resolved = resolver.resolveStreamForCandidate(candidate)
            if (resolved.stream != null) return resolved.stream
        }
        return null
    }
}
