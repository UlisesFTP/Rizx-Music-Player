package fm.rizx.player.data.provider

import fm.rizx.player.core.error.AppError
import fm.rizx.player.data.lyrics.LrcParser
import fm.rizx.player.data.remote.lrclib.LrcLibApi
import fm.rizx.player.data.remote.lrclib.LrcLibTrackDto
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
import kotlin.math.abs

/**
 * **Timed** lyrics from the keyless LRCLIB API — the source that makes the synced lyrics screen possible.
 *
 * Matching is a three-step climb-down, and the reason is our own audio pipeline: LRCLIB's exact lookup
 * matches duration within ±2 s, but tracks here usually stream from YouTube, whose uploads carry intros,
 * outros and silence the reference master doesn't have. A single exact call would 404 constantly.
 *
 * 1. `/api/get` **with** the duration — the precise hit, and the only step that can distinguish two
 *    recordings of the same song.
 * 2. `/api/get` **without** it — same title/artist, any length.
 * 3. `/api/search` — free text, then the closest candidate by duration, preferring timed lyrics.
 *
 * A 404 at any step is a miss, not a failure. Only genuine transport errors surface as [AppError], so a
 * song nobody has transcribed shows an empty state instead of an error.
 */
class LrcLibProvider(
    private val api: LrcLibApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LyricsProvider {

    override val id: String = ID
    override val kind: ProviderKind = ProviderKind.LYRICS
    override val name: String = NAME

    override suspend fun getLyrics(track: Track): Lyrics? {
        val artist = track.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: return null
        val title = track.title.takeIf { it.isNotBlank() } ?: return null
        val album = track.album?.title?.takeIf { it.isNotBlank() }
        val durationMs = track.durationMs

        return guard {
            withContext(io) {
                // Step 1 runs only when we know our own duration — without it the exact call is just
                // step 2 with extra words.
                val exact = durationMs?.let { exactGet(artist, title, album, it) }
                exact
                    ?: looseGet(artist, title, album)
                    ?: bestFromSearch(artist, title, durationMs)
            }
        }
    }

    override suspend fun searchLyrics(query: String): List<LyricsCandidate> {
        val q = query.trim().takeIf { it.isNotBlank() } ?: return emptyList()
        return guard {
            withContext(io) {
                api.search(q)
                    .asSequence()
                    // A row with neither timed nor plain text is a stub the user can't use.
                    .filter { it.syncedLyrics != null || it.plainLyrics != null || it.instrumental }
                    .take(MAX_RESULTS)
                    .mapNotNull { it.toCandidate() }
                    .toList()
            }
        }.orEmpty()
    }

    // ---- Matching steps ----

    /** Step 1: duration-matched, the only step that can tell two recordings of a song apart. */
    private suspend fun exactGet(artist: String, title: String, album: String?, durationMs: Long): Lyrics? =
        notFoundAsNull { api.get(artist, title, album, durationMs / 1000) }?.toLyrics()

    /** Step 2: same title/artist, any length. */
    private suspend fun looseGet(artist: String, title: String, album: String?): Lyrics? =
        notFoundAsNull { api.get(artist, title, album, null) }?.toLyrics()

    /**
     * Step 3. Scores candidates by how close their duration is to ours, with a bonus for timed lyrics:
     * given two transcriptions of the same song, the synced one is strictly more useful, and a wrong-length
     * match is usually a live version, an edit, or a different mix.
     */
    private suspend fun bestFromSearch(artist: String, title: String, durationMs: Long?): Lyrics? {
        val results = notFoundAsNull { api.search("$artist $title") } ?: return null
        return results
            .filter { it.syncedLyrics != null || it.plainLyrics != null || it.instrumental }
            .minByOrNull { row ->
                val drift = durationMs?.let { ours ->
                    row.duration?.let { abs((it * 1000).toLong() - ours) } ?: UNKNOWN_DURATION_PENALTY
                } ?: 0L
                drift + if (row.syncedLyrics != null) 0L else UNSYNCED_PENALTY
            }
            ?.toLyrics()
    }

    // ---- Mapping ----

    private fun LrcLibTrackDto.toLyrics(): Lyrics? {
        val lines = LrcParser.parse(syncedLyrics)
        val plain = plainLyrics?.trim()?.takeIf { it.isNotEmpty() }
        if (lines.isEmpty() && plain == null && !instrumental) return null
        return Lyrics(plain = plain, lines = lines, sourceName = NAME, instrumental = instrumental)
    }

    private fun LrcLibTrackDto.toCandidate(): LyricsCandidate? {
        val lyrics = toLyrics() ?: return null
        return LyricsCandidate(
            id = (id ?: return null).toString(),
            title = trackName.orEmpty(),
            artist = artistName.orEmpty(),
            album = albumName?.takeIf { it.isNotBlank() },
            durationMs = duration?.let { (it * 1000).toLong() },
            lyrics = lyrics,
        )
    }

    // ---- Error policy ----

    /** A 404 is "nobody has transcribed this", which is data, not a fault. Everything else propagates. */
    private suspend fun <T> notFoundAsNull(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: HttpException) {
            if (e.code() == 404) null else throw e
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
        const val ID = "lrclib"
        const val NAME = "LRCLIB"

        /** LRCLIB returns 20 rows; each embeds its full lyrics, so the payload is large. Keep it bounded. */
        private const val MAX_RESULTS = 20

        /** Ranking weights, in the same "milliseconds of drift" unit the score is built from. */
        private const val UNSYNCED_PENALTY = 30_000L
        private const val UNKNOWN_DURATION_PENALTY = 60_000L
    }
}
