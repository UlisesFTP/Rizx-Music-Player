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
 * A second development streaming provider so the Sources screen has a real choice. Resolves the same
 * bundled tone asset but advertises a different quality, so switching the active provider is a real
 * (if cosmetic) change.
 */
class FakeStreamingProviderB : StreamingProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.STREAMING
    override val name: String = "Test Tone HQ"

    override suspend fun searchForTrack(track: Track): List<StreamCandidate> {
        delay(160)
        val candidateId = "${track.source.id}:hq"
        return listOf(
            StreamCandidate(
                id = candidateId,
                title = track.title,
                durationMs = track.durationMs,
                source = ProviderRef(provider = ID, id = candidateId),
            ),
        )
    }

    override suspend fun getStreamUrl(candidate: StreamCandidate): Stream {
        delay(120)
        return Stream(
            url = "asset:///fake_stream.wav",
            protocol = StreamProtocol.FILE,
            mimeType = "audio/wav",
            bitrateKbps = 320,
            codec = "pcm",
            container = "wav",
            qualityLabel = "Lossless",
            durationMs = candidate.durationMs,
            source = ProviderRef(provider = ID, id = candidate.id),
        )
    }

    companion object {
        const val ID = "fake-streaming-b"
    }
}
