package fm.rizx.player.ui.screens

import fm.rizx.player.ui.components.SectionHeader
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.AppMix
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.ForYouSection
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.MixKind
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DiscoverMosaic
import fm.rizx.player.ui.components.DiscoverMosaicSkeleton
import fm.rizx.player.ui.components.DotMatrixSpinner
import fm.rizx.player.ui.components.Eyebrow
import fm.rizx.player.ui.components.InkFrame
import fm.rizx.player.ui.components.MosaicTile
import fm.rizx.player.ui.components.PickMosaic
import fm.rizx.player.ui.components.RizxChip
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.components.mosaicRow
import fm.rizx.player.ui.components.mosaicRows
import fm.rizx.player.ui.components.mosaicWall
import fm.rizx.player.ui.components.tintFor
import fm.rizx.player.ui.home.HomeUiState
import fm.rizx.player.ui.home.HomeViewModel
import fm.rizx.player.ui.home.WovenBlock
import fm.rizx.player.ui.home.weaveHome
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.paperElevation
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal
import java.time.LocalDate

/**
 * What the Home feed is showing: [All] is the overview — charts *and* the "For you" recommendations,
 * which used to be a tab of their own — and the rest are one full category each.
 */
enum class HomeTab(val labelRes: Int) {
    All(R.string.home_tab_all),
    Songs(R.string.home_tab_songs),
    // Playlists earn a tab now that the feed carries sixty-odd of them (Apple's Top 100 per country,
    // its curated rows, Spotify's editorial, Deezer's regionals). Without a destination the overview
    // silently kept ten and dropped the rest.
    Playlists(R.string.home_tab_playlists),
    Albums(R.string.home_tab_albums),
    Artists(R.string.home_tab_artists),
}

/** How many items a carousel previews on the [HomeTab.All] overview before "See all". */
private const val PREVIEW_ITEMS = 10

/**
 * How many tiles the mosaic wall holds — as much of the Home as the mosaics may take before the charts
 * are pushed out of reach. Six also leaves the arrangement room to vary: it partitions into rows several
 * different ways, which is what the weave draws on.
 */
private const val MOSAIC_TILES = 6

/**
 * Playlist mosaics are floored at two, so the wall still reads as a wall on a cold start where the only
 * mix that can exist is the global one. Mixes take the rest.
 */
private const val MIN_PLAYLIST_TILES = 2

/** How many covers a collage tile uses. */
private const val COLLAGE_COVERS = 4

/**
 * What a playlist card says under its name: its track count when the source gave one, else the
 * generic label. With sixty playlists on offer, "100 songs" separates a country chart from a
 * four-track mood row far better than sixty identical "Editorial" captions.
 */
/**
 * A personalized row's heading. Shared by the real rows and by the skeletons that stand in for them,
 * so a row keeps one title — and therefore one LazyColumn key — from announcement to arrival.
 */
@Composable
private fun forYouTitle(section: ForYouSection): String = when (section) {
    is ForYouSection.Mix -> stringResource(R.string.home_mix_of, section.seedTitle)
    is ForYouSection.BecauseYouLike -> stringResource(R.string.home_because_you_like, section.artistName)
    is ForYouSection.ArtistsForYou -> stringResource(R.string.home_artists_for_you)
    is ForYouSection.AlbumsForYou -> stringResource(R.string.home_albums_for_you)
}

/** A mix's name. The domain holds no resources, so the kind and its subject are formatted here. */
@Composable
private fun mixTitle(mix: AppMix): String = when (mix.kind) {
    MixKind.DAILY -> stringResource(R.string.home_mix_daily)
    MixKind.ARTIST -> stringResource(R.string.home_mix_artist, mix.subject)
    MixKind.ON_REPEAT -> stringResource(R.string.home_mix_on_repeat)
    MixKind.REDISCOVER -> stringResource(R.string.home_mix_rediscover)
    MixKind.DISCOVERY -> stringResource(R.string.home_mix_discovery)
    MixKind.GLOBAL -> stringResource(R.string.home_mix_global)
}

/**
 * What the mix is made of, in numbers. Deliberately factual — the statistics behind a mix are the
 * reason to trust it, and a vague mood line would be inventing something the app does not know.
 */
@Composable
private fun mixCaption(mix: AppMix): String = when (mix.kind) {
    MixKind.DAILY -> stringResource(R.string.home_mix_daily_caption, mix.tracks.size, mix.artistCount)
    MixKind.ARTIST -> stringResource(R.string.home_mix_artist_caption, mix.tracks.size, mix.subject)
    MixKind.ON_REPEAT -> stringResource(R.string.home_mix_on_repeat_caption, mix.tracks.size)
    MixKind.REDISCOVER -> stringResource(R.string.home_mix_rediscover_caption, mix.tracks.size)
    MixKind.DISCOVERY -> stringResource(R.string.home_mix_discovery_caption, mix.tracks.size)
    MixKind.GLOBAL -> stringResource(R.string.home_mix_global_caption, mix.tracks.size)
}

