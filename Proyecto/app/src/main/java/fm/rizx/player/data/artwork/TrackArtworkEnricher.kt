package fm.rizx.player.data.artwork

import fm.rizx.player.data.remote.soundcloud.SoundcloudIds
import fm.rizx.player.data.remote.youtube.YoutubeIds
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry
import fm.rizx.player.domain.usecase.ArtistNameMatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Fills in cover art that a source didn't come with — **owner first, and never on a guess**.
 *
 * Two rules, in order:
 *
 *  1. **Ask the provider that owns the track.** A ref names a row in someone's catalogue, so that
 *     catalogue can return the exact recording by id: no matching, no possibility of a different
 *     song. This is what [MetadataProvider.trackDetail] exists for, and it is dispatched through
 *     [MetadataProvider.owns] rather than by looking up `source.provider` in the registry — a
 *     provider's registry id (`applemusic-metadata`) is not the namespace it mints refs in
 *     (`applemusic`), so the naive lookup finds nothing for most providers.
 *  2. **Only then borrow from another catalogue, and only after verifying it.** Spotify's embed
 *     carries no per-track art at all and YouTube's "cover" is a 16:9 video still, so those tracks do
 *     need a cover from elsewhere — but the candidate must pass [ArtworkMatching.canLendArtwork].
 *     Taking rank-1 unverified is what used to put a remix's sleeve on the original.
 *
 * The track's **identity is never touched** — only [Track.artwork] — so it still resolves to audio
 * exactly as before.
 *
 * Everything degrades silently: a lookup that fails, times out or matches nothing leaves the track as
 * it was. Enrichment is cosmetic and must never fail an import.
 *
 * Lookups are memoized by [ArtworkCache] under the track's **[fm.rizx.player.domain.model.ProviderRef]
 * identity**, not under a query string. The old query key meant two different songs sharing an
 * "artist title" string shared one cover, and a track with no artist credit collapsed onto its bare
 * title — every song called "Intro" wore the same stranger's sleeve, persisted to disk.
 */
