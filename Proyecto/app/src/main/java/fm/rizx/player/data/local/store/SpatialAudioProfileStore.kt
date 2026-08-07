package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.SpatialAnalysis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Remembers what each song *is*, so the second listen starts where the first one finished instead of
 * spending sixteen seconds measuring the same record again.
 *
 * **It stores the measurement, not the finished profile.** Storing the profile is the obvious choice and
 * it quietly breaks the intensity control: a song heard once on "adaptive" would keep sounding adaptive
 * for ever, because its cached answer already had that multiplier baked in. Keeping the genre and the
 * measurement — the facts — lets the profile be recomputed against whatever the listener has chosen now.
 *
 * A separate file from the automatic equaliser's store: different schemas, different reasons to be
 * invalidated, and sharing one would mean a change to either throwing away the other's work.
 *
 * **Nothing here is load-bearing.** Every read and write is best-effort; a corrupt file, a full disk or
 * a schema from a future build all end at "not cached", which costs one measurement and breaks nothing.
 */
class SpatialAudioProfileStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowIso: () -> String = { Instant.now().toString() },
) {

    /** What was learned about one recording. */
    data class Cached(val genre: String?, val analysis: SpatialAnalysis?)

    @Serializable
    private data class Entry(
        val schemaVersion: Int = SCHEMA_VERSION,
        val genre: String? = null,
        val analysis: SpatialAnalysis? = null,
        val computedAtIso: String = "",
    )

    @Serializable
    private data class Persisted(val entries: Map<String, Entry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    /** @param key `track.source.identityKey`. */
    suspend fun get(key: String): Cached? = withContext(io) {
        mutex.withLock { read()[key] }
            ?.takeIf { it.schemaVersion == SCHEMA_VERSION }
            ?.let { Cached(it.genre, it.analysis) }
    }

    suspend fun put(key: String, genre: String?, analysis: SpatialAnalysis?) {
        withContext(io) {
            mutex.withLock {
                val entries = read().toMutableMap()
                entries[key] = Entry(genre = genre, analysis = analysis, computedAtIso = nowIso())
                // Oldest first out. The map keeps insertion order, which is close enough to recency for
                // a cache whose only cost of being wrong is one re-measurement.
                val trimmed = if (entries.size <= MAX_ENTRIES) {
                    entries
                } else {
                    entries.entries.drop(entries.size - MAX_ENTRIES).associate { it.key to it.value }
                }
                write(trimmed)
            }
        }
    }

    private fun read(): Map<String, Entry> = runCatching {
        if (!file.isFile) return emptyMap()
        json.decodeFromString(Persisted.serializer(), file.readText()).entries
    }.getOrDefault(emptyMap())

    private fun write(entries: Map<String, Entry>) {
        runCatching {
            file.parentFile?.mkdirs()
            // Temp-then-rename, so a process death mid-write leaves the previous cache intact rather
            // than a half-written file that fails to parse for ever after.
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(Persisted.serializer(), Persisted(entries)))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    private companion object {
        /** Bump when a measurement's meaning changes; every older entry is then ignored. */
        const val SCHEMA_VERSION = 1
        const val MAX_ENTRIES = 400
    }
}