@Composable
private fun playlistSubtitle(playlist: PlaylistRef): String =
    playlist.trackCount?.takeIf { it > 0 }
        ?.let { pluralStringResource(R.plurals.home_playlist_tracks, it, it) }
        ?: stringResource(R.string.home_editorial)

/**
 * Charts & discovery.
 *
 * **All** is a scannable overview — the mosaic wall of Rizx's own mixes and the best playlists, then a
 * carousel per category, each with "See all" into its tab — and the other tabs are the full 2-column
 * grid for one category. Same shape as the Library: land on a summary of everything, drill in when you
 * know what you want. The old "See all" *screens* stay gone: `AlbumsViewModel`/`ArtistsViewModel`
 * re-loaded the very `dashboard.homeFeed()` this screen already holds, so they were a second copy of
 * this content; the tabs are the destination now.
 */
@Composable
fun HomeScreen(
    onOpenSearch: () -> Unit,
    onOpenLikes: () -> Unit,
    onOpenAlbum: (ProviderRef) -> Unit,
    onOpenArtist: (ProviderRef) -> Unit,
    onOpenEditorialPlaylist: (PlaylistRef) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val state by vm.state.collectAsStateWithLifecycle()
    // Saved by name, not as the enum itself: restoring a constant that a later version removed (as
    // "For you" was) would throw on the way back from process death.
    var tabName by rememberSaveable { mutableStateOf(HomeTab.All.name) }
    val tab = HomeTab.entries.firstOrNull { it.name == tabName } ?: HomeTab.All
    // Hoisted here because `tabContent`/`carousel` below are plain LazyListScope builders, not
    // @Composable functions — stringResource() can only be called from this composable scope.
    val topSongsTitle = stringResource(R.string.home_top_songs)
    val topAlbumsTitle = stringResource(R.string.home_top_albums)
    val popularArtistsTitle = stringResource(R.string.home_popular_artists)
    val playlistsForYouTitle = stringResource(R.string.home_playlists_for_you)
    val newReleasesTitle = stringResource(R.string.home_new_releases)
    val forYouLabel = stringResource(R.string.home_tab_for_you)
    val mixesTitle = stringResource(R.string.home_mixes_section)
    val continueTitle = stringResource(R.string.home_continue_listening)
    val continueListening by vm.continueListening.collectAsStateWithLifecycle()
    // Localized titles for the personalized rows, resolved here for the same reason as above.
    val content = state as? HomeUiState.Content
    val forYouRows = content?.forYouSections.orEmpty().map { forYouTitle(it) to it }
    // Rows the feed has announced but not yet filled. They are drawn at their real height, so the
    // personalized half lands *in place* — it used to arrive as a screen of content shoved in above
    // whatever the user had started reading. Titles that already have a real row are dropped: the two
    // lists share LazyColumn keys, and a duplicate key is a crash.
    val forYouSkeletons = content?.forYouPending.orEmpty()
        .map { forYouTitle(it) }
        .filter { title -> forYouRows.none { it.first == title } }

    // ---- The mosaics ---------------------------------------------------------------------------
    // Built here, in composable scope, because every label and caption on them is localized; the wall
    // itself (`mosaicWall`) is pure layout and knows nothing about mixes or playlists.
    val homeMixes by vm.mixes.collectAsStateWithLifecycle()
    // What varies the overview's layout. Seeded from what the mixes are *about* plus the date, so the
    // arrangement is fixed while you read the screen, shifts as your listening does, and rotates daily.
    // Deliberately **not** the tile keys: those carry the feed's playlists, and re-laying the page out
    // under the reader every time the charts refresh underneath them is not "organic", it is a jump.
    val mixSignature = homeMixes.mixes.joinToString("|") { it.id }
    val layoutSeed = remember(mixSignature) {
        mixSignature.hashCode() * 31 + LocalDate.now().toEpochDay().toInt()
    }
    val mosaicPadding = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp)
    val heroMix = homeMixes.mixes.firstOrNull()
    val hero: (@Composable () -> Unit)? = if (heroMix == null) null else {
        {
            val title = mixTitle(heroMix)
            PickMosaic(
                eyebrow = stringResource(R.string.home_rizx_pick),
                title = title,
                subtitle = heroMix.subject,
                caption = mixCaption(heroMix),
                playLabel = stringResource(R.string.home_play_now),
                coverUrl = heroMix.leadTrack?.artwork.coverUrl(),
                tintKey = heroMix.id,
                weight = heroMix.weight,
                modifier = mosaicPadding,
                onClick = { vm.playMix(heroMix, title) },
            )
        }
    }
    val playlists = content?.feed?.editorialPlaylists.orEmpty().flatMap { it.items }
    // Mixes lead the wall; playlists fill the rest of it, never fewer than [MIN_PLAYLIST_TILES] so the
    // ask — "some playlists as widget mosaics" — holds even when the statistics have plenty to say.
    val wallMixes = homeMixes.mixes.drop(1).take(MOSAIC_TILES - MIN_PLAYLIST_TILES)
    val mosaicPlaylists = playlists.take(MOSAIC_TILES - wallMixes.size)
    val mixLabel = stringResource(R.string.home_mix_label)
    val playlistLabel = stringResource(R.string.home_editorial)
    val mosaicTiles = wallMixes.map { mix ->
        val title = mixTitle(mix)
        MosaicTile(
            key = mix.id,
            label = mixLabel,
            title = title,
            caption = mixCaption(mix),
            covers = mix.tracks.mapNotNull { it.artwork.coverUrl() }.distinct().take(COLLAGE_COVERS),
            tintKey = mix.id,
            weight = mix.weight,
            onClick = { vm.playMix(mix, title) },
        )
    } + mosaicPlaylists.map { playlist ->
        MosaicTile(
            key = "pl-${playlist.source.identityKey}",
            label = playlistLabel,
            title = playlist.name,
            caption = playlistSubtitle(playlist),
            covers = listOf(playlist.artwork.coverUrl()),
            tintKey = playlist.source.id,
            // No meter: a playlist someone else curated has no statistics of ours behind it.
            weight = null,
            onClick = { onOpenEditorialPlaylist(playlist) },
        )
    }
    // The day's single recommendation. It is the one mosaic that depends on the slow personalized half,
    // so while that half is still announcing itself the card holds its own height — same key, same slot,
    // filled in place. Otherwise it would drop a poster in above whatever the user was reading.
    val pick = homeMixes.pick
    val discover: (@Composable () -> Unit)? = if (pick != null) {
        {
            DiscoverMosaic(
                eyebrow = stringResource(R.string.home_daily_discover),
                title = pick.track.title,
                artist = pick.track.artists.joinToString { it.name }.ifEmpty { "—" },
                reason = stringResource(R.string.home_similar_to, pick.becauseOf),
                coverUrl = pick.track.artwork.coverUrl(),
                tintKey = pick.track.source.id,
                modifier = mosaicPadding,
                onClick = { vm.playTrack(pick.track) },
            )
        }
    } else if (forYouSkeletons.isNotEmpty()) {
        { DiscoverMosaicSkeleton(mosaicPadding) }
    } else {
        null
    }

    Box(Modifier.fillMaxSize()) {
        Text(
            "Rizx", style = sg(120, FontWeight.Bold, -0.05f), color = c.text.copy(alpha = 0.06f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
        )
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        // App logo — the launcher icon in miniature: the logomark on the brand's ivory paper.
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RectangleShape)
                                .background(Color(0xFFF3ECE2))
                                .border(1.dp, c.line2, RectangleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = stringResource(R.string.home_logo_cd),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text("Rizx", style = sg(13, FontWeight.Bold, -0.05f), color = c.text)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.home_greeting), style = mr(12, FontWeight.SemiBold), color = c.muted)
                        Text(stringResource(R.string.home_subtitle), style = sg(19, FontWeight.Bold, -0.01f), color = c.text)
                    }
                    RizxIconButton(RizxIcons.Search, stringResource(R.string.action_search), onOpenSearch, background = c.elev, border = c.line, iconSize = 21.dp)
                    RizxIconButton(RizxIcons.Favorite, stringResource(R.string.home_liked_songs_cd), onOpenLikes, background = c.elev, border = c.line, iconSize = 20.dp, tint = c.redAccent)
                }
            }

            when (val s = state) {
                is HomeUiState.Content -> {
                    // Tabs only once there's a feed to filter — they'd be inert while loading or offline.
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HomeTab.entries.forEach { entry ->
                                RizxChip(stringResource(entry.labelRes), active = tab == entry, onClick = { tabName = entry.name })
                            }
                        }
                    }
                    // "Continue listening" — under the tabs, over everything the feed brings. It sits
                    // outside `tabContent` so it stays put as you switch tabs, and it is local and
                    // instant: no load state, nothing to wait for. Empty history draws nothing at all
                    // rather than an empty shelf.
                    trackCarousel(continueTitle, continueListening, vm::playTrack)
                    tabContent(
                        s.feed, tab, onOpenAlbum, onOpenArtist, onOpenEditorialPlaylist, vm::playTrack,
                        onSeeAll = { tabName = it.name },
                        topSongsTitle = topSongsTitle,
                        topAlbumsTitle = topAlbumsTitle,
                        popularArtistsTitle = popularArtistsTitle,
                        newReleasesTitle = newReleasesTitle,
                        forYouRows = forYouRows,
                        forYouSkeletons = forYouSkeletons,
                        playlistsForYouTitle = playlistsForYouTitle,
                        forYouLabel = forYouLabel,
                        mixesTitle = mixesTitle,
                        hero = hero,
                        mosaicTiles = mosaicTiles,
                        discover = discover,
                        promotedPlaylists = mosaicPlaylists.size,
                        layoutSeed = layoutSeed,
                        regionalConsent = s.regionalConsent,
                        countryName = s.countryName,
                        onSetRegionalConsent = vm::setRegionalConsent,
                    )
                }

                HomeUiState.Loading -> item {
                    Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                        DotMatrixSpinner(color = c.accent, diameter = 34.dp)
                    }
                }
                // No feed, but the history is local — offline is exactly when a way back into what you
                // were playing is worth most, so the row outlives the charts here. The mixes built from
                // that same history outlive it too.
                // Retry means "go to the network", not "re-read the cache we just failed to fill".
                HomeUiState.Offline -> {
                    trackCarousel(continueTitle, continueListening, vm::playTrack)
                    if (hero != null) item(key = "pick-mosaic") { hero() }
                    mosaicWall(mosaicTiles, layoutSeed)
                    item { HomeMessage(stringResource(R.string.home_offline_message), vm::refresh) }
                }
                is HomeUiState.Error -> {
                    trackCarousel(continueTitle, continueListening, vm::playTrack)
                    if (hero != null) item(key = "pick-mosaic") { hero() }
                    mosaicWall(mosaicTiles, layoutSeed)
                    item { HomeMessage(s.message, vm::refresh) }
                }
            }

            item { Spacer(Modifier.height(LocalBottomInset.current + 16.dp)) }
        }
    }
}

