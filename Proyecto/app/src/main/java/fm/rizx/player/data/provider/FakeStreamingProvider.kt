package fm.rizx.player.data.provider

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.StreamingProvider
import kotlinx.coroutines.delay

/**
 * Development streaming provider. Discovers a couple of deterministic candidates per track (a
 * primary and a lower-quality alternate, mimicking "usually several matches") and resolves each to a
 * fake, ephemeral HTTPS URL. Simulated latency keeps the resolution flow realistic. Real scraping
 * (e.g. yt-dlp) replaces this in a later phase; the resolved URLs are never persisted.
 */
class FakeStreamingProvider : StreamingProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.STREAMING
    override val name: String = "Fake Stream"

    override suspend fun searchForTrack(track: Track): List<StreamCandidate> {
        delay(SEARCH_LATENCY_MS)
        return listOf(
            candidate(track, variant = "primary"),
            candidate(track, variant = "alt"),
        )
    }

    override suspend fun getStreamUrl(candidate: StreamCandidate): Stream {
        delay(RESOLVE_LATENCY_MS)
        val alt = candidate.id.endsWith(":alt")
        // A bundled tone asset stands in for a real, ephemeral stream URL so playback actually works
        // on device. `asset:///…` is served by Media3's AssetDataSource. Real scraping lands later.
        return Stream(
            url = FAKE_ASSET_URI,
            protocol = StreamProtocol.FILE,
            mimeType = "audio/wav",
            bitrateKbps = if (alt) 128 else 256,
            codec = "pcm",
            container = "wav",
            qualityLabel = if (alt) "Standard" else "High",
            durationMs = candidate.durationMs,
            source = ProviderRef(provider = ID, id = candidate.id),
        )
    }

    private fun candidate(track: Track, variant: String): StreamCandidate {
        val candidateId = "${track.source.id}:$variant"
        return StreamCandidate(
            id = candidateId,
            title = track.title,
            durationMs = track.durationMs,
            source = ProviderRef(provider = ID, id = candidateId),
        )
    }

    companion object {
        const val ID = "fake-streaming"
        private const val SEARCH_LATENCY_MS = 200L
        private const val RESOLVE_LATENCY_MS = 150L
        /** Bundled placeholder audio in `app/src/main/assets/` (see `PlaybackMediaMapper`). */
        private const val FAKE_ASSET_URI = "asset:///fake_stream.wav"
    }
}
