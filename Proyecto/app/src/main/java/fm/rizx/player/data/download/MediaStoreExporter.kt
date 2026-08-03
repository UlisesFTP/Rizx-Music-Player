package fm.rizx.player.data.download

import fm.rizx.player.domain.model.DownloadedTrack
import java.io.File

/** Where an export landed. [displayName] is what a foreign player will actually show. */
data class ExportedFile(val uri: String, val displayName: String)

/**
 * Copies a download into the shared `Music/Rizx` folder so other players can see it.
 *
 * An interface so the repository stays JVM-testable — the real implementation needs a `ContentResolver`.
 */
interface MediaStoreExporter {
    suspend fun export(entry: DownloadedTrack, file: File): Result<ExportedFile>

    /**
     * Whether a copy exported earlier is still there. The user owns that file once it lands in their
     * Music folder and may well have deleted or moved it, and MediaStore answers a second insert of the
     * same name with `Song (1).opus` rather than an error — so "is it still there?" is the only way to
     * tell re-publishing from duplicating.
     */
    suspend fun exists(uri: String): Boolean = false
}