private fun LazyListScope.tabContent(
    feed: HomeFeed,
    tab: HomeTab,
    onOpenAlbum: (ProviderRef) -> Unit,
    onOpenArtist: (ProviderRef) -> Unit,
    onOpenEditorialPlaylist: (PlaylistRef) -> Unit,
    onPlay: (Track) -> Unit,
    onSeeAll: (HomeTab) -> Unit,
    topSongsTitle: String,
    topAlbumsTitle: String,
    popularArtistsTitle: String,
    newReleasesTitle: String,
    forYouRows: List<Pair<String, ForYouSection>>,
    forYouSkeletons: List<String>,
    playlistsForYouTitle: String,
    forYouLabel: String,
    mixesTitle: String,
    /** The pick band and the daily poster, already localized — see [HomeScreen]. */
    hero: (@Composable () -> Unit)?,
    mosaicTiles: List<MosaicTile>,
    discover: (@Composable () -> Unit)?,
    /** How many playlists the wall took, so the carousel below doesn't show them twice. */
    promotedPlaylists: Int,
    /** Varies the overview's arrangement; stable for as long as the reader is on the screen. */
    layoutSeed: Int,
    regionalConsent: Boolean?,
    countryName: String?,
    onSetRegionalConsent: (Boolean) -> Unit,
) {
    when (tab) {
        HomeTab.All -> {
            val tracks = feed.topTracks.flatMap { it.items }
            val albums = feed.topAlbums.flatMap { it.items }
            val artists = feed.topArtists.flatMap { it.items }
            val playlists = feed.editorialPlaylists.flatMap { it.items }
            val newReleases = feed.newReleases.flatMap { it.items }
            if (tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() &&
                playlists.isEmpty() && forYouRows.isEmpty() && mosaicTiles.isEmpty() && hero == null
            ) {
                item { HomeEmpty(stringResource(R.string.home_empty_charts)) }
            }

            // ---- "For you": everything picked for this listener, under one label. Its own tab was
            // folded in here — the songs, artists, albums and playlists chosen for you read better as
            // the top of the overview than as a place you had to go looking for. ----
            if (forYouRows.isNotEmpty() || forYouSkeletons.isNotEmpty() || playlists.isNotEmpty() ||
                hero != null || mosaicTiles.isNotEmpty()
            ) {
                item(key = "for-you-label") {
                    Eyebrow(
                        forYouLabel,
                        Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 2.dp),
                    )
                }
            }

            // The pick band leads, always: it is the pick, and the strongest thing on the screen does not
            // belong in a lottery.
            if (hero != null) item(key = "pick-mosaic") { hero() }

            // ---- The woven overview ----------------------------------------------------------------
            // Mosaic rows and strips alternate, one or two of each at a time, in an order varied by
            // `layoutSeed`. It used to be the whole tile wall followed by six carousels in a fixed order,
            // which reads as a template no matter how good the tiles are.
            val rows = mosaicRows(mosaicTiles, layoutSeed)
            val strips = buildList {
                // One entry per personalized row, real or the skeleton standing in for it — keyed by the
                // title, so the skeleton is woven into exactly the slot its row will land in.
                forYouRows.forEach { (title, section) ->
                    add(Strip("fy-$title") { forYouStrip(title, section, onPlay, onOpenAlbum, onOpenArtist) })
                }
                forYouSkeletons.forEach { title -> add(Strip("fy-$title") { skeletonCarousel(title) }) }
                if (discover != null) add(Strip("daily-pick") { item(key = "daily-pick") { discover() } })
                add(
                    Strip("playlists") {
                        carousel(
                            playlistsForYouTitle,
                            // The wall already shows the first few as mosaics; showing them again here
                            // would be the same playlist twice in one screen.
                            playlists.drop(promotedPlaylists),
                            HomeTab.Playlists,
                            onSeeAll,
                            key = { it.source.identityKey },
                        ) { playlist ->
                            CarouselCell(onClick = { onOpenEditorialPlaylist(playlist) }) {
                                HomeCover(
                                    playlist.source.id, initial = playlist.name.take(1),
                                    Modifier.size(CAROUSEL_ART).paperElevation(), initialSize = 40,
                                    imageUrl = playlist.artwork.coverUrl(),
                                )
                                CellTitle(playlist.name)
                                CellSubtitle(playlistSubtitle(playlist))
                            }
                        }
                    },
                )
                // The charts, each with "See all" into its own tab. Added unconditionally even when a
                // chart came back empty — `carousel` draws nothing then, and keeping the strip list the
                // same length whatever the network returned is what stops the weave shifting around.
                add(
                    Strip("songs") {
                        carousel(topSongsTitle, tracks, HomeTab.Songs, onSeeAll, key = { it.source.identityKey }) { track ->
                            CarouselCell(onClick = { onPlay(track) }) {
                                HomeCover(
                                    track.source.id, initial = null,
                                    Modifier.size(CAROUSEL_ART).paperElevation(),
                                    imageUrl = track.artwork.coverUrl(),
                                )
                                CellTitle(track.title)
                                CellSubtitle(track.artists.joinToString { it.name }.ifEmpty { "—" })
                            }
                        }
                    },
                )
                add(
                    Strip("albums") {
                        carousel(topAlbumsTitle, albums, HomeTab.Albums, onSeeAll, key = { it.source.identityKey }) { album ->
                            CarouselCell(onClick = { onOpenAlbum(album.source) }) {
                                HomeCover(
                                    album.source.id, initial = album.title.take(1),
                                    Modifier.size(CAROUSEL_ART).paperElevation(), initialSize = 40,
                                    imageUrl = album.artwork.coverUrl(),
                                )
                                CellTitle(album.title)
                                CellSubtitle(album.artists.firstOrNull()?.name ?: stringResource(R.string.home_generic_album))
                            }
                        }
                    },
                )
                add(
                    Strip("artists") {
                        carousel(popularArtistsTitle, artists, HomeTab.Artists, onSeeAll, key = { it.source.identityKey }) { artist ->
                            CarouselCell(onClick = { onOpenArtist(artist.source) }, centered = true) {
                                HomeCover(
                                    artist.source.id, initial = artist.name.take(1),
                                    Modifier.size(CAROUSEL_ART).paperElevation(CircleShape),
                                    initialSize = 38, circle = true, imageUrl = artist.artwork.coverUrl(),
                                )
                                CellTitle(artist.name, centered = true)
                            }
                        }
                    },
                )
                // New releases were being fetched, blended and deduped on every load, then never drawn.
                // They share the Albums tab's grid because that is what they are.
                add(
                    Strip("new-releases") {
                        carousel(newReleasesTitle, newReleases, HomeTab.Albums, onSeeAll, key = { "nr-${it.source.identityKey}" }) { album ->
                            CarouselCell(onClick = { onOpenAlbum(album.source) }) {
                                HomeCover(
                                    album.source.id, initial = album.title.take(1),
                                    Modifier.size(CAROUSEL_ART).paperElevation(), initialSize = 40,
                                    imageUrl = album.artwork.coverUrl(),
                                )
                                CellTitle(album.title)
                                CellSubtitle(album.artists.firstOrNull()?.name ?: stringResource(R.string.home_generic_album))
                            }
                        }
                    },
                )
            }
            val byKey = strips.associateBy { it.key }
            val scope = this
            weaveHome(rows.size, strips.map { it.key }, layoutSeed).forEach { block ->
                when (block) {
                    is WovenBlock.Tiles -> {
                        // The header belongs to the first row of tiles only. Past that the tiles' own
                        // MIX / EDITORIAL plates say what they are, and a second heading over a row that
                        // follows a chart strip would be claiming to label everything under it.
                        if (block.row == 0) {
                            item(key = "mixes-header") {
                                SectionHeader(
                                    mixesTitle,
                                    Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 4.dp),
                                )
                            }
                        }
                        mosaicRow(rows[block.row], block.row)
                    }
                    is WovenBlock.Strip -> byKey[block.key]?.emit?.invoke(scope)
                }
            }

            // The consent ask sits *after* the music, not before it. It improves the personalised
            // rows, so it belongs next to them — opening a music app on a permission card is the
            // wrong first impression, and this one is easy to miss precisely because it can wait.
            if (regionalConsent == null) {
                item(key = "region-consent") {
                    RegionConsentCard(
                        countryName = countryName,
                        onAccept = { onSetRegionalConsent(true) },
                        onDecline = { onSetRegionalConsent(false) },
                    )
                }
            }
        }

        HomeTab.Playlists -> {
            val playlists = feed.editorialPlaylists.flatMap { it.items }
            if (playlists.isEmpty()) item { HomeEmpty(stringResource(R.string.home_empty_playlists)) }
            browseGrid(playlists, key = { "pl-${it.source.identityKey}" }) { playlist, index ->
                GridCell(index, onClick = { onOpenEditorialPlaylist(playlist) }) {
                    HomeCover(
                        playlist.source.id, initial = playlist.name.take(1),
                        Modifier.fillMaxWidth().aspectRatio(1f).paperElevation(), initialSize = 40,
                        imageUrl = playlist.artwork.coverUrl(),
                    )
                    CellTitle(playlist.name)
                    CellSubtitle(playlistSubtitle(playlist))
                }
            }
        }

        HomeTab.Songs -> {
            val tracks = feed.topTracks.flatMap { it.items }
            if (tracks.isEmpty()) item { HomeEmpty(stringResource(R.string.home_empty_songs)) }
            browseGrid(tracks, key = { "tr-${it.source.identityKey}" }) { track, index ->
                GridCell(index, onClick = { onPlay(track) }) {
                    HomeCover(
                        track.source.id, initial = null,
                        Modifier.fillMaxWidth().aspectRatio(1f).paperElevation(),
                        imageUrl = track.artwork.coverUrl(),
                    )
                    CellTitle(track.title)
                    CellSubtitle(track.artists.joinToString { it.name }.ifEmpty { "—" })
                }
            }
        }

        HomeTab.Albums -> {
            val albums = feed.topAlbums.flatMap { it.items }
            if (albums.isEmpty()) item { HomeEmpty(stringResource(R.string.home_empty_albums)) }
            browseGrid(albums, key = { "al-${it.source.identityKey}" }) { album, index ->
                GridCell(index, onClick = { onOpenAlbum(album.source) }) {
                    HomeCover(
                        album.source.id, initial = album.title.take(1),
                        Modifier.fillMaxWidth().aspectRatio(1f).paperElevation(), initialSize = 46,
                        imageUrl = album.artwork.coverUrl(),
                    )
                    CellTitle(album.title)
                    CellSubtitle(album.artists.firstOrNull()?.name ?: stringResource(R.string.home_generic_album))
                }
            }
        }

        HomeTab.Artists -> {
            val artists = feed.topArtists.flatMap { it.items }
            if (artists.isEmpty()) item { HomeEmpty(stringResource(R.string.home_empty_artists)) }
            browseGrid(artists, key = { "ar-${it.source.identityKey}" }) { artist, index ->
                GridCell(index, onClick = { onOpenArtist(artist.source) }, centered = true) {
                    HomeCover(
                        artist.source.id, initial = artist.name.take(1),
                        Modifier.fillMaxWidth().aspectRatio(1f).paperElevation(CircleShape),
                        initialSize = 42, circle = true, imageUrl = artist.artwork.coverUrl(),
                    )
                    CellTitle(artist.name, centered = true)
                }
            }
        }
    }
}

