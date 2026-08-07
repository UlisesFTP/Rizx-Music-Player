package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/**
 * An 8D MP3 rendered from a song — a **separate file from that song's download**, deliberately.
 *
 * Folding this into [DownloadFormat] was the other option and it was the wrong one: the offline index
 * derives its key from `track.source.identityKey`, so one song can hold exactly one entry, and an 8D
 * copy would have had to evict the ordinary download or share its row. Both mean choosing 8D quietly
 * changes what "downloaded" plays. A render is a different thing with a different purpose — something
 * to take to a car stereo or another player — so it lives in its own directory and its own index and
 * nothing about offline playback can notice it.
 */
@Serializable
data class SpatialRender(
    val track: Track,
    val fileName: String,
    val sizeBytes: Long,
    /** The profile's own label, e.g. `pop+measured` — what the render was made with, for the record. */
    val profileLabel: String,
    val renderedAtIso: String,
    /** Where it landed in the phone's Music folder, once exported. */
    val exportedUri: String? = null,
)

/** What a render is doing right now. Absent from the map when it is not running. */
data class SpatialRenderState(
    val status: SpatialRenderStatus,
    val progressPercent: Int = 0,
    val error: String? = null,
)

enum class SpatialRenderStatus {
    /** Pulling the source audio down. */
    FETCHING,

    /** Decoding, spatializing and encoding — the long half. */
    RENDERING,
    COMPLETE,
    FAILED,
}
