package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.lyrics.KrcParser
import fm.rizx.player.data.remote.kugou.KugouApi
import fm.rizx.player.data.remote.kugou.KugouCandidateDto
import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.LyricsCandidate
import fm.rizx.player.domain.lyrics.LyricsMatchTarget
import fm.rizx.player.domain.lyrics.LyricsTrackMatcher
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.LyricsProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

/**
 * **Word-by-word** lyrics from KuGou, keyless — the second karaoke source, in the `krc` format.
 *
 * Two requests: search for candidates (passing our duration, which KuGou uses to rank), then download
 * the best one and decode it (see [KrcParser]). It sits next to [NeteaseLyricsProvider] because the two
 * catalogues miss different songs; whichever answers first wins the chain.
 *
 * A candidate that decodes to nothing is treated as a miss, not a failure — a format change costs the
 * user this provider, not the screen.
 */
class KugouLyricsProvider(
    private val api: KugouApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LyricsProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.LYRICS
    override val name: String = NAME

    override suspend fun getLyrics(track: Track): Lyrics? {
        val title = track.title.takeIf { it.isNotBlank() } ?: return null
        val artist = track.artists.firstOrNull()?.name.orEmpty()
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")

        return guard {
            withContext(io) {
                val candidates = api.search(query, track.durationMs).candidates
                val best = candidates.bestFor(track) ?: return@withContext null
                lyricsFor(best)
            }
        }
    }

    override suspend fun searchLyrics(query: String): List<LyricsCandidate> {
        val q = query.trim().takeIf { it.isNotBlank() } ?: return emptyList()
        return guard {
            withContext(io) {
                api.search(q).candidates
                    .take(MAX_CANDIDATES)
                    .mapNotNull { candidate ->
                        val lyrics = lyricsFor(candidate) ?: return@mapNotNull null
                        LyricsCandidate(
                            id = candidate.id ?: return@mapNotNull null,
                            title = candidate.song.orEmpty(),
                            artist = candidate.singer.orEmpty(),
                            durationMs = candidate.duration,
                            lyrics = lyrics,
                        )
                    }
            }
        }.orEmpty()
    }

    /**
     * The candidate that is the same recording, not merely the same length — see [LyricsTrackMatcher].
     * KuGou already filters server-side by duration, so this mostly decides between near-identical hits.
     */
    private fun List<KugouCandidateDto>.bestFor(track: Track): KugouCandidateDto? =
        LyricsTrackMatcher.bestOf(track, this) { candidate ->
            LyricsMatchTarget(
                title = candidate.song.orEmpty(),
                artist = candidate.singer.orEmpty(),
                durationMs = candidate.duration,
            )
        }

    private suspend fun lyricsFor(candidate: KugouCandidateDto): Lyrics? {
        val id = candidate.id ?: return null
        val key = candidate.accesskey ?: return null
        val lines = KrcParser.parseEncoded(api.download(id, key).content)
        if (lines.isEmpty()) return null
        return Lyrics(lines = lines, sourceName = NAME)
    }

    private suspend fun <T> guard(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 404) null else throw AppError.ProviderFailure(name, "HTTP ${e.code()}", e)
        } catch (e: IOException) {
            throw AppError.Network(e.message ?: "connection failed", e)
        } catch (e: Exception) {
            throw AppError.ProviderFailure(name, e.message ?: "lyrics lookup failed", e)
        }

    companion object {
        const val ID = "kugou"
        const val NAME = "KuGou"

        /** Each candidate costs a download call, so the manual picker stays short. */
        private const val MAX_CANDIDATES = 5

    }
}
