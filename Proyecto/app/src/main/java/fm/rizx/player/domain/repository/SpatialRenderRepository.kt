package fm.rizx.player.domain.repository

import fm.rizx.player.domain.model.SpatialRender
import fm.rizx.player.domain.model.SpatialRenderState
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Renders songs to standalone **8D MP3 files** — the offline half of the spatializer.
 *
 * Separate from `DownloadRepository` on purpose, and not a [fm.rizx.player.domain.model.DownloadFormat]
 * value: a render is a second, different file for the same song, and the download index cannot hold two
 * rows for one track. Nothing here touches offline playback, which keeps playing whatever the ordinary
 * download holds.
 */
interface SpatialRenderRepository {

    /** Finished renders, keyed by `ProviderRef.identityKey`. */
    val renders: StateFlow<Map<String, SpatialRender>>

    /** In-flight work, keyed the same way. Absent once finished. */
    val states: StateFlow<Map<String, SpatialRenderState>>

    /** Starts a render. A song that already has one is left alone. */
    fun render(track: Track)

    fun cancel(key: String)

    /** Removes the file and its entry. The song's ordinary download is untouched. */
    suspend fun delete(key: String)
}
