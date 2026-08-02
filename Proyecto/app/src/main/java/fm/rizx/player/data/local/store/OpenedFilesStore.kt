package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.stripResolutionState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Persists the "recently opened" files list — audio the user picked through the system file explorer.
 *
 * Same idiom as [DownloadIndexStore]: one small JSON blob, atomic temp-then-rename, every read
 * `runCatching`-guarded so a corrupt file degrades to an empty list and never breaks the screen.
 *
 * Two rules with reasons:
 * - **Capped at [MAX_ENTRIES]**, newest first — this is a recents list, not a second library. The
 *   caller gets the pruned entries back from [upsert] so it can release their read grants; the system
 *   caps persisted grants (512 per app), and a list that only ever grows would eventually eat them all.
 * - Tracks are stored through [stripResolutionState] — the document URI in `source.id` is identity
 *   (SAF's stable name for the document), but a resolved stream URL would be a rule violation.
 */
class OpenedFilesStore(private val file: File) {

    private val json get() = TrackJson.json
    private val writeLock = Mutex()

    @Serializable
    data class Entry(val track: Track, val mimeType: String? = null, val openedAtIso: String)

    @Serializable
    private data class Persisted(val entries: List<Entry> = emptyList())

    /** Newest first. Missing or unreadable file → empty. */
    fun load(): List<Entry> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(Persisted.serializer(), file.readText()).entries
    }.getOrDefault(emptyList())

    /**
     * Inserts or refreshes [entries] at the top (re-opening a file moves it up rather than duplicating),
     * prunes to [MAX_ENTRIES], persists, and returns what was pruned — whose grants the caller releases.
     */
    suspend fun upsert(entries: List<Entry>): List<Entry> = writeLock.withLock {
        val incoming = entries.map { it.copy(track = it.track.stripResolutionState()) }
        val incomingKeys = incoming.map { it.track.source.identityKey }.toSet()
        val merged = incoming + load().filterNot { it.track.source.identityKey in incomingKeys }
        val kept = merged.take(MAX_ENTRIES)
        persist(kept)
        merged.drop(MAX_ENTRIES)
    }

    /** Removes one entry by its track identity. */
    suspend fun remove(identityKey: String): Unit = writeLock.withLock {
        persist(load().filterNot { it.track.source.identityKey == identityKey })
    }

    private fun persist(entries: List<Entry>) {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Persisted.serializer(), Persisted(entries)))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 50
    }
}
