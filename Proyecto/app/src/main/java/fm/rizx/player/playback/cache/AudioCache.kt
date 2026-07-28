package fm.rizx.player.playback.cache

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The audio byte cache: songs are kept **as they stream**, so replaying one costs no network at all and
 * works with the phone offline.
 *
 * This deliberately isn't a second download queue. Media3's [SimpleCache] stores the exact bytes
 * ExoPlayer already pulled to play the song, so filling it is free — a separate cache (DiskLruCache or
 * similar) would have to fetch every song a second time.
 *
 * **Keyed by content identity, never by URL.** Stream URLs here are ephemeral (YouTube's rotate, and the
 * project forbids persisting them), so a URL-keyed cache would miss every single time and quietly fill
 * the disk with bytes it could never reuse. `QueueStreamResolver` stamps each request with the track's
 * `ProviderRef.identityKey` via `DataSpec.setKey`, which Media3's default key factory then uses.
 *
 * Eviction is LRU with liked songs held back ([ProtectedLruCacheEvictor]).
 *
 * The size limit is read **once**, when the cache is first opened: [SimpleCache] takes its evictor at
 * construction. Changing the setting therefore applies from the next playback session, the same contract
 * the Hi-Res output toggle has, and for the same reason.
 */
@UnstableApi
class AudioCache(
    private val context: Context,
    private val settings: SettingsRepository,
    favorites: FavoritesRepository,
) {

    /** Liked track keys, mirrored so the evictor can consult them without touching the database. */
    @Volatile
    private var protectedKeys: Set<String> = emptySet()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            favorites.favoriteTracks().collect { tracks ->
                protectedKeys = tracks.mapTo(HashSet()) { it.source.identityKey }
            }
        }
    }

    /**
     * Opened lazily and exactly once. [SimpleCache] throws if two instances share a directory, so this
     * must stay a process-wide singleton — it is provided as one, and the service resolves the same
     * instance rather than building its own.
     */
    val cache: Cache by lazy {
        val maxBytes = runBlocking { settings.audioCacheBytes.first() }
        SimpleCache(
            directory(),
            ProtectedLruCacheEvictor(maxBytes) { key -> key in protectedKeys },
            StandaloneDatabaseProvider(context),
        )
    }

    /**
     * True when every byte of [key] is on disk, so playback can skip resolving a stream URL entirely.
     *
     * That skip is the whole point: resolving is a network round trip, and doing it for a song we already
     * hold would make "cached" mean "instant only while online".
     */
    fun isFullyCached(key: String): Boolean = runCatching {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        length > 0L && cache.isCached(key, 0L, length)
    }.getOrDefault(false)

    /**
     * The fully-cached format bucket for this song, or null. Songs are keyed **per format** (see
     * [audioCacheKey]), and any complete bucket plays offline — so switching Hi-Res on doesn't strand a
     * song the user already has, it just prefers whichever copy is whole.
     */
    fun fullyCachedKeyFor(identityKey: String): String? = runCatching {
        val prefix = identityKey + KEY_FORMAT_SEPARATOR
        cache.keys.firstOrNull { it.startsWith(prefix) && isFullyCached(it) }
    }.getOrNull()

    /** The best-cached fraction across this song's format buckets, 0..1. */
    fun cachedFractionFor(identityKey: String): Float = runCatching {
        val prefix = identityKey + KEY_FORMAT_SEPARATOR
        cache.keys.filter { it.startsWith(prefix) }.maxOfOrNull { cachedFraction(it) } ?: 0f
    }.getOrDefault(0f)

    /**
     * How much of [key] is on disk, 0..1. `0` also covers "never played" — the caller can't tell those
     * apart, and shouldn't: a song with no bytes cached is not one the user left half-finished.
     */
    fun cachedFraction(key: String): Float = runCatching {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length <= 0L) return 0f
        (cache.getCachedBytes(key, 0L, length).toFloat() / length).coerceIn(0f, 1f)
    }.getOrDefault(0f)

    /** Bytes currently held. */
    fun sizeBytes(): Long = runCatching { cache.cacheSpace }.getOrDefault(0L)

    /**
     * Drops every cached song. Removes resources one by one rather than deleting the directory: the cache
     * is open and in use by the player, and pulling the files out from under it corrupts its index.
     */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            runCatching { cache.keys.toSet().forEach { key -> cache.removeResource(key) } }
        }
    }

    private fun directory(): File = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    private companion object {
        /** Under `cacheDir`: the OS may reclaim it under storage pressure, which is correct for a cache. */
        const val DIRECTORY = "audio-cache"
    }
}

/** Separates a song's identity from the format its bytes are in. Never appears in a `ProviderRef` key. */
internal const val KEY_FORMAT_SEPARATOR = "#"

/**
 * The byte-cache key for one song **in one format**.
 *
 * Content identity alone is not enough. The same song is fetched as AAC or as Opus depending on the
 * Hi-Res setting, and under a single key Media3 would happily serve the first half from the cached AAC
 * file and fetch the second half from the Opus one — a decoder error or noise mid-song, not a cache
 * miss. Bucketing by format makes every key hold exactly one resource.
 */
fun audioCacheKey(identityKey: String, codec: String?): String =
    identityKey + KEY_FORMAT_SEPARATOR + (codec?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "raw")
