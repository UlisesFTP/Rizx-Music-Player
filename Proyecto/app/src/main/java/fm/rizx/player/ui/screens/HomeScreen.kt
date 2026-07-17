package fm.rizx.player.ui.screens

import fm.rizx.player.ui.components.SectionHeader
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.HomeFeed
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DotMatrixSpinner
import fm.rizx.player.ui.components.RizxChip
import fm.rizx.player.ui.components.RizxIconButton
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.components.tintFor
import fm.rizx.player.ui.home.HomeUiState
import fm.rizx.player.ui.home.HomeViewModel
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.paperElevation
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal

/** What the Home feed is showing: [All] is the overview, the rest are one full category each. */
enum class HomeTab(val label: String) {
    All("All"),
    Songs("Songs"),
    ForYou("For you"),
    Albums("Albums"),
    Artists("Artists"),
}

/** How many items a carousel previews on the [HomeTab.All] overview before "See all". */
private const val PREVIEW_ITEMS = 10

/**
 * Charts & discovery.
 *
 * **All** is a scannable overview — a carousel per category, each with "See all" into its tab — and the
 * other tabs are the full 2-column grid for one category. Same shape as the Library: land on a summary
 * of everything, drill in when you know what you want. The old "See all" *screens* stay gone:
 * `AlbumsViewModel`/`ArtistsViewModel` re-loaded the very `dashboard.homeFeed()` this screen already
 * holds, so they were a second copy of this content; the tabs are the destination now.
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
    var tab by rememberSaveable { mutableStateOf(HomeTab.All) }

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
                        // App logo (matches the launcher icon: ivory square + coral corner on a dark tile).
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RectangleShape)
                                .background(Color(0xFF14110C))
                                .border(1.dp, c.line2, RectangleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = "Rizx logo",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text("Rizx", style = sg(13, FontWeight.Bold, -0.05f), color = c.text)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Good evening", style = mr(12, FontWeight.SemiBold), color = c.muted)
                        Text("Charts & discovery", style = sg(19, FontWeight.Bold, -0.01f), color = c.text)
                    }
                    RizxIconButton(RizxIcons.Search, "Search", onOpenSearch, background = c.elev, border = c.line, iconSize = 21.dp)
                    RizxIconButton(RizxIcons.Favorite, "Liked songs", onOpenLikes, background = c.elev, border = c.line, iconSize = 20.dp, tint = c.redAccent)
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
                                RizxChip(entry.label, active = tab == entry, onClick = { tab = entry })
                            }
                        }
                    }
                    tabContent(
                        s.feed, tab, onOpenAlbum, onOpenArtist, onOpenEditorialPlaylist, vm::playTrack,
                        onSeeAll = { tab = it },
                    )
                }

                HomeUiState.Loading -> item {
                    Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                        DotMatrixSpinner(color = c.accent, diameter = 34.dp)
                    }
                }
                HomeUiState.Offline -> item { HomeMessage("You're offline. Connect and pull the feed again.", vm::load) }
                is HomeUiState.Error -> item { HomeMessage(s.message, vm::load) }
            }

            item { Spacer(Modifier.height(176.dp)) }
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
) {
    when (tab) {
        HomeTab.All -> {
            val tracks = feed.topTracks.flatMap { it.items }
            val albums = feed.topAlbums.flatMap { it.items }
            val artists = feed.topArtists.flatMap { it.items }
            val playlists = feed.editorialPlaylists.flatMap { it.items }
            if (tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()) {
                item { HomeEmpty("Nothing in the charts right now.") }
            }

            carousel("Top songs", tracks, HomeTab.Songs, onSeeAll) { track ->
                CarouselCell(onClick = { onPlay(track) }) {
                    CoverArt(
                        tintFor(track.source.id), initial = null,
                        Modifier.size(CAROUSEL_ART).paperElevation(),
                        imageUrl = track.artwork.coverUrl(),
                    )
                    CellTitle(track.title)
                    CellSubtitle(track.artists.joinToString { it.name }.ifEmpty { "—" })
                }
            }

            carousel("Made for you", playlists, HomeTab.ForYou, onSeeAll) { playlist ->
                CarouselCell(onClick = { onOpenEditorialPlaylist(playlist) }) {
                    CoverArt(
                        tintFor(playlist.source.id), initial = playlist.name.take(1),
                        Modifier.size(CAROUSEL_ART).paperElevation(), initialSize = 40,
                        imageUrl = playlist.artwork.coverUrl(),
                    )
                    CellTitle(playlist.name)
                    CellSubtitle("Editorial")
                }
            }

            carousel("Top albums", albums, HomeTab.Albums, onSeeAll) { album ->
                CarouselCell(onClick = { onOpenAlbum(album.source) }) {
                    CoverArt(
                        tintFor(album.source.id), initial = album.title.take(1),
                        Modifier.size(CAROUSEL_ART).paperElevation(), initialSize = 40,
                        imageUrl = album.artwork.coverUrl(),
                    )
                    CellTitle(album.title)
                    CellSubtitle(album.artists.firstOrNull()?.name ?: "Album")
                }
            }

            carousel("Popular artists", artists, HomeTab.Artists, onSeeAll) { artist ->
                CarouselCell(onClick = { onOpenArtist(artist.source) }, centered = true) {
                    CoverArt(
                        tintFor(artist.source.id), initial = artist.name.take(1),
                        Modifier.size(CAROUSEL_ART).paperElevation(CircleShape),
                        initialSize = 38, circle = true, imageUrl = artist.artwork.coverUrl(),
                    )
                    CellTitle(artist.name, centered = true)
                }
            }
        }

        HomeTab.Songs -> {
            val tracks = feed.topTracks.flatMap { it.items }
            if (tracks.isEmpty()) item { HomeEmpty("No songs in the charts right now.") }
            browseGrid(tracks, key = { "tr-${it.source.identityKey}" }) { track, index ->
                GridCell(index, onClick = { onPlay(track) }) {
                    CoverArt(
                        tintFor(track.source.id), initial = null,
                        Modifier.fillMaxWidth().aspectRatio(1f).paperElevation(),
                        imageUrl = track.artwork.coverUrl(),
                    )
                    CellTitle(track.title)
                    CellSubtitle(track.artists.joinToString { it.name }.ifEmpty { "—" })
                }
            }
        }

        HomeTab.ForYou -> {
            val playlists = feed.editorialPlaylists.flatMap { it.items }
            // Editorial picks stand in until real, personalized recommendations exist — say so rather than
            // implying these were chosen for you.
            item {
                Text(
                    "Editorial picks for now — personalized recommendations are coming.",
                    style = mr(12, FontWeight.Medium),
                    color = RizxTheme.colors.muted,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp),
                )
            }
            if (playlists.isEmpty()) item { HomeEmpty("No picks right now.") }
            browseGrid(playlists, key = { "pl-${it.source.identityKey}" }) { playlist, index ->
                GridCell(index, onClick = { onOpenEditorialPlaylist(playlist) }) {
                    CoverArt(
                        tintFor(playlist.source.id), initial = playlist.name.take(1),
                        Modifier.fillMaxWidth().aspectRatio(1f).paperElevation(), initialSize = 46,
                        imageUrl = playlist.artwork.coverUrl(),
                    )
                    CellTitle(playlist.name)
                }
            }
        }

        HomeTab.Albums -> {
            val albums = feed.topAlbums.flatMap { it.items }
            if (albums.isEmpty()) item { HomeEmpty("No albums in the charts right now.") }
            browseGrid(albums, key = { "al-${it.source.identityKey}" }) { album, index ->
                GridCell(index, onClick = { onOpenAlbum(album.source) }) {
                    CoverArt(
                        tintFor(album.source.id), initial = album.title.take(1),
                        Modifier.fillMaxWidth().aspectRatio(1f).paperElevation(), initialSize = 46,
                        imageUrl = album.artwork.coverUrl(),
                    )
                    CellTitle(album.title)
                    CellSubtitle(album.artists.firstOrNull()?.name ?: "Album")
                }
            }
        }

        HomeTab.Artists -> {
            val artists = feed.topArtists.flatMap { it.items }
            if (artists.isEmpty()) item { HomeEmpty("No artists in the charts right now.") }
            browseGrid(artists, key = { "ar-${it.source.identityKey}" }) { artist, index ->
                GridCell(index, onClick = { onOpenArtist(artist.source) }, centered = true) {
                    CoverArt(
                        tintFor(artist.source.id), initial = artist.name.take(1),
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
    cell: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "hdr-$title") {
        SectionHeader(
            title,
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp),
            action = "See all",
            onAction = { onSeeAll(tab) },
        )
    }
    item(key = "car-$title") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Capped: the strip is a taste of the category — "See all" is right there for the rest.
            items(items.take(PREVIEW_ITEMS)) { item -> cell(item) }
        }
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
                "Retry", style = sg(14, FontWeight.Bold), color = c.onFill,
                modifier = Modifier.padding(top = 16.dp).background(c.fill).clickableScale(scale = 0.94f, onClick = onRetry).padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}
