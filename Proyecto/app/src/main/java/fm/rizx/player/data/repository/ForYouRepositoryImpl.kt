package fm.rizx.player.data.repository

import fm.rizx.player.core.region.RegionResolver
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerArtistSearch
import fm.rizx.player.data.remote.deezer.DeezerIds
import fm.rizx.player.data.remote.deezer.toAlbumRef
import fm.rizx.player.data.remote.deezer.toArtistRef
import fm.rizx.player.data.remote.deezer.toTrackOrNull
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.RadioMixSource
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.ForYouRepository
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import fm.rizx.player.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.supervisorScope

/**
 * Builds the personalized "For you" rows from the user's own taste (likes + recents):
 * - **Mix** rows — YT Music's real autoplay recommendations seeded by the user's tracks;
 * - **Because you like <artist>** — Deezer's artist radio for the most-listened artist;
 * - **Artists for you** — Deezer's related artists across the top taste artists.
 *
 * Rows are fetched concurrently and each is `runCatching`-isolated: a broken source drops its row,
 * never the feed. Cold start (no taste data) returns no sections. Also fronts the regional-consent
 * setting and the detected country so the Home ViewModel needs only this one seam.
 *
 * **Plan first, then fill.** Every row's title is derivable from local taste alone, so [sections]
 * emits the titled-but-empty plan before it touches the network and the finished rows after. The
 * Home turns that first emission into skeletons of the real height — otherwise this, the slowest
 * half of the screen, landed a screen of content *above* the charts the user was already reading.
 */
