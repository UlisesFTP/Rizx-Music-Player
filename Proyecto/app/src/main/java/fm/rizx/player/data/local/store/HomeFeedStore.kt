package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.AttributedResult
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.stripResolutionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * The last Home the user saw, with the moment it was fetched so freshness can be judged and the feed
 * selection it was built from so a changed selection can't serve the previous platform's charts.
 *
 * [feedSelection] defaults to empty, which never equals a real selection — so a cache written before
 * this field existed is discarded exactly once, on the first read after updating.
 */
@Serializable
data class CachedHomeFeed(
    val feed: HomeFeed = HomeFeed(),
    val sections: List<ForYouSection> = emptyList(),
    val savedAtIso: String,
    val feedSelection: String = "",
)

/**
 * Persists the last rendered Home so opening the app shows it **immediately** instead of a spinner.
 *
 * This is the *stale-while-revalidate* half of the Home load: [read] hands back whatever was last seen
 * (rendered at once), and the ViewModel refreshes underneath it. A cold Home costs ~70 network
 * round-trips; without this every launch paid all of them before drawing a single pixel.
 *
 * One JSON blob written atomically (temp-then-rename), `Mutex`-serialized, every op `runCatching`-guarded
 * — the same idiom as [LyricsStore] / [PlaybackSessionStore] / [DownloadIndexStore], and for the same
 * reason: a corrupt or missing file must degrade to "nothing cached", never break the screen.
 *
 * Tracks go through [stripResolutionState] first, so **no resolved stream URL is ever written** — those
 * are ephemeral by contract (AGENTS.md). What is cached is metadata and cover URLs only.
 */
class HomeFeedStore(
    private val file: File,
    private val now: () -> Instant = { Instant.now() },
    /** Injectable so a test can keep the write on its own scheduler instead of a real IO thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val json get() = TrackJson.json
    private val lock = Mutex()

    /**
     * The cached Home, or `null` when there is none, it cannot be decoded, it is older than [MAX_AGE]
     * — week-old charts are worse than a short wait — or it was built from a different
     * [feedSelection] than the one now in force.
     */
    suspend fun read(feedSelection: String = ""): CachedHomeFeed? = withContext(io) {
        lock.withLock {
            runCatching {
                if (!file.exists()) return@runCatching null
                val cached = json.decodeFromString(CachedHomeFeed.serializer(), file.readText())
                cached.takeIf { age(it) < MAX_AGE && it.feedSelection == feedSelection }
            }.getOrNull()
        }
    }

    suspend fun write(feed: HomeFeed, sections: List<ForYouSection>, feedSelection: String = "") {
        withContext(io) {
            lock.withLock {
                runCatching {
                    val text = json.encodeToString(
                        CachedHomeFeed.serializer(),
                        CachedHomeFeed(
                            feed = feed.stripped(),
                            sections = sections.map { it.stripped() },
                            savedAtIso = now().toString(),
                            feedSelection = feedSelection,
                        ),
                    )
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) {
                        file.writeText(text) // fall back to a direct write if the atomic rename fails
                        tmp.delete()
                    }
                }
            }
        }
    }

    suspend fun clear() {
        withContext(io) { lock.withLock { runCatching { file.delete() } } }
    }

    /** Past this the cache is still shown, but a refresh is kicked off behind it. */
    fun isStale(cached: CachedHomeFeed): Boolean = age(cached) >= REFRESH_AFTER

    private fun age(cached: CachedHomeFeed): Duration =
        runCatching { Duration.between(Instant.parse(cached.savedAtIso), now()) }
            .getOrDefault(Duration.ZERO)
            // A clock that moved backwards must not make a stale cache look fresh forever.
            .let { if (it.isNegative) MAX_AGE else it }

    // ---- Stripping ----

    private fun HomeFeed.stripped() = copy(topTracks = topTracks.map { it.strippedTracks() })

    private fun AttributedResult<Track>.strippedTracks() = copy(items = items.map { it.stripResolutionState() })

    private fun ForYouSection.stripped(): ForYouSection = when (this) {
        is ForYouSection.Mix -> copy(items = items.map { it.stripResolutionState() })
        is ForYouSection.BecauseYouLike -> copy(items = items.map { it.stripResolutionState() })
        is ForYouSection.ArtistsForYou, is ForYouSection.AlbumsForYou -> this
    }

    private companion object {
        /** Serve instantly, refresh behind it after this. */
        val REFRESH_AFTER: Duration = Duration.ofMinutes(30)

        /** Beyond this the cache is discarded — charts this old would be misleading. */
        val MAX_AGE: Duration = Duration.ofDays(7)
    }
}