/** Carousel artwork size — big enough to read at a glance, small enough that ~2.5 peek past the edge. */
private val CAROUSEL_ART = 152.dp

/**
 * One woven block of the overview that isn't a mosaic row: a carousel, the skeleton standing in for one,
 * or the daily poster.
 *
 * [key] is what fixes where the weave puts it — it must name the *content*, not the position, so a strip
 * that turns out empty and drops out doesn't reorder everything around it. See [weaveHome].
 */
private class Strip(val key: String, val emit: LazyListScope.() -> Unit)

/** One personalized row, whichever of the four shapes it happens to be. */
private fun LazyListScope.forYouStrip(
    title: String,
    section: ForYouSection,
    onPlay: (Track) -> Unit,
    onOpenAlbum: (ProviderRef) -> Unit,
    onOpenArtist: (ProviderRef) -> Unit,
) = when (section) {
    is ForYouSection.Mix -> trackCarousel(title, section.items, onPlay)
    is ForYouSection.BecauseYouLike -> trackCarousel(title, section.items, onPlay)
    is ForYouSection.ArtistsForYou -> carousel(title, section.items, key = { it.source.identityKey }) { artist ->
        CarouselCell(onClick = { onOpenArtist(artist.source) }, centered = true) {
            HomeCover(
                artist.source.id, initial = artist.name.take(1),
                Modifier.size(CAROUSEL_ART).paperElevation(CircleShape),
                initialSize = 38, circle = true, imageUrl = artist.artwork.coverUrl(),
            )
            CellTitle(artist.name, centered = true)
        }
    }
    is ForYouSection.AlbumsForYou -> carousel(title, section.items, key = { it.source.identityKey }) { album ->
        CarouselCell(onClick = { onOpenAlbum(album.source) }) {
            HomeCover(
                album.source.id, initial = album.title.take(1),
                Modifier.size(CAROUSEL_ART).paperElevation(), initialSize = 40,
                imageUrl = album.artwork.coverUrl(),
            )
            CellTitle(album.title)
            CellSubtitle(album.artists.firstOrNull()?.name ?: stringResource(R.string.home_generic_album))
        }
    }
}

