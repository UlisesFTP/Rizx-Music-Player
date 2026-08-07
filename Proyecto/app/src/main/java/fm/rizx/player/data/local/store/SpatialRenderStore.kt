package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.SpatialRender
import fm.rizx.player.domain.model.stripResolutionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Persists which songs have an **8D render** on disk, in a file of its own.
 *
 * A separate store rather than a column on the download index, because [DownloadIndexStore] derives its
 * key from the track on read — one song, one row, by construction — so a render sharing it would have
 * to displace the ordinary download. Keeping them apart means nothing about offline playback can see a
 * render, and deleting either leaves the other alone.
 *
 * Same idiom as every other store here: atomic temp-then-rename, `Mutex`-serialized, every operation
 * `runCatching`-guarded so a corrupt file degrades to "nothing is rendered" instead of breaking a screen.
 */
class SpatialRenderStore(private val file: File) {

    private val json get() = TrackJson.json
    private val writeLock = Mutex()

    /** Keyed by `ProviderRef.identityKey`. Missing or unreadable file → empty. */
    suspend fun load(): Map<String, SpatialRender> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching emptyMap()
            json.decodeFromString(Persisted.serializer(), file.readText())
                .entries
                .associateBy { it.track.source.identityKey }
        }.getOrDefault(emptyMap())
    }

    /** Writes [index] atomically. An empty index deletes the file rather than writing `{}`. */
    suspend fun save(index: Map<String, SpatialRender>) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                runCatching {
                    if (index.isEmpty()) {
                        file.delete()
                        return@runCatching
                    }
                    // Resolved stream URLs are ephemeral and never reach disk; the durable half is the
                    // file name, which is a field of its own.
                    val text = json.encodeToString(
                        Persisted.serializer(),
                        Persisted(index.values.map { it.copy(track = it.track.stripResolutionState()) }),
                    )
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) {
                        file.writeText(text)
                        tmp.delete()
                    }
                }
            }
        }
    }

    @Serializable
    private data class Persisted(val entries: List<SpatialRender>)
}
