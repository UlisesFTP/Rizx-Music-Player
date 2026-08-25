package fm.rizx.player.data.local.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The one cross-account fact sync needs: which account this install last synced with.
 *
 * Not in `sync_state` — that table is keyed by account, and the question is asked *before* the new
 * account has a row. A plain JSON file, same idiom as [PlaylistShareStore].
 */
class SyncPrefsStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Serializable
    private data class Prefs(val lastAccountId: String? = null)

    private val json get() = TrackJson.json
    private val lock = Mutex()
    private var cached: Prefs? = null

    suspend fun lastAccountId(): String? = withContext(io) { lock.withLock { loaded().lastAccountId } }

    suspend fun setLastAccountId(accountId: String?) = withContext(io) {
        lock.withLock {
            val next = loaded().copy(lastAccountId = accountId)
            cached = next
            runCatching {
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.encodeToString(Prefs.serializer(), next))
                if (!tmp.renameTo(file)) {
                    file.writeText(json.encodeToString(Prefs.serializer(), next))
                    tmp.delete()
                }
            }
        }
    }

    private fun loaded(): Prefs = cached ?: runCatching {
        if (file.exists()) json.decodeFromString(Prefs.serializer(), file.readText()) else Prefs()
    }.getOrDefault(Prefs()).also { cached = it }
}
