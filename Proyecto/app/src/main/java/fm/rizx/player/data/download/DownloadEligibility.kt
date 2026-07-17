package fm.rizx.player.data.download

import fm.rizx.player.data.provider.FakeStreamingProvider
import fm.rizx.player.data.provider.FakeStreamingProviderB
import fm.rizx.player.data.remote.itunes.ItunesIds
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamProtocol

/**
 * Streaming providers whose output must never be written to disk.
 *
 * - **iTunes** serves a 30-second preview carrying the *full* track duration
 *   (`ItunesMappers.toStream` sets `durationMs = trackTimeMillis`), so a duration check cannot catch it.
 *   Downloaded, it would be a 30s file that the resolver then prefers over the real stream **forever**.
 * - **The fakes** point at a bundled `asset:///` test tone — there is nothing to fetch.
 */
private val NON_DOWNLOADABLE_PROVIDERS = setOf(
    ItunesIds.STREAMING,
    FakeStreamingProvider.ID,
    FakeStreamingProviderB.ID,
)

/**
 * Whether these bytes are worth saving. Checked **before a byte is written**, because both rejections
 * below fail silently and then win the offline short-circuit permanently.
 *
 * HLS is a manifest, not audio: saved "as-is" it yields a ~2 KB text file and reports success. Real HLS
 * streams do occur here (see `PlaybackService.onPlayerError`'s HLS fallback), so this is not theoretical.
 *
 * Keyed on [Stream.source] — the provider that actually produced the bytes — rather than string-matching
 * `qualityLabel`. Note this is deliberately *not* the same ref the download is indexed under: that is
 * `track.source`, which for a Deezer-discovered track played via Audius is a different provider entirely.
 */
fun Stream.isDownloadable(): Boolean =
    protocol != StreamProtocol.HLS && source.provider !in NON_DOWNLOADABLE_PROVIDERS
