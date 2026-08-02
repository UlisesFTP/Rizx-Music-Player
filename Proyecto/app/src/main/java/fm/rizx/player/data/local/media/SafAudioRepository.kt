package fm.rizx.player.data.local.media

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import fm.rizx.player.data.local.store.OpenedFilesStore
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.codecForMime
import fm.rizx.player.domain.model.containerForMime
import fm.rizx.player.domain.repository.OpenedFilesRepository
import fm.rizx.player.domain.repository.OpenedFolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * SAF-backed audio: resolves picked documents into [Track]s, remembers them, and streams them back.
 *
 * The division of honesty: tags come from [MediaMetadataRetriever] (the same framework the player
 * decodes with), the fallback title is the file's own name, and the embedded cover — the one image a
 * bare document can offer — is copied once into `cacheDir/opened-art/` so Coil can load it as a file.
 * `cacheDir` on purpose: it is a *cache* of bytes recoverable from the document, so the OS reclaiming
 * it under pressure costs a placeholder, not data.
 *
 * Grants: a persistable read grant is taken per opened document and **released when the entry is pruned
 * or forgotten** — the store's cap is what keeps the app safely under the system's per-app grant limit.
 */
class SafAudioRepository(
    private val context: Context,
    private val store: OpenedFilesStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : OpenedFilesRepository {

    private val _recent = MutableStateFlow(store.load().map { it.track })
    override val recent: StateFlow<List<Track>> = _recent.asStateFlow()

    /** identityKey (== uri) → mime, mirrored from the store so [streamFor] stays synchronous. */
    @Volatile
    private var mimeByUri: Map<String, String?> = store.load().associate { it.track.source.id to it.mimeType }

    override suspend fun openFiles(uris: List<String>): List<Track> = withContext(io) {
        val entries = uris.mapNotNull { resolveOne(it) }
        remember(entries)
        entries.map { it.track }
    }

    override suspend fun openFolder(treeUri: String): OpenedFolder = withContext(io) {
        val tree = Uri.parse(treeUri)
        takeGrant(tree)
        val found = ArrayList<FolderAudioEntry>()
        runCatching { walk(tree, DocumentsContract.getTreeDocumentId(tree), depth = 0, found) }
        val plan = planFolderQueue(found)
        val entries = plan.entries.mapNotNull { resolveOne(it.uri, knownName = it.displayName, knownMime = it.mimeType) }
        remember(entries)
        OpenedFolder(
            tracks = entries.map { it.track },
            name = folderName(tree),
            skipped = plan.skipped,
        )
    }

    override fun streamFor(track: Track): Stream? {
        if (track.source.provider != LocalIds.FILE_PROVIDER) return null
        val mime = mimeByUri[track.source.id]
        return Stream(
            url = track.source.id, // the document uri IS the id — nothing resolved, nothing ephemeral
            protocol = StreamProtocol.FILE,
            mimeType = mime,
            codec = codecForMime(mime),
            container = containerForMime(mime),
            durationMs = track.durationMs,
            source = track.source,
        )
    }

    override suspend fun forget(track: Track) {
        store.remove(track.source.identityKey)
        releaseGrant(track.source.id)
        _recent.value = _recent.value.filterNot { it.source.identityKey == track.source.identityKey }
    }

    // ---- resolution ----

    /** One document → a store entry, or null when it can't even be probed (skipped, never fatal). */
    private fun resolveOne(
        uriString: String,
        knownName: String? = null,
        knownMime: String? = null,
    ): OpenedFilesStore.Entry? = runCatching {
        val uri = Uri.parse(uriString)
        takeGrant(uri)
        val (displayName, queriedMime) = documentNameAndMime(uri)
        val retriever = MediaMetadataRetriever()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs: Long? = null
        var art: ByteArray? = null
        try {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            art = retriever.embeddedPicture
        } catch (_: Exception) {
            // Unreadable by the framework: the row still exists, titled by its file name — and if it
            // won't play either, playback reports that the honest way.
        } finally {
            runCatching { retriever.release() }
        }
        OpenedFilesStore.Entry(
            track = fileTrack(
                uri = uriString,
                displayName = knownName ?: displayName,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                artworkPath = art?.let { cacheArt(uriString, it) },
            ),
            mimeType = knownMime ?: queriedMime,
            openedAtIso = Instant.now().toString(),
        )
    }.getOrNull()

    private suspend fun remember(entries: List<OpenedFilesStore.Entry>) {
        if (entries.isEmpty()) return
        val pruned = store.upsert(entries)
        pruned.forEach { releaseGrant(it.track.source.id) }
        val fresh = store.load()
        mimeByUri = fresh.associate { it.track.source.id to it.mimeType }
        _recent.value = fresh.map { it.track }
    }

    /** display name + mime from the document row; either may be absent on exotic providers. */
    private fun documentNameAndMime(uri: Uri): Pair<String?, String?> = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        }
    }.getOrNull() ?: (null to null)

    /** Recursive child walk via DocumentsContract — depth-capped; the planner applies the size cap. */
    private fun walk(tree: Uri, documentId: String, depth: Int, out: MutableList<FolderAudioEntry>) {
        if (depth > MAX_FOLDER_DEPTH || out.size >= HARD_WALK_CAP) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext() && out.size < HARD_WALK_CAP) {
                val childId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(tree, childId, depth + 1, out)
                } else {
                    out += FolderAudioEntry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId).toString(),
                        displayName = name,
                        mimeType = mime,
                    )
                }
            }
        }
    }

    private fun folderName(tree: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/') }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "Folder"

    /** Copies the embedded picture once; keyed by uri hash so a re-open reuses it. */
    private fun cacheArt(uriString: String, bytes: ByteArray): String? = runCatching {
        val dir = File(context.cacheDir, ART_DIR).apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-256").digest(uriString.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        val file = File(dir, "$name.jpg")
        if (!file.exists()) file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()

    // ---- grants ----

    private fun takeGrant(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } // documents from providers that don't offer persistable grants still play this session
    }

    private fun releaseGrant(uriString: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString), Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private companion object {
        const val ART_DIR = "opened-art"
        const val MAX_FOLDER_DEPTH = 3

        /** Absolute stop for the walk itself, above the queue cap — a guard, not a feature. */
        const val HARD_WALK_CAP = 2_000
    }
}