class TrackArtworkEnricher(
    private val registry: ProviderRegistry,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Concurrent lookups. Enough to keep a 100-track import quick without hammering the provider. */
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val cache: ArtworkCache = ArtworkCache(),
) {

    /**
     * Returns [tracks] with missing covers filled in. Tracks that already have artwork are left
     * untouched and cost nothing. Returns the input unchanged when there is nothing to do.
     *
     * [upgradeFrom] names providers whose artwork is *present but poor* and should be replaced rather
     * than kept — YouTube being the case that matters: its "cover" is a 16:9 video still, so a queue
     * filled from a YT Mix would show letterboxed screenshots next to square covers. Those tracks are
     * looked up like an artwork-less one and keep their original image if nothing verifies.
     */
    suspend fun enrich(
        tracks: List<Track>,
        upgradeFrom: Set<String> = emptySet(),
        repairBorrowed: Boolean = false,
    ): List<Track> {
        fun needsLookup(track: Track) =
            track.artwork.coverUrl() == null ||
                track.source.provider in upgradeFrom ||
                (repairBorrowed && track.artwork.isBorrowed(track))

        if (tracks.none(::needsLookup)) return tracks
        val providers = metadataProviders().ifEmpty { return tracks }

        return withContext(io) {
            val gate = Semaphore(parallelism)
            coroutineScope {
                tracks
                    .map { track ->
                        async {
                            if (!needsLookup(track)) {
                                track
                            } else {
                                gate.withPermit {
                                    // A borrowed cover being repaired is dropped when nothing verifies:
                                    // keeping it would preserve exactly the wrong image we came to fix.
                                    val dropOnFailure = repairBorrowed && track.artwork.isBorrowed(track)
                                    track.copy(artwork = resolve(track, providers, dropOnFailure))
                                }
                            }
                        }
                    }
                    .awaitAll()
            }.also { cache.flush() }
        }
    }

    /** True when this artwork was lent by another catalogue rather than shipped with the track. */
    private fun ArtworkSet?.isBorrowed(track: Track): Boolean =
        this?.items?.any { it.source != null && it.source != track.source } == true

    /**
     * The best cover URL for **one** track, for a screen that shows it large.
     *
     * A track whose own artwork is good is returned untouched and costs nothing. One whose artwork is
     * missing, or comes from a provider in [upgradeFrom], is looked up: a YouTube-sourced song carries
     * a 16:9 video still, which the full-screen player crops into a blurry band.
     *
     * Display-only: the queue keeps the track exactly as it is.
     */
    suspend fun coverFor(track: Track, upgradeFrom: Set<String> = POOR_ARTWORK_PROVIDERS): String? {
        val own = track.artwork.coverUrl()
        if (own != null && track.source.provider !in upgradeFrom) return own
        val providers = metadataProviders().ifEmpty { return own }
        return withContext(io) { resolve(track, providers).coverUrl() ?: own }.also { cache.flush() }
    }

    /**
     * A cover for a whole playlist, for sources that don't supply one: the first track that has (or
     * can find) artwork wins. Only [COVER_PROBE_LIMIT] tracks are probed — a playlist cover is one
     * image, and the first few tracks are the ones a user associates with the list anyway.
     */
    suspend fun playlistCover(tracks: List<Track>): String? {
        tracks.firstNotNullOfOrNull { it.artwork.coverUrl() }?.let { return it }
        val providers = metadataProviders().ifEmpty { return null }
        return withContext(io) {
            tracks.take(COVER_PROBE_LIMIT).firstNotNullOfOrNull { resolve(it, providers).coverUrl() }
        }.also { cache.flush() }
    }

    /**
     * The memoized, request-collapsing front of [discover].
     *
     * Keyed by the track's own identity, so the answer belongs to *this recording* and to nothing
     * else. Keeps the track's existing art when nothing verifies — a poor cover still beats none.
     */
    private suspend fun resolve(
        track: Track,
        providers: List<MetadataProvider>,
        dropOnFailure: Boolean = false,
    ): ArtworkSet? =
        cache.get(track.source.identityKey) { discover(track, providers) }
            ?: if (dropOnFailure) null else track.artwork

    /** Owner first, then a bounded, verified walk of the other catalogues. */
    private suspend fun discover(track: Track, providers: List<MetadataProvider>): ArtworkSet? {
        owner(track, providers)?.let { owner ->
            runCatchingProvider { owner.trackDetail(track.source) }
                ?.artwork
                ?.takeIf { it.coverUrl() != null }
                ?.let { return it }
        }
        return borrow(track, providers)
    }

    private fun owner(track: Track, providers: List<MetadataProvider>): MetadataProvider? =
        providers.firstOrNull { it.owns(track.source) }

    /**
     * Searches other catalogues for a cover this track may wear, accepting only a verified match.
     *
     * Bounded on both axes. At most [MAX_BORROW_PROVIDERS] catalogues are tried, because a
     * verification gate turns "first provider that answers" into "walk them all" — with four
     * providers registered and 25 rows in a YT-Mix that is the difference between 25 requests and
     * two hundred. And each is asked for [SEARCH_LIMIT] candidates rather than one, since the right
     * release is often rank 2 or 3 behind a remix.
     */
    private suspend fun borrow(track: Track, providers: List<MetadataProvider>): ArtworkSet? {
        val query = searchQuery(track) ?: return null
        var tried = 0
        for (provider in providers) {
            if (provider.owns(track.source)) continue // already asked, by id
            if (tried >= MAX_BORROW_PROVIDERS) break
            tried++
            val candidates = runCatchingProvider {
                provider.search(SearchParams(query = query, limit = SEARCH_LIMIT)).tracks
            }.orEmpty()
            candidates
                .firstOrNull { ArtworkMatching.canLendArtwork(track, it) }
                ?.let { lender ->
                    lender.artwork?.takeIf { it.coverUrl() != null }?.let { return it.borrowedFrom(lender) }
                }
        }
        return null
    }

    /**
     * Stamps the lender's ref on every variant, so a borrowed cover is afterwards distinguishable
     * from a track's own. That is what lets [enrich] *withdraw* a cover it can no longer justify:
     * a track's own poor image is honest and worth keeping, while a borrowed one that no longer
     * verifies is a wrong answer and belongs on the floor.
     */
    private fun ArtworkSet.borrowedFrom(lender: Track): ArtworkSet =
        ArtworkSet(items.map { it.copy(source = lender.source) })

    /**
     * `"<first artist> <title>"`, with the artist de-channelized — a YouTube track credits
     * "ModjoOfficial", and searching a catalogue for "ModjoOfficial Lady (Hear Me Tonight)" returns
     * nothing at all (verified live), so the very tracks that most need a cover were the ones
     * guaranteed not to find one.
     *
     * **A track with no artist credit is not searched.** Its query would be the bare title, and a
     * bare title matches a stranger's song of the same name — which is exactly how every "Intro" in
     * the library ended up sharing one sleeve. Such a track keeps whatever art it has.
     */
    private fun searchQuery(track: Track): String? {
        val title = track.title.trim().takeIf { it.isNotEmpty() } ?: return null
        val artist = track.artists.firstOrNull()?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "${ArtistNameMatching.searchName(artist)} $title"
    }

    private fun metadataProviders(): List<MetadataProvider> =
        registry.list(ProviderKind.METADATA)
            .filterIsInstance<MetadataProvider>()
            .sortedByDescending { it.id == PREFERRED_PROVIDER_ID }

    /** Swallows provider failures (a broken provider must never fail an import) but honours cancellation. */
    private inline fun <T> runCatchingProvider(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /**
         * Providers whose artwork exists but isn't a cover: YouTube gives a 16:9 video still and
         * SoundCloud a waveform/avatar. Both look wrong next to real square album art, so anything
         * showing a track large should ask for an upgrade.
         */
        val POOR_ARTWORK_PROVIDERS: Set<String> = setOf(YoutubeIds.STREAMING, SoundcloudIds.STREAMING)

        private const val DEFAULT_PARALLELISM = 10
        private const val COVER_PROBE_LIMIT = 5

        /** Deezer first: keyless, fast, and its covers are the best match for this app's look. */
        private const val PREFERRED_PROVIDER_ID = "deezer"

        /**
         * Candidates asked of each catalogue. One was the old behaviour and it is too few once the
         * result is verified: a search for a song whose remix is more popular ranks the remix first.
         */
        private const val SEARCH_LIMIT = 5

        /** Catalogues consulted before giving up, so verification can't turn into a fan-out. */
        private const val MAX_BORROW_PROVIDERS = 2
    }
}