class ForYouRepositoryImpl(
    private val favorites: FavoritesRepository,
    private val recents: RecentlyPlayedRepository,
    private val mix: RadioMixSource,
    private val deezer: DeezerApi,
    private val settings: SettingsRepository,
    private val region: RegionResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Injectable so tests get deterministic seed picking. */
    private val shuffle: (List<Track>) -> List<Track> = { it.shuffled() },
    /** Shared with the metadata provider, so a taste artist is looked up once across both. */
    private val artists: DeezerArtistSearch = DeezerArtistSearch(deezer),
) : ForYouRepository {

    override val regionalConsent: Flow<Boolean?> get() = settings.recsRegionalConsent

    override suspend fun setRegionalConsent(consented: Boolean) = settings.setRecsRegionalConsent(consented)

    override fun countryName(): String? = region.countryDisplayName()

    override fun sections(): Flow<List<ForYouSection>> = flow {
        val recent = runCatching { recents.recent(TASTE_LIMIT).first() }.getOrDefault(emptyList())
        val liked = runCatching { favorites.favoriteTracks().first() }.getOrDefault(emptyList())
        val taste = (recent + liked).distinctBy { it.source }
        if (taste.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        // Seeds and the top artist are picked **once**, here, and handed to both halves: the plan's
        // titles and the real rows' titles must be the same strings or the Home would swap a skeleton
        // for a differently-titled row instead of filling it. (`shuffle` alone would differ per call.)
        val seeds = mixSeeds(taste)
        val artist = topArtists(taste).firstOrNull()
        emit(plan(seeds, artist))

        emit(
            supervisorScope {
                // Three independent branches, all in flight at once. The discovery branch keeps its two
                // rows together because both are built from the same related-artist lookup — and crucially
                // `albumsForYou` now starts the moment that lookup lands instead of waiting behind the
                // slow YT mixes, which used to add its two round-trips as pure tail latency.
                val mixes = async { mixSections(seeds) }
                val because = async { becauseYouLike(artist) }
                val discovery = async {
                    val similar = relatedArtists(taste)
                    val artistsRow = similar.takeIf { it.size >= MIN_ROW_ITEMS }
                        ?.let { ForYouSection.ArtistsForYou(it.take(ROW_ITEMS)) }
                    listOfNotNull(artistsRow, albumsForYou(similar))
                }
                mixes.await() + listOfNotNull(because.await()) + discovery.await()
            },
        )
    }.flowOn(io)

    /**
     * The rows about to be built: real titles, no items yet. This is what lets the Home reserve the
     * block's height up front — every title here comes from local taste, so it is ready before the
     * first network call. A planned row whose source then comes back empty collapses, which is one
     * row of movement instead of the whole block arriving at once.
     */
    private fun plan(seeds: List<Track>, artist: ArtistCredit?): List<ForYouSection> = buildList {
        seeds.forEach { add(ForYouSection.Mix(seedTitle = it.title, items = emptyList())) }
        // The three Deezer-backed rows all hang off a credited artist; with none, none of them can run.
        if (artist != null) {
            add(ForYouSection.BecauseYouLike(artistName = artist.name, items = emptyList()))
            add(ForYouSection.ArtistsForYou(emptyList()))
            add(ForYouSection.AlbumsForYou(emptyList()))
        }
    }

    /**
     * Seeds for up to [MIX_ROWS] Mix rows. Distinct by **artist** first — two mixes seeded from the
     * same artist return largely the same songs, which is the loudest redundancy the feed can have —
     * and then by title, because the row's title doubles as its LazyColumn key.
     */
    private fun mixSeeds(taste: List<Track>): List<Track> =
        shuffle(taste)
            .distinctBy { track -> track.artists.firstOrNull()?.name?.lowercase() ?: track.title.lowercase() }
            .distinctBy { it.title.lowercase() }
            .take(MIX_ROWS)

    private suspend fun mixSections(seeds: List<Track>): List<ForYouSection> = supervisorScope {
        seeds
            // Concurrently: each row is a blocking NewPipe search + mix + artwork pass, and running the
            // second behind the first doubled the slowest thing on the Home. `awaitAll` keeps the order.
            .map { seed ->
                async {
                    // Only what the carousel draws gets an artwork lookup — see RadioMixSource.mixTracks.
                    runCatching { mix.mixTracks(seed, ROW_ITEMS) }.getOrDefault(emptyList())
                        .takeIf { it.size >= MIN_ROW_ITEMS }
                        ?.let { ForYouSection.Mix(seedTitle = seed.title, items = it.take(ROW_ITEMS)) }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun becauseYouLike(artist: ArtistCredit?): ForYouSection? {
        if (artist == null) return null
        val artistId = deezerArtistId(artist) ?: return null
        return runCatching { deezer.artistRadio(artistId, ROW_ITEMS).data.mapNotNull { it.toTrackOrNull() } }
            .getOrDefault(emptyList())
            .takeIf { it.size >= MIN_ROW_ITEMS }
            ?.let { ForYouSection.BecauseYouLike(artistName = artist.name, items = it) }
    }

    /** Artists similar to the user's most-listened ones — the seed for both discovery rows. */
    private suspend fun relatedArtists(taste: List<Track>): List<ArtistRef> = supervisorScope {
        topArtists(taste).take(RELATED_SEEDS)
            // One seed = up to two serialized Deezer calls (id lookup, then related), so three seeds ran
            // as six round-trips in a row. Fanned out, it is two.
            .map { credit ->
                async {
                    val id = deezerArtistId(credit) ?: return@async emptyList()
                    runCatching { deezer.artistRelated(id, RELATED_PER_SEED).data.mapNotNull { it.toArtistRef() } }
                        .getOrDefault(emptyList())
                }
            }
            .awaitAll()
            .flatten()
            .distinctBy { it.source }
    }

    /**
     * Records by the first few similar artists. Deezer's `/artist/{id}/albums` omits the artist on each
     * row (it's implied by the endpoint), so it's carried over from the seed — otherwise every card
     * would render with a blank subtitle.
     */
    private suspend fun albumsForYou(related: List<ArtistRef>): ForYouSection? = supervisorScope {
        val perArtist = related.take(ALBUM_SEEDS)
            .map { artist ->
                async {
                    val id = artist.source.takeIf { it.provider == DeezerIds.PROVIDER }
                        ?.let { DeezerIds.rawId(it) } ?: return@async emptyList()
                    runCatching { deezer.artistAlbums(id, ALBUMS_PER_SEED).data.mapNotNull { it.toAlbumRef() } }
                        .getOrDefault(emptyList())
                        .map { album -> if (album.artists.isEmpty()) album.copy(artists = listOf(artist)) else album }
                }
            }
            .awaitAll()
        // Round-robin rather than concatenated: a row of one artist's back catalogue reads as their
        // discography, not as a recommendation.
        val albums = (0 until (perArtist.maxOfOrNull { it.size } ?: 0))
            .flatMap { rank -> perArtist.mapNotNull { it.getOrNull(rank) } }
            .distinctBy { it.source }
        albums.takeIf { it.size >= MIN_ROW_ITEMS }
            ?.let { ForYouSection.AlbumsForYou(items = it.take(ROW_ITEMS)) }
    }

    /** The taste's credited artists by how often they appear, most-listened first. */
    private fun topArtists(taste: List<Track>): List<ArtistCredit> =
        taste.mapNotNull { it.artists.firstOrNull() }
            .filter { it.name.isNotBlank() }
            .groupBy { it.name.lowercase() }
            .values
            .sortedByDescending { it.size }
            .map { credits -> credits.firstOrNull { it.source != null } ?: credits.first() }

    /**
     * The raw Deezer id of a credited artist: its own ref, or a name lookup.
     *
     * Shared with the artist radio, and for the same reason: a recently-played track can easily be one
     * that came out of a YouTube Mix, whose credit is a channel name Deezer has never heard of.
     */
    private suspend fun deezerArtistId(credit: ArtistCredit): String? = artists.idFor(credit)

    private companion object {
        const val TASTE_LIMIT = 25
        const val MIX_ROWS = 2
        const val ROW_ITEMS = 12
        const val MIN_ROW_ITEMS = 3
        const val RELATED_SEEDS = 3
        const val RELATED_PER_SEED = 8

        /** Albums come from a couple of the similar artists — enough for a row, two extra calls. */
        const val ALBUM_SEEDS = 2
        const val ALBUMS_PER_SEED = 6
    }
}
