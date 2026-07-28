package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.lyrics.LrcParser
import fm.rizx.player.data.lyrics.RichSyncParser
import fm.rizx.player.data.remote.musixmatch.MusixmatchClient
import fm.rizx.player.data.remote.musixmatch.MusixmatchTrack
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
import java.io.IOException

/**
 * **Word-by-word** lyrics from Musixmatch — the largest catalogue of the three karaoke sources.
 *
 * Requested by the owner. It carries no API key: [MusixmatchClient] recovers the web player's own
 * signing secret at runtime, the same keyless-scrape approach the Spotify playlist import already uses.
 * That also makes it the most breakable provider here, which is exactly why it is one link in a chain —
 * when it stops answering, NetEase, KuGou and LRCLIB carry on.
 *
 * Prefers a richsync (per word) and falls back to the subtitle (per line) for songs that have only that.
 */
class MusixmatchLyricsProvider(
    private val client: MusixmatchClient,
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
                val hits = client.search(query, MAX_RESULTS)
                // Prefer a track that actually has word timings; among those, the best match for this
                // recording. Word timings are worth reaching for, but not from a different version.
                val best = hits.filter { it.hasRichsync }.bestFor(track)
                    ?: hits.bestFor(track)
                    ?: return@withContext null
                lyricsFor(best)
            }
        }
    }

    override suspend fun searchLyrics(query: String): List<LyricsCandidate> {
        val q = query.trim().takeIf { it.isNotBlank() } ?: return emptyList()
        return guard {
            withContext(io) {
                client.search(q, MAX_CANDIDATES).mapNotNull { hit ->
                    val lyrics = lyricsFor(hit) ?: return@mapNotNull null
                    LyricsCandidate(
                        id = hit.trackId.toString(),
                        title = hit.title,
                        artist = hit.artist,
                        album = hit.album,
                        durationMs = hit.durationMs,
                        lyrics = lyrics,
                    )
                }
            }
        }.orEmpty()
    }

    private fun List<MusixmatchTrack>.bestFor(track: Track): MusixmatchTrack? =
        LyricsTrackMatcher.bestOf(track, this) { hit ->
            LyricsMatchTarget(
                title = hit.title,
                artist = hit.artist,
                album = hit.album,
                durationMs = hit.durationMs,
            )
        }

    private fun lyricsFor(hit: MusixmatchTrack): Lyrics? {
        val words = if (hit.hasRichsync) RichSyncParser.parse(client.richSync(hit.trackId)) else emptyList()
        val lines = words.ifEmpty { LrcParser.parse(client.subtitle(hit.trackId)) }
        if (lines.isEmpty()) return null
        return Lyrics(lines = lines, sourceName = NAME)
    }

    private suspend fun <T> guard(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw AppError.Network(e.message ?: "connection failed", e)
        } catch (e: Exception) {
            throw AppError.ProviderFailure(name, e.message ?: "lyrics lookup failed", e)
        }

    companion object {
        const val ID = "musixmatch"
        const val NAME = "Musixmatch"

        private const val MAX_RESULTS = 5

        /** Each candidate costs a second request for its body, so the manual picker stays short. */
        private const val MAX_CANDIDATES = 4

    }
}
