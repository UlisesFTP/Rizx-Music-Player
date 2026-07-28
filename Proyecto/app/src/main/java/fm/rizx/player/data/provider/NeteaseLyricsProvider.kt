package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.lyrics.LrcParser
import fm.rizx.player.data.lyrics.YrcParser
import fm.rizx.player.data.remote.netease.NeteaseApi
import fm.rizx.player.data.remote.netease.NeteaseSongDto
import fm.rizx.player.domain.lyrics.LyricsMatchTarget
import fm.rizx.player.domain.lyrics.LyricsTrackMatcher
import fm.rizx.player.domain.model.Lyrics
import fm.rizx.player.domain.model.LyricsCandidate
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
 * **Word-by-word** lyrics from NetEase Cloud Music, keyless.
 *
 * NetEase is the one freely-readable source that publishes `yrc`: a transcript timed per word rather
 * than per line, which is what the karaoke view needs. When a song has no `yrc` it still returns plain
 * LRC, so this provider degrades to line timings instead of dropping out.
 *
 * Matching is search-then-score, the same shape as [LrcLibProvider]: NetEase has no
 * "find by artist+title+duration" endpoint, so we search and pick the candidate whose length is closest
 * to ours — the only signal that separates a single from its live, remix and extended versions.
 */
class NeteaseLyricsProvider(
    private val api: NeteaseApi,
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
                val song = bestMatch(query, track) ?: return@withContext null
                lyricsFor(song.id ?: return@withContext null)
            }
        }
    }

    override suspend fun searchLyrics(query: String): List<LyricsCandidate> {
        val q = query.trim().takeIf { it.isNotBlank() } ?: return emptyList()
        return guard {
            withContext(io) {
                api.search(q, limit = MAX_RESULTS).result.songs
                    .take(MAX_CANDIDATES)
                    .mapNotNull { song ->
                        val id = song.id ?: return@mapNotNull null
                        val lyrics = lyricsFor(id) ?: return@mapNotNull null
                        LyricsCandidate(
                            id = id.toString(),
                            title = song.name.orEmpty(),
                            artist = song.artists.joinToString { it.name.orEmpty() },
                            album = song.album?.name?.takeIf { it.isNotBlank() },
                            durationMs = song.duration,
                            lyrics = lyrics,
                        )
                    }
            }
        }.orEmpty()
    }

    /**
     * The candidate that is actually the same recording — title, artist and version, then length.
     *
     * Length alone used to decide this, which is how a live take or a sped-up edit of the same song won:
     * they are the versions whose duration lands closest to the original.
     */
    private suspend fun bestMatch(query: String, track: Track): NeteaseSongDto? {
        val songs = api.search(query, limit = MAX_RESULTS).result.songs.ifEmpty { return null }
        return LyricsTrackMatcher.bestOf(track, songs) { song ->
            LyricsMatchTarget(
                title = song.name.orEmpty(),
                artist = song.artists.joinToString { it.name.orEmpty() },
                album = song.album?.name,
                durationMs = song.duration,
            )
        }
    }

    /** Word timings when the song has them, else the line-timed LRC, else nothing. */
    private suspend fun lyricsFor(id: Long): Lyrics? {
        val body = api.lyric(id)
        val words = YrcParser.parse(body.yrc?.lyric)
        val lines = words.ifEmpty { LrcParser.parse(body.lrc?.lyric) }
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
        const val ID = "netease"
        const val NAME = "NetEase"

        private const val MAX_RESULTS = 10

        /** Each candidate costs its own lyric request, so the manual picker stays short. */
        private const val MAX_CANDIDATES = 5

    }
}
