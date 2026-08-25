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
 * The unlisted link that was last created for one playlist.
 *
 * Holds no snapshot and no token secret beyond the URL the user already copied: the server keeps only a
 * hash of the token, so this file *is* the only place the app can look up its own link again.
 */
@Serializable
data class StoredPlaylistShare(
    val id: String = "",
    val url: String = "",
    val expiresAtIso: String = "",
    val createdAtIso: String? = null,
) {
    /** Unparseable expiry counts as expired: a link we can't date is one we shouldn't promise. */
    fun isLiveAt(now: Instant): Boolean =
        runCatching { Instant.parse(expiresAtIso).isAfter(now) }.getOrDefault(false)
}

/**
 * Remembers which share link belongs to which playlist.
 *
 * The server can't answer this. `rizx_playlist_shares` stores an anonymous snapshot plus a token hash —
 * deliberately, so a snapshot can't be traced back to a library — which means `list` returns shares with
 * no playlist attached. Without this file the app forgets its own links the moment the screen closes, and
 * the "revoke from this installation" promise in Settings has nothing to revoke.
 *
 * Same idiom as [ArtistBioStore] / [LyricsStore]: one JSON blob in `filesDir`, atomic temp-then-rename,
 * `Mutex`-serialized, every operation `runCatching`-guarded, so a corrupt file degrades to "no links
 * remembered" instead of breaking the playlist screen. Keyed by playlist id.
 */
class PlaylistShareStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val json get() = TrackJson.json
    private val lock = Mutex()
    private var entries: MutableMap<String, StoredPlaylistShare>? = null

    suspend fun get(playlistId: String): StoredPlaylistShare? =
        withContext(io) { lock.withLock { loaded()[playlistId] } }

    suspend fun put(playlistId: String, share: StoredPlaylistShare) = mutate { it[playlistId] = share }

    suspend fun remove(playlistId: String) = mutate { it.remove(playlistId) }

    /** Revocation identifies the *share*, not the playlist, so forgetting has to work by value too. */
    suspend fun removeByShareId(shareId: String) = mutate { map ->
        map.entries.filter { it.value.id == shareId }.forEach { map.remove(it.key) }
    }

    // ---- Internals ----

    private suspend fun mutate(block: (MutableMap<String, StoredPlaylistShare>) -> Unit) {
        withContext(io) {
            lock.withLock {
                val map = loaded()
                block(map)
                persist(map)
            }
        }
    }

    private fun loaded(): MutableMap<String, StoredPlaylistShare> = entries ?: read().also { entries = it }

    private fun read(): MutableMap<String, StoredPlaylistShare> = runCatching {
        if (!file.exists()) return@runCatching mutableMapOf()
        json.decodeFromString(PersistedShares.serializer(), file.readText())
            .entries
            .associate { it.key to it.value }
            .toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun persist(map: Map<String, StoredPlaylistShare>) {
        runCatching {
            if (map.isEmpty()) {
                file.delete()
                return@runCatching
            }
            val text = json.encodeToString(
                PersistedShares.serializer(),
                PersistedShares(map.map { PersistedShare(it.key, it.value) }),
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

// ---- On-disk shape (private) ----

@Serializable
private data class PersistedShares(val entries: List<PersistedShare>)

@Serializable
private data class PersistedShare(val key: String, val value: StoredPlaylistShare)
