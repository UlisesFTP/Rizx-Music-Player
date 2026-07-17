package fm.rizx.player.data.local.store

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.QueueContext
import fm.rizx.player.domain.model.QueueItem
import fm.rizx.player.domain.model.QueueItemStatus
import fm.rizx.player.domain.model.QueueSourceKind
import fm.rizx.player.domain.model.RepeatMode
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.stripResolutionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the current playback session — the queue, the cursor, the repeat mode, and the current
 * track's playback position — so the app **resumes on the same song at the same second** after the
 * process is killed and relaunched.
 *
 * Stored as one small JSON blob in `filesDir` (atomic temp-then-rename). Tracks are run through
 * [stripResolutionState] before encoding, so ephemeral resolved stream URLs are **never** written to
 * disk (AGENTS.md §7.3 / NUCLEAR_UPSTREAM_STUDY.md §7.3) — on restore each track is re-resolved
 * just-in-time by the streaming resolver. Every operation is crash-safe (`runCatching`): a missing or
 * corrupt file simply yields `null`, and a failed write is swallowed so it can never break playback.
 */
@Singleton
class PlaybackSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private val json get() = TrackJson.json
    private val writeLock = Mutex() // serialize writes so concurrent saves can't clash on the temp file

    /** The last persisted session, or `null` if none/unreadable. Empty queues are treated as absent. */
    suspend fun load(): PlaybackSessionSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val f = file
            if (!f.exists()) return@runCatching null
            json.decodeFromString(PersistedSession.serializer(), f.readText()).toSnapshot()
        }.getOrNull()?.takeIf { it.items.isNotEmpty() }
    }

    /** Writes [snapshot] atomically. An empty queue clears the stored session instead of writing it. */
    suspend fun save(snapshot: PlaybackSessionSnapshot) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                runCatching {
                    if (snapshot.items.isEmpty()) {
                        file.delete()
                        return@runCatching
                    }
                    val text = json.encodeToString(PersistedSession.serializer(), PersistedSession.from(snapshot))
                    val tmp = File(context.filesDir, "$FILE_NAME.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) {
                        file.writeText(text) // fall back to a direct write if the atomic rename fails
                        tmp.delete()
                    }
                }
            }
        }
    }

    /** Forgets the stored session (e.g. when the queue is cleared). */
    suspend fun clear() {
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
    }

    private companion object {
        const val FILE_NAME = "playback_session.json"
    }
}

/** A restorable snapshot of the playback session (queue + cursor + repeat mode + position + context). */
data class PlaybackSessionSnapshot(
    val items: List<QueueItem>,
    val currentIndex: Int,
    val repeatMode: RepeatMode,
    val positionMs: Long,
    val context: QueueContext = QueueContext(),
    val shuffleOn: Boolean = false,
    /** Carried with [items] because they are stored in shuffled order — without it the restored toggle
     *  would read "on" but have nothing to restore. */
    val unshuffledIds: List<String>? = null,
)

// ---- On-disk shape (private; enums stored by name for forward-compat) ----

@Serializable
private data class PersistedSession(
    val items: List<PersistedItem>,
    val currentIndex: Int,
    val repeatMode: String,
    val positionMs: Long,
    val context: PersistedContext = PersistedContext(),
    val shuffleOn: Boolean = false,
    val unshuffledIds: List<String>? = null,
) {
    fun toSnapshot() = PlaybackSessionSnapshot(
        items = items.map { it.toQueueItem() },
        currentIndex = currentIndex,
        repeatMode = runCatching { RepeatMode.valueOf(repeatMode) }.getOrDefault(RepeatMode.OFF),
        positionMs = positionMs.coerceAtLeast(0L),
        context = context.toContext(),
        shuffleOn = shuffleOn,
        unshuffledIds = unshuffledIds,
    )

    companion object {
        fun from(s: PlaybackSessionSnapshot) = PersistedSession(
            items = s.items.map { PersistedItem.from(it) },
            currentIndex = s.currentIndex,
            repeatMode = s.repeatMode.name,
            positionMs = s.positionMs.coerceAtLeast(0L),
            context = PersistedContext.from(s.context),
            shuffleOn = s.shuffleOn,
            unshuffledIds = s.unshuffledIds,
        )
    }
}

@Serializable
private data class PersistedContext(
    val kind: String = QueueSourceKind.MANUAL.name,
    val label: String = "",
    val radioSeed: ProviderRef? = null,
) {
    fun toContext() = QueueContext(
        kind = runCatching { QueueSourceKind.valueOf(kind) }.getOrDefault(QueueSourceKind.MANUAL),
        label = label,
        radioSeed = radioSeed,
    )

    companion object {
        fun from(c: QueueContext) = PersistedContext(kind = c.kind.name, label = c.label, radioSeed = c.radioSeed)
    }
}

@Serializable
private data class PersistedItem(
    val id: String,
    val track: Track,
    val addedAtIso: String,
) {
    // Restored items start IDLE — the stream is re-resolved just-in-time, never read from disk.
    fun toQueueItem() = QueueItem(id = id, track = track, status = QueueItemStatus.IDLE, addedAtIso = addedAtIso)

    companion object {
        fun from(item: QueueItem) = PersistedItem(
            id = item.id,
            track = item.track.stripResolutionState(), // drop ephemeral stream candidates before persisting
            addedAtIso = item.addedAtIso,
        )
    }
}
