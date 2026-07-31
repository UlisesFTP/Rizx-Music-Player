package fm.rizx.player.data.local.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

/**
 * One artist's biography as it was last looked up. A blank [text] is a **recorded absence** — the
 * artist has no usable page — which is worth storing precisely because it costs two requests to find
 * out each time.
 */
@Serializable
data class StoredArtistBio(
    val text: String = "",
    /** The article, for the attribution link Wikipedia's licence requires. */
    val url: String? = null,
    val lang: String = "",
    /** Stamped by [ArtistBioStore.put] — the store owns the clock. */
    val checkedAtIso: String = "",
) {
    val found: Boolean get() = text.isNotBlank()
}

/**
 * Remembers what was found for each artist, so opening a page twice costs nothing and works offline.
 *
 * Same idiom as [AutoEqStore] / [LyricsStore]: one JSON blob in `filesDir`, atomic temp-then-rename,
 * `Mutex`-serialized, every operation `runCatching`-guarded — a corrupt or missing file degrades to
 * "nothing cached" and never breaks the screen. Keyed by `ProviderRef.identityKey`.
 */
class ArtistBioStore(
    private val file: File,
    private val now: () -> Instant = { Instant.now() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val json get() = TrackJson.json
    private val lock = Mutex()
    private var entries: MutableMap<String, StoredArtistBio>? = null

    suspend fun get(key: String): StoredArtistBio? = withContext(io) { lock.withLock { loaded()[key] } }

    suspend fun put(key: String, entry: StoredArtistBio) = mutate { map ->
        map[key] = entry.copy(checkedAtIso = now().toString())
    }

    suspend fun clear() = mutate { it.clear() }

    // ---- Internals ----

    private suspend fun mutate(block: (MutableMap<String, StoredArtistBio>) -> Unit) {
        withContext(io) {
            lock.withLock {
                val map = loaded()
                block(map)
                evict(map)
                persist(map)
            }
        }
    }

    private fun loaded(): MutableMap<String, StoredArtistBio> = entries ?: read().also { entries = it }

    private fun read(): MutableMap<String, StoredArtistBio> = runCatching {
        if (!file.exists()) return@runCatching mutableMapOf()
        json.decodeFromString(PersistedBios.serializer(), file.readText())
            .entries
            .associate { it.key to it.value }
            .toMutableMap()
    }.getOrDefault(mutableMapOf())

    /** Oldest first: a biography is cheap to fetch again, so the cap can be blunt. */
    private fun evict(map: MutableMap<String, StoredArtistBio>) {
        if (map.size <= MAX_ENTRIES) return
        map.entries
            .sortedBy { it.value.checkedAtIso }
            .take(map.size - MAX_ENTRIES)
            .forEach { map.remove(it.key) }
    }

    private fun persist(map: Map<String, StoredArtistBio>) {
        runCatching {
            if (map.isEmpty()) {
                file.delete()
                return@runCatching
            }
            val text = json.encodeToString(
                PersistedBios.serializer(),
                PersistedBios(map.map { PersistedBio(it.key, it.value) }),
            )
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 300
    }
}

// ---- On-disk shape (private) ----

@Serializable
private data class PersistedBios(val entries: List<PersistedBio>)

@Serializable
private data class PersistedBio(val key: String, val value: StoredArtistBio)
