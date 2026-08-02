package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

/** What opening a folder produced: its playable tracks, the folder's name, and how many were left out. */
data class OpenedFolder(val tracks: List<Track>, val name: String, val skipped: Int)

/**
 * Audio opened through the system file picker — the door for everything `MediaStore` doesn't index:
 * an SD card, a USB stick, another app's downloads folder.
 *
 * These are ordinary [Track]s with `source = ProviderRef("file", "<document uri>")`. The document URI is
 * the *identity* of the file (SAF's stable name for it), not a resolved stream URL — persisting it is
 * exactly as legitimate as persisting a MediaStore `_ID`. What makes it durable is the persistable read
 * grant the implementation takes when a document is opened and releases when it is forgotten.
 *
 * URIs cross this boundary as [String]s: the domain has no Android `Uri`.
 */
interface OpenedFilesRepository {

    /** Files opened before, newest first — the "recently opened" list. Loaded once, then kept live. */
    val recent: StateFlow<List<Track>>

    /**
     * Resolves picked document [uris] into playable tracks (tags via the media framework; an untagged
     * file is titled by its file name), remembers them in [recent], and keeps their read grants.
     * A document that can't be read at all is skipped rather than failing the batch.
     */
    suspend fun openFiles(uris: List<String>): List<Track>

    /**
     * Walks a picked folder (recursively, within a documented depth and size cap) and resolves its audio
     * into a play order. The folder itself is remembered via its files; [OpenedFolder.skipped] says
     * honestly how many fell over the cap.
     */
    suspend fun openFolder(treeUri: String): OpenedFolder

    /**
     * The playable [Stream] for a previously opened file-track, else null. Synchronous and non-blocking —
     * the ExoPlayer loader thread calls this through the same seam as MediaStore tracks.
     */
    fun streamFor(track: Track): Stream?

    /** Drops one remembered file (and its grant). The document itself is untouched. */
    suspend fun forget(track: Track)
}