/**
 * A Home cover: [CoverArt] wearing the **ink frame**.
 *
 * Every album, song, artist and playlist on this screen goes through here, so the black margin that makes
 * them read as brutalist blocks is one decision rather than a dozen call sites — and denser surfaces
 * elsewhere in the app keep the hairline that suits them.
 */
@Composable
private fun HomeCover(
    tintKey: String,
    initial: String?,
    modifier: Modifier = Modifier,
    initialSize: Int = 52,
    circle: Boolean = false,
    imageUrl: String? = null,
) = CoverArt(
    tintFor(tintKey),
    initial,
    modifier,
    initialSize,
    circle = circle,
    imageUrl = imageUrl,
    borderColor = RizxTheme.colors.hardLine,
    borderWidth = InkFrame,
)

/**
 * One titled row of the [HomeTab.All] overview: a header with "See all" into the category's own tab,
 * then a horizontally-scrolling strip.
 *
 * A `LazyRow` inside the outer `LazyColumn`, so a 50-item chart composes the ~3 cells you can see rather
 * than all of them. Absent categories draw nothing at all — no empty header.
 */
private fun <T> LazyListScope.carousel(
    title: String,
    items: List<T>,
    tab: HomeTab,
    onSeeAll: (HomeTab) -> Unit,
    key: (T) -> Any,
    cell: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "hdr-$title") {
        SectionHeader(
            title,
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp),
            action = stringResource(R.string.action_see_all),
            onAction = { onSeeAll(tab) },
        )
    }
    item(key = "car-$title") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Capped: the strip is a taste of the category — "See all" is right there for the rest.
            // Keyed by provider identity so a refresh that reorders a strip moves the cells that exist
            // instead of rebuilding (and re-fetching the artwork of) every one of them.
            items(items.take(PREVIEW_ITEMS), key = key) { item -> cell(item) }
        }
    }
}

