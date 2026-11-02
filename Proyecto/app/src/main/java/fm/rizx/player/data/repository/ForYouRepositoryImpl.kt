package fm.rizx.player.data.repository

import fm.rizx.player.core.region.RegionResolver
import fm.rizx.player.data.remote.deezer.DeezerAlbumShortDto
import fm.rizx.player.data.remote.deezer.DeezerApi
import fm.rizx.player.data.remote.deezer.DeezerArtistSearch
import fm.rizx.player.data.remote.deezer.DeezerIds
import fm.rizx.player.data.remote.deezer.toAlbumRef
import fm.rizx.player.data.remote.deezer.toArtistRef
import fm.rizx.player.data.remote.deezer.toTrackOrNull
import fm.rizx.player.domain.discovery.DiscoveryAnchor
import fm.rizx.player.domain.discovery.DiscoveryFeedback
import fm.rizx.player.domain.discovery.DiscoveryMerge
import fm.rizx.player.domain.discovery.DiscoveryPlaylistSource
import fm.rizx.player.domain.discovery.GenreCatalog
import fm.rizx.player.domain.discovery.HiddenItems
import fm.rizx.player.domain.discovery.TasteSeeds
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.PlayStat
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.SoundGenre
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.provider.RadioMixSource
import fm.rizx.player.domain.repository.FavoritesRepository
import fm.rizx.player.domain.repository.ForYouRepository
import fm.rizx.player.domain.repository.RecentlyPlayedRepository
import fm.rizx.player.domain.repository.SettingsRepository
import fm.rizx.player.domain.usecase.ArtistNameMatching
import fm.rizx.player.domain.usecase.TasteProfile
import fm.rizx.player.domain.usecase.soundGenre
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.supervisorScope
import java.time.LocalDate
import kotlin.random.Random

/**
 * Builds the personalized "For you" rows from the user's own taste (likes + recents):
 * - **Mix** rows — YT Music's real autoplay recommendations seeded by the user's tracks;
 * - **Because you like <artist>** — Deezer's artist radio for the most-listened artist;
 * - **Similar to <artist>** — one row per top taste artist: Deezer's related artists plus records by
 *   them, drawn as one mixed carousel (the shape every streaming feed uses for this);
 * - **Playlists with <artist>** — editorial playlists featuring a taste artist, interleaved across the
 *   [discovery] platforms (spec 026);
 * - **Your genre** — playlists for the taste's dominant genres from all four platforms;
 * - **New from your artists** — the release radar: anchors' records from the last [RADAR_DAYS] days.
 *
 * Rows are fetched concurrently and each is isolated: a broken source drops its row, never the feed.
 *
 * **Who the anchors are.** Ranked by [TasteProfile] when the listening log is available — recency,
 * replays and completions raise an artist, skips lower one — instead of by how often a name appears,
 * which let a song skipped ten times anchor the whole Home. Anything the listener marked "not
 * interested" ([feedback]) is never an anchor. With almost no history, the artists picked in the
 * cold-start card ([seeds]) stand in.
 *
 * **Plan first, then fill.** Every row whose title is derivable from local taste is announced empty
 * before any network call; the Home draws those as skeletons of the real height. Rows whose title needs
 * the network (a genre only Deezer knows, the release radar that may well be empty) simply arrive.
 */
