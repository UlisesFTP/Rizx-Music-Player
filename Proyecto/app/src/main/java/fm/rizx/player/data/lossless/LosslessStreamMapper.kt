package fm.rizx.player.data.lossless

import fm.rizx.player.domain.lossless.ValidatedLosslessStream
import fm.rizx.player.domain.model.AudioFormatUi
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamProtocol

/** The provider id a community-lossless stream is attributed to, in diagnostics and in the readout. */
const val COMMUNITY_LOSSLESS_PROVIDER = "community-lossless"

/**
 * [Stream.codec] for a verified FLAC.
 *
 * A constant rather than a literal because three places have to agree on it: the mapper that sets it,
 * the player's readout that decides "this is lossless" from it, and the service's mid-song fallback
 * that recognises which kind of stream just died.
 */
const val FLAC_CODEC = "FLAC"

/**
 * A verified FLAC as a playable [Stream].
 *
 * Every technical field here was **read from the file**, not inferred: the sample rate, depth and
 * channel count come out of STREAMINFO and the bitrate out of the real byte length over the real
 * duration. That is what lets the player's readout claim them.
 *
 * [ProviderRef.id] is [trackIdentityKey] and its `url` is deliberately null. A `ProviderRef` is the
 * canonical, persistable identity of a piece of content, and the URL behind a lossless stream is
 * ephemeral in exactly the way this project forbids persisting — so it travels in [Stream.url], which
 * is documented as throwaway, and nowhere else.
 */
/**
 * A resolved stream as the readout under the player.
 *
 * Straight field copies, no inference: whatever the provider didn't report stays null and simply doesn't
 * appear on the line. A compressed stream from a search provider contributes a codec and a bitrate; a
 * verified FLAC contributes everything, because its header was read.
 */
fun Stream.toAudioFormatUi(): AudioFormatUi = AudioFormatUi(
    codec = codec,
    container = container,
    bitrateKbps = bitrateKbps,
    sampleRateHz = sampleRateHz,
    bitsPerSample = bitsPerSample,
    channels = channels,
    provenance = provenance,
)

fun ValidatedLosslessStream.toStream(trackIdentityKey: String): Stream = Stream(
    url = url,
    protocol = StreamProtocol.HTTPS,
    mimeType = mimeType,
    bitrateKbps = info.effectiveBitrateKbps,
    codec = FLAC_CODEC,
    container = "flac",
    durationMs = info.durationMs,
    contentLengthBytes = info.contentLength,
    sampleRateHz = info.sampleRateHz,
    bitsPerSample = info.bitsPerSample,
    channels = info.channels,
    provenance = provenance,
    source = ProviderRef(provider = COMMUNITY_LOSSLESS_PROVIDER, id = trackIdentityKey, url = null),
)