/** [carousel] without a "See all" action — the personalized For-you rows have no backing tab. */
private fun <T> LazyListScope.carousel(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    cell: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "hdr-$title") {
        SectionHeader(
            title,
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp),
        )
    }
    item(key = "car-$title") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items.take(PREVIEW_ITEMS), key = key) { item -> cell(item) }
        }
    }
}

/** How many placeholder cells a skeleton row draws — enough to fill the widest phone. */
private const val SKELETON_CELLS = 4

/**
 * A row whose title is already known but whose cards are not yet: the real [SectionHeader] over a
 * strip of placeholder cells built the same size as the real ones.
 *
 * It shares its item keys with [carousel], so when the row lands the LazyColumn reuses these very
 * slots — the header never redraws and the cards swap in without a single pixel of movement. That is
 * the whole point: the For-you block is the slowest half of the Home, and arriving after the charts
 * it used to insert ~900dp above them while a 72dp spinner was all that had been reserved.
 */
private fun LazyListScope.skeletonCarousel(title: String) {
    item(key = "hdr-$title") {
        SectionHeader(
            title,
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp),
        )
    }
    item(key = "car-$title") {
        // One pulse drives the whole row: every cell in it is waiting on the same request.
        val alpha by rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0.30f,
            targetValue = 0.70f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "skeletonAlpha",
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            userScrollEnabled = false, // nothing to scroll to yet
        ) {
            items(SKELETON_CELLS) { SkeletonCell(alpha) }
        }
    }
}

