package fm.rizx.player.data.genre

import fm.rizx.player.data.artwork.ArtworkMatching
import fm.rizx.player.data.remote.itunes.ItunesApi
import fm.rizx.player.data.remote.itunes.toTrackOrNull
import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.usecase.ArtistNameMatching
import fm.rizx.player.domain.usecase.GenreClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A track's genre family plus the catalogue's own wording for it (kept for display). */
data class ResolvedGenre(val genre: SoundGenre, val label: String?)

/**
 * What genre is this song? — asked cheapest-first, and never answered with a guess.
 *
 * Most tracks in this app are Deezer metadata played through YouTube audio, and **neither of those
 * carries a genre on a track row**. So the question needs a chain, in rising cost:
 *
 *  1. **[Track.tags]** — free. Apple/iTunes rows and the on-device scan already put their genre there.
 *  2. **The owner, by id** — `MetadataProvider.trackDetail`, dispatched through
 *     [MetadataProvider.owns] rather than by looking up `source.provider` in the registry (a provider's
 *     registry id is not the namespace it mints refs in — see [MetadataProvider.ownedNamespaces]).
 *  3. **The album** — `albumDetail(...).tags`. This is the step that covers the common case: Deezer
 *     reports genres on `/album/{id}` and nowhere else.
 *  4. **iTunes, by song** — a public search, and the candidate must pass
 *     [ArtworkMatching.canLendArtwork] before its genre is believed. Taking rank 1 unverified is how a
 *     remix's metadata ends up on the original.
 *  5. **iTunes, by artist** — an artist's genre is stable, which makes it a sound last resort. Verified
 *     with [ArtistNameMatching.sameArtist] and memoized, since a queue is usually a handful of artists.
 *
 * Anything unmatched is [SoundGenre.UNKNOWN], which the curve table shapes to flat. Every step is
 * guarded: a dead provider, a timeout or no network means "no genre", never a thrown exception — an
 * equalizer must not be able to interrupt playback. Cancellation is honoured, so skipping tracks quickly
 * abandons the lookups instead of queueing them up.
 */
class TrackGenreResolver(
    private val registry: ProviderRegistry,
    private val itunes: ItunesApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Artist name key → genre wording (empty string = asked, nothing there). */
    private val artistGenres = ConcurrentHashMap<String, String>()

    suspend fun resolve(track: Track): ResolvedGenre = withContext(io) { chain(track) }

    private suspend fun chain(track: Track): ResolvedGenre {
        // The first wording seen even if nothing recognised it: worth showing ("Filmi", "Sea Shanty")
        // even when the curve stays flat, because it tells the user what the app was told.
        var unmatched: String? = null

        suspend fun step(labels: suspend () -> List<String>): ResolvedGenre? {
            val found = guarded(labels) ?: return null
            for (raw in found) {
                val label = raw.trim().takeIf { it.isNotEmpty() } ?: continue
                val genre = GenreClassifier.classify(label)
                if (genre != SoundGenre.UNKNOWN) return ResolvedGenre(genre, label)
                if (unmatched == null) unmatched = label
            }
            return null
        }

        step { track.tags }?.let { return it }
        step { ownerTags(track) }?.let { return it }
        step { albumTags(track) }?.let { return it }
        step { songGenre(track) }?.let { return it }
        step { artistGenre(track) }?.let { return it }
        return ResolvedGenre(SoundGenre.UNKNOWN, unmatched)
    }

    /** The owner catalogue's own row for this exact ref — no matching, so it can't be the wrong song. */
    private suspend fun ownerTags(track: Track): List<String> {
        val owner = providers().firstOrNull { it.owns(track.source) } ?: return emptyList()
        return owner.trackDetail(track.source)?.tags.orEmpty()
    }

    /** The album's genres — Deezer's only genre surface, and Apple's album rows carry one too. */
    private suspend fun albumTags(track: Track): List<String> {
        val ref = track.album?.source ?: return emptyList()
        val owner = providers().firstOrNull { it.owns(ref) } ?: return emptyList()
        return owner.albumDetail(ref)?.tags.orEmpty()
    }

    /**
     * iTunes' `primaryGenreName` for this recording, but only from a row that verifies as the same
     * release. A track with no artist credit is not searched at all: its query would be a bare title, and
     * bare titles collide constantly.
     */
    private suspend fun songGenre(track: Track): List<String> {
        val artist = primaryArtist(track) ?: return emptyList()
        val title = track.title.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val term = "${ArtistNameMatching.searchName(artist)} $title"
        val rows = itunes.search(term = term, entity = "song", limit = SONG_CANDIDATES).results
        return rows
            .firstOrNull { row -> row.toTrackOrNull()?.let { ArtworkMatching.canLendArtwork(track, it) } == true }
            ?.primaryGenreName
            ?.let(::listOf)
            .orEmpty()
    }

    /**
     * The artist's own genre. Weaker than the song's — an artist can cross genres — but stable, cheap and
     * almost always right about the *family*, which is all a curve needs.
     */
    private suspend fun artistGenre(track: Track): List<String> {
        val artist = primaryArtist(track) ?: return emptyList()
        val key = ArtistNameMatching.key(artist).takeIf { it.isNotEmpty() } ?: return emptyList()
        artistGenres[key]?.let { return if (it.isEmpty()) emptyList() else listOf(it) }

        val rows = itunes.search(
            term = ArtistNameMatching.searchName(artist),
            entity = "musicArtist",
            limit = ARTIST_CANDIDATES,
        ).results
        val genre = rows
            .firstOrNull { row -> row.artistName?.let { ArtistNameMatching.sameArtist(artist, it) } == true }
            ?.primaryGenreName
            ?.trim()
            .orEmpty()
        artistGenres[key] = genre
        return if (genre.isEmpty()) emptyList() else listOf(genre)
    }

    private fun primaryArtist(track: Track): String? =
        track.artists.firstOrNull()?.name?.trim()?.takeIf { it.isNotEmpty() }

    private fun providers(): List<MetadataProvider> =
        runCatching { registry.list(ProviderKind.METADATA).filterIsInstance<MetadataProvider>() }
            .getOrDefault(emptyList())

    /** Swallows provider/network failures — a genre is a nice-to-have — but honours cancellation. */
    private suspend inline fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** The right release is often rank 2 or 3, behind a remix or a compilation. */
        const val SONG_CANDIDATES = 5

        /** Same-name artists exist; a couple of rows is enough for the verifier to find the real one. */
        const val ARTIST_CANDIDATES = 3
    }
}
