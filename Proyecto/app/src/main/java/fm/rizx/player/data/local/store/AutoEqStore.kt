package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.SoundGenre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

/**
 * What the automatic equalizer worked out for one track. [curveDb] is per device band; [adapted] says the
 * song's own spectrum has already refined it, which is what makes the entry worth keeping rather than
 * recomputing.
 */
@Serializable
data class StoredAutoEq(
    val genre: SoundGenre,
    val label: String? = null,
    val curveDb: List<Float> = emptyList(),
    val adapted: Boolean = false,
    val bandCount: Int = 0,
    /** Stamped by [AutoEqStore.put] — the store owns the clock, so callers leave it empty. */
    val computedAtIso: String = "",
)

/**
 * Remembers the curve each song got, so the **second** play is instant.
 *
 * This is what makes the feature's cost acceptable. The first play of a song pays for everything: a genre
 * lookup over the network, then twelve seconds of listening before the curve is refined. None of that
 * changes for that recording ever again, so it is written down — after which the same song is equalized
 * the moment it starts, offline included.
 *
 * One small JSON blob, atomic temp-then-rename, `Mutex`-serialized, every operation `runCatching`-guarded:
 * the same idiom as [LyricsStore] and [PlaybackSessionStore], and for the same reason — a corrupt or
 * missing file must degrade to "nothing cached", never break playback.
 *
 * Keyed by `ProviderRef.identityKey`. [bandCount] is stored alongside because a curve is expressed in
 * *this device's* bands: an entry written for a five-band phone means nothing on a ten-band one, and
 * reading it back would silently apply the wrong frequencies.
 */
class AutoEqStore(
    private val file: File,
    private val now: () -> Instant = { Instant.now() },
) {

    private val json get() = TrackJson.json
    private val lock = Mutex()

    /** Loaded on first access and kept in memory — this is read on every track change. */
    private var entries: MutableMap<String, StoredAutoEq>? = null

    /** The stored decision for [key], or null when there is none for a device with [bandCount] bands. */
    suspend fun get(key: String, bandCount: Int): StoredAutoEq? = withContext(Dispatchers.IO) {
        lock.withLock { loaded()[key] }?.takeIf { it.bandCount == bandCount && it.curveDb.size == bandCount }
    }

    suspend fun put(key: String, entry: StoredAutoEq) = mutate { map ->
        map[key] = entry.copy(computedAtIso = now().toString())
    }

    suspend fun clear() = mutate { it.clear() }

    // ---- Internals ----

    private suspend fun mutate(block: (MutableMap<String, StoredAutoEq>) -> Unit) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                val map = loaded()
                block(map)
                evict(map)
                persist(map)
            }
        }
    }

    private fun loaded(): MutableMap<String, StoredAutoEq> = entries ?: read().also { entries = it }

    private fun read(): MutableMap<String, StoredAutoEq> = runCatching {
        if (!file.exists()) return@runCatching mutableMapOf()
        json.decodeFromString(PersistedAutoEq.serializer(), file.readText())
            .entries
            .associate { it.key to it.value }
            .toMutableMap()
    }.getOrDefault(mutableMapOf())

    /** Oldest first — a curve is cheap to recompute, so the cap can be blunt. */
    private fun evict(map: MutableMap<String, StoredAutoEq>) {
        if (map.size <= MAX_ENTRIES) return
        map.entries
            .sortedBy { it.value.computedAtIso }
            .take(map.size - MAX_ENTRIES)
            .forEach { map.remove(it.key) }
    }

    private fun persist(map: Map<String, StoredAutoEq>) {
        runCatching {
            if (map.isEmpty()) {
                file.delete()
                return@runCatching
            }
            val text = json.encodeToString(
                PersistedAutoEq.serializer(),
                PersistedAutoEq(map.map { PersistedAutoEqEntry(it.key, it.value) }),
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
        /** A few hundred bytes per song, so this covers a very large listening history for almost nothing. */
        const val MAX_ENTRIES = 1_000
    }
}

// ---- On-disk shape (private) ----

@Serializable
private data class PersistedAutoEq(val entries: List<PersistedAutoEqEntry>)

@Serializable
private data class PersistedAutoEqEntry(val key: String, val value: StoredAutoEq)
