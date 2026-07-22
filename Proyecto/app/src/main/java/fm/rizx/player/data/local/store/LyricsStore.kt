package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

/** A track's resolved lyrics plus the two things the user can decide about them. */
@Serializable
data class StoredLyrics(
    val lyrics: Lyrics,
    /** Milliseconds the words are shifted against the audio; user-set, 0 until they touch it. */
    val offsetMs: Long = 0L,
    /** The user picked this version by hand — it outranks anything a provider would match. */
    val pinned: Boolean = false,
    val fetchedAtIso: String,
)

/**
 * Persists resolved lyrics per track, so re-opening the screen is instant and works offline — which
 * matters most for downloaded songs, where the audio is on disk but the words would otherwise still
 * need the network.
 *
 * One small JSON blob (atomic temp-then-rename, `Mutex`-serialized, every op `runCatching`-guarded) —
 * the same idiom as [DownloadIndexStore] and [PlaybackSessionStore], and for the same reason: a corrupt
 * or missing file must degrade to "nothing cached", never break the screen.
 *
 * Keyed by `ProviderRef.identityKey`, so the same song resolved through a different provider is a
 * different entry — as it should be, since its duration (and therefore its timing) differs.
 *
 * **Misses are deliberately not cached.** A song nobody has transcribed today may be transcribed
 * tomorrow, and a persisted "no lyrics" would make that permanently invisible.
 */
class LyricsStore(
    private val file: File,
    private val now: () -> Instant = { Instant.now() },
) {

    private val json get() = TrackJson.json
    private val lock = Mutex()

    /** Loaded lazily on first access and kept in memory: the screen reads this on every track change. */
    private var entries: MutableMap<String, StoredLyrics>? = null

    suspend fun get(key: String): StoredLyrics? = withContext(Dispatchers.IO) {
        lock.withLock { loaded()[key] }
    }

    /** Caches [lyrics] for [key], preserving any offset the user already dialled in for this track. */
    suspend fun put(key: String, lyrics: Lyrics, pinned: Boolean = false) = mutate { map ->
        val previous = map[key]
        map[key] = StoredLyrics(
            lyrics = lyrics,
            // A pin replaces the words, so the old offset no longer describes them; a plain re-cache of
            // the same track keeps the correction the user made.
            offsetMs = if (pinned) 0L else previous?.offsetMs ?: 0L,
            pinned = pinned,
            fetchedAtIso = now().toString(),
        )
    }

    suspend fun setOffset(key: String, offsetMs: Long) = mutate { map ->
        map[key]?.let { map[key] = it.copy(offsetMs = offsetMs) }
    }

    suspend fun remove(key: String) = mutate { map -> map.remove(key) }

    // ---- Internals ----

    private suspend fun mutate(block: (MutableMap<String, StoredLyrics>) -> Unit) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                val map = loaded()
                block(map)
                evict(map)
                persist(map)
            }
        }
    }

    private fun loaded(): MutableMap<String, StoredLyrics> =
        entries ?: read().also { entries = it }

    private fun read(): MutableMap<String, StoredLyrics> = runCatching {
        if (!file.exists()) return@runCatching mutableMapOf()
        json.decodeFromString(PersistedLyrics.serializer(), file.readText())
            .entries
            .associate { it.key to it.value }
            .toMutableMap()
    }.getOrDefault(mutableMapOf())

    /**
     * Caps the cache, dropping the oldest first — but **never a pinned entry**: that is a decision the
     * user made, not a copy of something the network can hand back.
     */
    private fun evict(map: MutableMap<String, StoredLyrics>) {
        if (map.size <= MAX_ENTRIES) return
        map.entries
            .filterNot { it.value.pinned }
            .sortedBy { it.value.fetchedAtIso }
            .take(map.size - MAX_ENTRIES)
            .forEach { map.remove(it.key) }
    }

    private fun persist(map: Map<String, StoredLyrics>) {
        runCatching {
            if (map.isEmpty()) {
                file.delete()
                return@runCatching
            }
            val text = json.encodeToString(
                PersistedLyrics.serializer(),
                PersistedLyrics(map.map { PersistedEntry(it.key, it.value) }),
            )
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text) // fall back to a direct write if the atomic rename fails
                tmp.delete()
            }
        }
    }

    private companion object {
        /** ~300 songs of text is a couple of MB at most, and covers any realistic offline listening set. */
        const val MAX_ENTRIES = 300
    }
}

// ---- On-disk shape (private) ----

@Serializable
private data class PersistedLyrics(val entries: List<PersistedEntry>)

@Serializable
private data class PersistedEntry(val key: String, val value: StoredLyrics)
