package fm.rizx.player.data.local.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private data class StoredSearches(val queries: List<String> = emptyList())

/**
 * The searches the user actually meant.
 *
 * Only **deliberate** searches land here — a submit, a tapped suggestion, a tapped pill or genre — never
 * the debounced query behind every keystroke. Otherwise the history would fill with "the wee", "the week",
 * "the weekn" and the one entry worth keeping would be buried under its own prefixes.
 *
 * One JSON blob written atomically (temp-then-rename), `Mutex`-serialized, every op `runCatching`-guarded:
 * the same idiom as [LyricsStore] / [HomeFeedStore] / [PlaybackSessionStore], and for the same reason — a
 * corrupt or missing file must degrade to "no history", never break the screen.
 *
 * [queries] is **hot**: a search just made appears in the pills without anything re-reading the file.
 */
class SearchHistoryStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val json get() = TrackJson.json
    private val lock = Mutex()
    private val _queries = MutableStateFlow<List<String>>(emptyList())
    private var loaded = false

    /** Newest first, capped at [MAX_ENTRIES]. Reads the file on the first collection, then stays live. */
    fun queries(): Flow<List<String>> = _queries.onStart { ensureLoaded() }

    /**
     * Records [raw] as the newest search, moving it up if it was already there.
     *
     * Case-insensitive dedup, because "the weeknd" and "The Weeknd" are one search — the *newest* spelling
     * wins, so the list shows what the user last typed rather than an older capitalization of it.
     */
    suspend fun remember(raw: String) {
        val query = raw.trim()
        if (query.length < MIN_LENGTH) return
        ensureLoaded()
        val next = lock.withLock {
            val kept = _queries.value.filterNot { it.equals(query, ignoreCase = true) }
            (listOf(query) + kept).take(MAX_ENTRIES).also { _queries.value = it }
        }
        withContext(io) { runCatching { write(next) } }
    }

    suspend fun clear() {
        lock.withLock { _queries.value = emptyList(); loaded = true }
        withContext(io) { runCatching { file.delete() } }
    }

    /**
     * Not inside the write lock's critical section by accident: [remember] takes the same lock right after,
     * and a Kotlin `Mutex` is **not** reentrant — holding it across both would deadlock the first search
     * the app ever makes.
     */
    private suspend fun ensureLoaded() {
        lock.withLock {
            if (loaded) return
            loaded = true
            _queries.value = withContext(io) {
                runCatching {
                    if (!file.exists()) return@runCatching emptyList()
                    json.decodeFromString(StoredSearches.serializer(), file.readText()).queries
                }.getOrDefault(emptyList())
            }
        }
    }

    private fun write(queries: List<String>) {
        val text = json.encodeToString(StoredSearches.serializer(), StoredSearches(queries))
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text) // fall back to a direct write if the atomic rename fails
            tmp.delete()
        }
    }

    private companion object {
        /** Enough to fill the pills and still have something left for the suggestion rows. */
        const val MAX_ENTRIES = 12

        /** A single character is a slip, not a search. */
        const val MIN_LENGTH = 2
    }
}