/**
 * One placeholder card, laid out exactly like [CarouselCell] + [CoverArt] + [CellTitle] +
 * [CellSubtitle]: same width, same artwork box, and text bars measured by rendering the real type
 * styles invisibly. Matching the height by construction rather than by a hand-tuned dp is what makes
 * the swap free — a guessed constant drifts the moment the type scale changes.
 */
@Composable
private fun SkeletonCell(alpha: Float) {
    val c = RizxTheme.colors
    Column(Modifier.width(CAROUSEL_ART)) {
        Box(
            Modifier
                .size(CAROUSEL_ART)
                .paperElevation()
                .background(c.elev)
                .border(InkFrame, c.hardLine, RectangleShape),
        )
        SkeletonLine(mr(14, FontWeight.SemiBold), widthFraction = 0.85f, top = 9.dp, alpha = alpha)
        SkeletonLine(mr(12, FontWeight.Medium), widthFraction = 0.55f, top = 0.dp, alpha = alpha)
    }
}

/** A text-shaped bar: a transparent glyph in the real style reserves the line, the bar paints over it. */
@Composable
private fun SkeletonLine(style: TextStyle, widthFraction: Float, top: Dp, alpha: Float) {
    val c = RizxTheme.colors
    Box(Modifier.padding(top = top).fillMaxWidth(widthFraction)) {
        Text("A", style = style, color = Color.Transparent, maxLines = 1)
        Box(
            Modifier
                .matchParentSize()
                .padding(vertical = 2.dp)
                .background(c.line2.copy(alpha = alpha)),
        )
    }
}

