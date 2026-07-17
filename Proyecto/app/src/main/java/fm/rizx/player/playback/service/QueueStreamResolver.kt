package fm.rizx.player.playback.service

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import fm.rizx.player.domain.model.PlaybackResolverSettings
import fm.rizx.player.domain.model.QueueItem
import fm.rizx.player.domain.model.QueueItemStatus
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.repository.DownloadRepository
import fm.rizx.player.domain.repository.LocalLibraryRepository
import fm.rizx.player.domain.repository.QueueRepository
import fm.rizx.player.domain.usecase.CandidateResult
import fm.rizx.player.domain.usecase.StreamingResolver
import fm.rizx.player.playback.queueItemIdFromPlaceholder
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
) : ResolvingDataSource.Resolver {

    private data class CachedStream(val stream: Stream, val resolvedAtMs: Long)

    /** Last resolved stream per queue item, so the service can detect an HLS stream (which the default
     *  progressive source can't play) and swap in an HLS-typed MediaItem. */
    private val resolvedStreams = ConcurrentHashMap<String, Stream>()

    /** Resolved stream per **content identity** (`ProviderRef.identityKey`), the reuse cache that makes
     *  re-play / skip / backward-seek skip the network resolve while the URL is still fresh. */
    private val streamCache = ConcurrentHashMap<String, CachedStream>()

    /** Content keys the background prefetch is currently resolving, so we never double-fetch one. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Background scope for [warm]; off the ExoPlayer loader thread. Cancelled in [release]. */
    private val prefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** The stream last resolved for [queueItemId], or null if not resolved yet. */
    fun resolvedStreamFor(queueItemId: String): Stream? = resolvedStreams[queueItemId]

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val id = queueItemIdFromPlaceholder(dataSpec.uri.toString()) ?: return dataSpec
        val item = queue.state.value.items.firstOrNull { it.id == id } ?: return dataSpec

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
        queue.updateItemState(id, QueueItemStatus.SUCCESS)
        return dataSpec.withUri(Uri.parse(stream.url))
    }

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
        val stream = resolveFirstPlayable(item) ?: return null
        cachePut(key, stream)
        return stream
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

    /** Drops a content key's cached URL (e.g. after a playback error from an expired URL) so it re-resolves. */
    fun invalidate(key: String) {
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
