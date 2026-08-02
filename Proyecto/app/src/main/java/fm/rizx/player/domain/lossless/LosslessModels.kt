package fm.rizx.player.domain.lossless

import fm.rizx.player.domain.model.AudioProvenance

/**
 * One row of a community lossless index.
 *
 * **Only the first three fields are real in practice.** The index shape this was built against carries
 * `song`, `artist` and `url` and nothing else — no album, no duration, no ISRC, no checksum. The rest are
 * modelled because a richer index (someone's own server, a curated list) can supply them and the matcher
 * scores them when they are there; with the minimal shape they simply never fire.
 *
 * That absence is what forces the two-stage match in `DefaultLosslessMatcher`: with no duration in the
 * row, the only way to tell this song from another recording of the same name is to read the duration out
 * of the file itself.
 */
data class LosslessIndexItem(
    val song: String,
    val artist: String,
    val url: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
    val sha256: String? = null,
    val license: String? = null,
    val sourceName: String? = null,
)

/**
 * Why a candidate scored what it did.
 *
 * Kept alongside the score rather than folded into it so a rejection can be *explained* — in the
 * diagnostics, in a test failure, and in the log line when a track that plainly exists in the index
 * still isn't used. `null` means "neither side offered this signal", which is different from "they
 * disagreed".
 */
data class LosslessMatchEvidence(
    val titleMatched: Boolean,
    val artistMatched: Boolean,
    val albumMatched: Boolean? = null,
    val durationMatched: Boolean? = null,
    val isrcMatched: Boolean? = null,
    val directIdentity: Boolean = false,
    val reasons: List<String> = emptyList(),
)

/** An index row that survived the metadata stage, with the score that got it there. */
data class LosslessCandidate(
    val item: LosslessIndexItem,
    val matchScore: Int,
    val evidence: LosslessMatchEvidence,
)

/**
 * What the file's own STREAMINFO block says about it — read from the first 64 KiB, never guessed.
 *
 * This is the entire technical readout the UI is allowed to show, because it is the only part that was
 * measured. [effectiveBitrateKbps] is computed from the real byte length over the real duration; the
 * 1411 kbps that gets quoted for "CD quality" is a property of uncompressed PCM and says nothing about
 * a FLAC.
 */
data class FlacStreamInfo(
    val sampleRateHz: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val totalSamples: Long,
    val durationMs: Long,
    val streamInfoMd5Hex: String? = null,
    val contentLength: Long? = null,
    val effectiveBitrateKbps: Int? = null,
)

/**
 * A candidate that passed **both** stages: its metadata matched and its decoded header agrees.
 *
 * [url] is a resolved stream URL and is therefore ephemeral — it lives in the in-memory resolution cache
 * and never in a playlist, a download index or a `Track`.
 */
data class ValidatedLosslessStream(
    val candidate: LosslessCandidate,
    val url: String,
    val info: FlacStreamInfo,
    val mimeType: String = "audio/flac",
    /** Always [AudioProvenance.COMMUNITY_UNVERIFIED] for an index source; the container was checked, not the origin. */
    val provenance: AudioProvenance = AudioProvenance.COMMUNITY_UNVERIFIED,
)