/** A For-you row of playable tracks (Mix / Because-you-like), in the standard carousel cell. */
private fun LazyListScope.trackCarousel(title: String, tracks: List<Track>, onPlay: (Track) -> Unit) =
    carousel(title, tracks, key = { it.source.identityKey }) { track ->
        CarouselCell(onClick = { onPlay(track) }) {
            HomeCover(
                track.source.id, initial = null,
                Modifier.size(CAROUSEL_ART).paperElevation(),
                imageUrl = track.artwork.coverUrl(),
            )
            CellTitle(track.title)
            CellSubtitle(track.artists.joinToString { it.name }.ifEmpty { "—" })
        }
    }

/**
 * The discreet regional-recommendations ask (owner decision: an in-app card, never an OS dialog —
 * the country comes from the SIM / device language, no location involved). Shown only while the
 * choice is undecided; accepting or declining persists it, and Settings can change it later.
 */
@Composable
private fun RegionConsentCard(countryName: String?, onAccept: () -> Unit, onDecline: () -> Unit) {
    val c = RizxTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 16.dp)
            .border(1.5.dp, c.hardLine, RectangleShape)
            .background(c.elev)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.home_region_consent_title), style = sg(16, FontWeight.Bold), color = c.text)
        Text(
            if (countryName != null) {
                stringResource(R.string.home_region_consent_body, countryName)
            } else {
                stringResource(R.string.home_region_consent_body_unknown)
            },
            style = mr(12, FontWeight.Medium),
            color = c.muted,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConsentButton(stringResource(R.string.home_region_consent_accept), filled = true, onClick = onAccept)
            ConsentButton(stringResource(R.string.home_region_consent_decline), filled = false, onClick = onDecline)
        }
    }
}

@Composable
private fun ConsentButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Box(
        Modifier
            .clickableScale(scale = 0.97f, onClick = onClick)
            .border(1.5.dp, c.hardLine, RectangleShape)
            .background(if (filled) c.text else c.elev)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(label, style = mr(12, FontWeight.Bold), color = if (filled) c.elev else c.text)
    }
}

@Composable
private fun CarouselCell(
    onClick: () -> Unit,
    centered: Boolean = false,
    content: @Composable () -> Unit,
) = Column(
    Modifier.width(CAROUSEL_ART).clickableScale(scale = 0.98f, onClick = onClick),
    horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
) { content() }

/** The app's 2-column browse grid, virtualized a row at a time. */
private fun <T> LazyListScope.browseGrid(
    items: List<T>,
    key: (T) -> String,
    cell: @Composable (item: T, index: Int) -> Unit,
) {
    itemsIndexed(items.chunked(2), key = { _, row -> "row-${key(row.first())}" }) { rowIndex, rowItems ->
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 9.dp, bottom = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            rowItems.forEachIndexed { colIndex, item ->
                Box(Modifier.weight(1f)) { cell(item, rowIndex * 2 + colIndex) }
            }
            if (rowItems.size == 1) Spacer(Modifier.weight(1f)) // keep the last odd cell half-width
        }
    }
}

@Composable
private fun GridCell(
    index: Int,
    onClick: () -> Unit,
    centered: Boolean = false,
    content: @Composable () -> Unit,
) = Column(
    Modifier.fillMaxWidth().staggeredReveal(index).clickableScale(scale = 0.98f, onClick = onClick),
    horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
) { content() }

@Composable
private fun CellTitle(text: String, centered: Boolean = false) = Text(
    text,
    style = mr(14, FontWeight.SemiBold),
    color = RizxTheme.colors.text,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    textAlign = if (centered) TextAlign.Center else null,
    modifier = Modifier.padding(top = 9.dp),
)

@Composable
private fun CellSubtitle(text: String) = Text(
    text,
    style = mr(12, FontWeight.Medium),
    color = RizxTheme.colors.muted,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

@Composable
private fun HomeEmpty(text: String) = Text(
    text,
    style = mr(13, FontWeight.Medium),
    color = RizxTheme.colors.muted,
    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp),
)

@Composable
private fun HomeMessage(text: String, onRetry: () -> Unit) {
    val c = RizxTheme.colors
    Box(Modifier.fillMaxWidth().height(300.dp).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = mr(14, FontWeight.Medium), color = c.muted, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.action_retry), style = sg(14, FontWeight.Bold), color = c.onFill,
                modifier = Modifier.padding(top = 16.dp).background(c.fill).clickableScale(scale = 0.94f, onClick = onRetry).padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}
