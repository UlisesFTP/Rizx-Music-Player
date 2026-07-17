package fm.rizx.player.domain.model

import kotlinx.serialization.Serializable

/** Local-file playback info. [fileUri] is the only required field. */
@Serializable
data class LocalFileInfo(
    val fileUri: String,
    val fileSize: Long? = null,
    val format: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val fingerprint: String? = null,
    val scannedAtIso: String? = null,
)

/**
 * The central playable entity. Has **no `id`** — its identity *is* [source] (a [ProviderRef]).
 * Playable either via [localFile] or by resolving [streamCandidates] just-in-time.
 *
 * Notes that differ from naive assumptions (NUCLEAR_UPSTREAM_STUDY.md §2):
 * - [artists] are full [ArtistCredit]s (with roles), not plain strings.
 * - [album] is a lightweight [AlbumRef], not a full album.
 * - [durationMs] is milliseconds; [disc] is a **String**.
 * - [streamCandidates] is transient resolution state — strip it before persisting (see
 *   [stripResolutionState]) so ephemeral stream URLs are never stored.
 */
@Serializable
data class Track(
    val title: String,
    val artists: List<ArtistCredit> = emptyList(),
    val album: AlbumRef? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val disc: String? = null,
    val artwork: ArtworkSet? = null,
    val tags: List<String> = emptyList(),
    val source: ProviderRef,
    val localFile: LocalFileInfo? = null,
    val streamCandidates: List<StreamCandidate> = emptyList(),
)

/**
 * Returns a copy with transient resolution state ([Track.streamCandidates]) dropped. Use before
 * persisting/exporting so resolved (ephemeral) stream URLs are never stored as durable truth.
 */
fun Track.stripResolutionState(): Track = copy(streamCandidates = emptyList())