class ForYouRepositoryImpl(
    private val favorites: FavoritesRepository,
    private val recents: RecentlyPlayedRepository,
    private val mix: RadioMixSource,
    private val deezer: DeezerApi,
    private val settings: SettingsRepository,
    private val region: RegionResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * How mix seeds are picked from the taste. Seeded by the day by default, so the rows hold still
     * across refreshes and change tomorrow — a Home that reshuffles on every pull reads as noise.
     * Injectable so tests get deterministic seed picking.
     */
    private val shuffle: (List<Track>) -> List<Track> = { it.shuffled(Random(LocalDate.now().toEpochDay())) },
    /** Shared with the metadata provider, so a taste artist is looked up once across both. */
    private val artists: DeezerArtistSearch = DeezerArtistSearch(deezer),
    /** The platforms behind the playlist shelves, in interleave order. Empty → those rows are skipped. */
    private val discovery: List<DiscoveryPlaylistSource> = emptyList(),
    private val feedback: DiscoveryFeedback? = null,
    private val seeds: TasteSeeds? = null,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ForYouRepository {

    override val regionalConsent: Flow<Boolean?> get() = settings.recsRegionalConsent

    override suspend fun setRegionalConsent(consented: Boolean) = settings.setRecsRegionalConsent(consented)

    override fun countryName(): String? = region.countryDisplayName()

    override fun sections(): Flow<List<ForYouSection>> = flow {
        val recent = runCatching { recents.recent(TASTE_LIMIT).first() }.getOrDefault(emptyList())
        val liked = runCatching { favorites.favoriteTracks().first() }.getOrDefault(emptyList())
        val hidden = optional { feedback?.hidden?.first() } ?: HiddenItems()
        val taste = (recent + liked).distinctBy { it.source }
            .filterNot { track -> track.nameKeys().any { it in hidden.artistKeys } }
        val seedNames = if (taste.size < SEED_HISTORY) optional { seeds?.seeds?.first() }.orEmpty() else emptyList()
        if (taste.isEmpty() && seedNames.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val stats = optional { recents.stats(STATS_LIMIT).first() }.orEmpty()
        val profile = stats.takeIf { it.isNotEmpty() }?.let { TasteProfile(it, liked, nowMs()) }

        // Picked **once**, here, and handed to both halves: the plan's titles and the real rows' titles
        // must be the same strings or the Home would swap a skeleton for a differently-titled row.
        val mixSeeds = mixSeeds(taste)
        val ranked = (rankedArtists(taste, profile) + seedNames.map { ArtistCredit(name = it) })
            .filter { ArtistNameMatching.key(it.name) !in hidden.artistKeys }
            .distinctBy { ArtistNameMatching.key(it.name) }
        val anchors = ranked.take(SIMILAR_ANCHORS)
        val playlistAnchors = if (discovery.isEmpty()) emptyList() else ranked.take(PLAYLIST_ANCHORS)
        val localGenres = localGenres(taste, profile)
        emit(plan(mixSeeds, anchors, playlistAnchors, localGenres.takeIf { discovery.isNotEmpty() }.orEmpty()))

        emit(
            supervisorScope {
                // Independent branches, all in flight at once; each anchor and each genre is its own
                // coroutine, so one artist Deezer can't resolve costs its row and nothing else.
                val mixes = async { mixSections(mixSeeds) }
                val because = async { becauseYouLike(anchors.firstOrNull()) }
                val similar = anchors.map { anchor -> async { similarTo(anchor) } }
                val playlists = playlistAnchors.map { anchor -> async { playlistsWith(anchor, hidden) } }
                // The release radar reads each anchor's discography, which also says which genre the
                // anchor records in — the fallback for tracks that carry no genre tag of their own.
                val discographies = async { discographies(ranked.take(RADAR_ANCHORS)) }
                val genres = async {
                    val fromDeezer = deezerGenres(discographies.await())
                    val picked = (localGenres + fromDeezer).distinct().take(GENRE_ROWS)
                    if (discovery.isEmpty()) emptyList() else picked.mapNotNull { genrePlaylists(it, hidden) }
                }
                val radar = async { releaseRadar(discographies.await(), hidden) }

                val playlistRows = playlists.map { it.await() }
                val genreRows = genres.await()
                buildList {
                    radar.await()?.let(::add)
                    playlistRows.getOrNull(0)?.let(::add)
                    addAll(mixes.await())
                    genreRows.getOrNull(0)?.let(::add)
                    because.await()?.let(::add)
                    playlistRows.drop(1).filterNotNull().forEach(::add)
                    genreRows.drop(1).forEach(::add)
                    similar.mapNotNull { it.await() }.forEach(::add)
                }
            },
        )
    }.flowOn(io)

    /**
     * The rows about to be built: real titles, no items yet, in the order the finished rows use. A
     * planned row whose source then comes back empty collapses — one row of movement, not a block.
     */
    private fun plan(
        seeds: List<Track>,
        anchors: List<ArtistCredit>,
        playlistAnchors: List<ArtistCredit>,
        genres: List<SoundGenre>,
    ): List<ForYouSection> = buildList {
        playlistAnchors.firstOrNull()?.let { add(ForYouSection.PlaylistsWith(anchorName = it.name)) }
        seeds.forEach { add(ForYouSection.Mix(seedTitle = it.title, items = emptyList())) }
        genres.firstOrNull()?.let { add(ForYouSection.GenrePlaylists(genre = it)) }
        // The Deezer-backed rows all hang off credited artists; with none, none of them can run.
        anchors.firstOrNull()?.let { add(ForYouSection.BecauseYouLike(artistName = it.name, items = emptyList())) }
        playlistAnchors.drop(1).forEach { add(ForYouSection.PlaylistsWith(anchorName = it.name)) }
        genres.drop(1).take(GENRE_ROWS - 1).forEach { add(ForYouSection.GenrePlaylists(genre = it)) }
        anchors.forEach { add(ForYouSection.SimilarTo(anchorName = it.name)) }
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

    /**
     * One anchor's neighborhood: Deezer's related artists, plus records by the closest of them. The two
     * halves ride one row so the carousel can interleave circles and covers — the mixed shape the ask
     * ("like a streaming app's feed") is about.
     */
    private suspend fun similarTo(anchor: ArtistCredit): ForYouSection.SimilarTo? = supervisorScope {
        val id = deezerArtistId(anchor) ?: return@supervisorScope null
        val related = runCatching { deezer.artistRelated(id, RELATED_PER_SEED).data.mapNotNull { it.toArtistRef() } }
            .getOrDefault(emptyList())
        val albums = albumsBy(related.take(ALBUM_SEEDS))
        val artists = related.take(SIMILAR_ARTISTS)
        ForYouSection.SimilarTo(anchorName = anchor.name, artists = artists, albums = albums.take(SIMILAR_ALBUMS))
            .takeIf { it.size >= MIN_ROW_ITEMS }
    }

    /**
     * "Playlists with <anchor>": each platform asked concurrently and isolated, then interleaved so the
     * row's head shows every platform that answered rather than whichever answered first.
     */
    private suspend fun playlistsWith(anchor: ArtistCredit, hidden: HiddenItems): ForYouSection.PlaylistsWith? {
        val target = DiscoveryAnchor(anchor.name, optional { deezerArtistId(anchor) })
        val lists = askAll { it.forArtist(target) }
        return DiscoveryMerge.interleave(lists.map { it.visible(hidden) }, ROW_ITEMS)
            .takeIf { it.size >= MIN_ROW_ITEMS }
            ?.let { ForYouSection.PlaylistsWith(anchorName = anchor.name, playlists = it) }
    }

    private suspend fun genrePlaylists(genre: SoundGenre, hidden: HiddenItems): ForYouSection.GenrePlaylists? {
        val lists = askAll { it.forGenre(genre) }
        return DiscoveryMerge.interleave(lists.map { it.visible(hidden) }, ROW_ITEMS)
            .takeIf { it.size >= MIN_ROW_ITEMS }
            ?.let { ForYouSection.GenrePlaylists(genre = genre, playlists = it) }
    }

    private suspend fun askAll(question: suspend (DiscoveryPlaylistSource) -> List<PlaylistRef>): List<List<PlaylistRef>> =
        supervisorScope {
            discovery.map { source -> async { optional { question(source) }.orEmpty() } }.awaitAll()
        }

    private fun List<PlaylistRef>.visible(hidden: HiddenItems): List<PlaylistRef> =
        if (hidden.playlistKeys.isEmpty()) this else filterNot { it.source.identityKey in hidden.playlistKeys }

    /** Each anchor's recent discography page, with the artist carried over (Deezer omits it on this endpoint). */
    private suspend fun discographies(anchors: List<ArtistCredit>): List<Discography> = supervisorScope {
        anchors.map { anchor ->
            async {
                val id = optional { deezerArtistId(anchor) } ?: return@async null
                val rows = optional { deezer.artistAlbums(id, DISCOGRAPHY_PAGE).data }.orEmpty()
                Discography(anchor, id, rows).takeIf { rows.isNotEmpty() }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * The release radar: anything an anchor released in the last [RADAR_DAYS] days, newest first.
     * Deezer's discography endpoint is not sorted by date (verified), so the whole page is read and
     * sorted here. A future-dated pre-release is not "new" yet and is left out.
     */
    private fun releaseRadar(discographies: List<Discography>, hidden: HiddenItems): ForYouSection.NewFromArtists? {
        val now = today()
        val from = now.minusDays(RADAR_DAYS)
        val fresh = discographies.flatMap { disc ->
            disc.albums.mapNotNull { row ->
                val date = row.releaseDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                if (date.isBefore(from) || date.isAfter(now)) return@mapNotNull null
                val album = row.toAlbumRef() ?: return@mapNotNull null
                val withArtist = if (album.artists.isEmpty()) {
                    album.copy(artists = listOf(ArtistRef(name = disc.anchor.name, source = DeezerIds.artist(disc.deezerId.toLongOrNull() ?: 0))))
                } else {
                    album
                }
                date to withArtist
            }
        }
        val albums = fresh.sortedByDescending { it.first }
            .map { it.second }
            .filterNot { album -> album.artists.any { ArtistNameMatching.key(it.name) in hidden.artistKeys } }
            .distinctBy { it.source.identityKey }
            .take(ROW_ITEMS)
        return albums.takeIf { it.isNotEmpty() }?.let { ForYouSection.NewFromArtists(albums = it) }
    }

    /**
     * Genres Deezer files the anchors' records under, strongest anchor first. Each anchor votes once,
     * with its most frequent genre — a remix album filed under Dance should not outvote eight studio
     * albums filed under Pop.
     */
    private fun deezerGenres(discographies: List<Discography>): List<SoundGenre> =
        discographies.mapNotNull { disc ->
            disc.albums.mapNotNull { it.genreId?.let(GenreCatalog::fromDeezerGenre) }
                .filter { it != SoundGenre.UNKNOWN }
                .groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key
        }.filter { it in GenreCatalog.browsable }.distinct()

    /**
     * Genres the taste's own tags already show — free, so they can be announced in the plan. From the
     * profile's facets when there is a log (strongest facet first), otherwise from tag counts.
     */
    private fun localGenres(taste: List<Track>, profile: TasteProfile?): List<SoundGenre> {
        val fromFacets = profile?.clusters()?.map { it.genre }.orEmpty()
        val fromTags = taste.map { it.soundGenre() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        return (fromFacets + fromTags)
            .filter { it != SoundGenre.UNKNOWN && it in GenreCatalog.browsable }
            .distinct()
            .take(GENRE_ROWS)
    }

    /**
     * Records by the given similar artists. Deezer's `/artist/{id}/albums` omits the artist on each
     * row (it's implied by the endpoint), so it's carried over from the seed — otherwise every card
     * would render with a blank subtitle.
     */
    private suspend fun albumsBy(related: List<ArtistRef>): List<AlbumRef> = supervisorScope {
        val perArtist = related
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
        // Round-robin rather than concatenated: a run of one artist's back catalogue reads as their
        // discography, not as a recommendation.
        (0 until (perArtist.maxOfOrNull { it.size } ?: 0))
            .flatMap { rank -> perArtist.mapNotNull { it.getOrNull(rank) } }
            .distinctBy { it.source }
    }

    /**
     * The taste's credited artists, strongest first.
     *
     * With a listening log, [TasteProfile.artists] decides: it weighs recency, replays and completions
     * and **penalizes skips**. Without one (or for credits the log doesn't cover) it falls back to how
     * often the name appears, which is what this used to do for everyone.
     */
    private fun rankedArtists(taste: List<Track>, profile: TasteProfile?): List<ArtistCredit> {
        val credits = taste.mapNotNull { it.artists.firstOrNull() }
            .filter { it.name.isNotBlank() }
            .groupBy { ArtistNameMatching.key(it.name) }
        val byCount = credits.values
            .sortedByDescending { it.size }
            .map { group -> group.firstOrNull { it.source != null } ?: group.first() }
        if (profile == null) return byCount
        val rank = profile.artists.withIndex().associate { (i, stat) -> stat.key to i }
        return byCount.sortedBy { rank[ArtistNameMatching.key(it.name)] ?: Int.MAX_VALUE }
    }

    /**
     * The raw Deezer id of a credited artist: its own ref, or a name lookup.
     *
     * Shared with the artist radio, and for the same reason: a recently-played track can easily be one
     * that came out of a YouTube Mix, whose credit is a channel name Deezer has never heard of.
     */
    private suspend fun deezerArtistId(credit: ArtistCredit): String? = artists.idFor(credit)

    /** A lookup that must never cost the feed: any failure is "not available", cancellation stays one. */
    private suspend fun <T> optional(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun Track.nameKeys(): List<String> = artists.map { ArtistNameMatching.key(it.name) }.filter { it.isNotEmpty() }

    private class Discography(val anchor: ArtistCredit, val deezerId: String, val albums: List<DeezerAlbumShortDto>)

    private companion object {
        const val TASTE_LIMIT = 25
        const val STATS_LIMIT = 200
        const val MIX_ROWS = 3
        const val ROW_ITEMS = 12
        const val MIN_ROW_ITEMS = 3

        /** One "Similar to" row per anchor — the feed's per-artist neighborhoods, like any streaming app. */
        const val SIMILAR_ANCHORS = 3
        const val RELATED_PER_SEED = 8

        /** How much of each half a row carries; interleaved in the UI, 12 cards max like every row. */
        const val SIMILAR_ARTISTS = 6
        const val SIMILAR_ALBUMS = 6

        /** Albums come from the first similar artists — enough for the row's album half, two calls. */
        const val ALBUM_SEEDS = 2
        const val ALBUMS_PER_SEED = 4

        /** "Playlists with <anchor>" rows, and "Your genre" rows. */
        const val PLAYLIST_ANCHORS = 2
        const val GENRE_ROWS = 2

        /** Release radar: this many anchors' discographies, releases from this many days back. */
        const val RADAR_ANCHORS = 8
        const val RADAR_DAYS = 30L
        const val DISCOGRAPHY_PAGE = 50

        /** Below this much history the cold-start seeds still stand in as anchors. */
        const val SEED_HISTORY = 5
    }
}
