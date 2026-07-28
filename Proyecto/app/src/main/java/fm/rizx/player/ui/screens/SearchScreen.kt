@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package fm.rizx.player.ui.screens

import fm.rizx.player.ui.search.SearchTab
import fm.rizx.player.ui.components.RizxChip
import fm.rizx.player.data.remote.deezer.DeezerIds
import fm.rizx.player.data.remote.youtube.YoutubeIds
import fm.rizx.player.data.remote.soundcloud.SoundcloudIds
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fm.rizx.player.R
import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.PlaylistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.ui.components.CoverArt
import fm.rizx.player.ui.components.DotMatrixSpinner
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.ui.components.clickableScale
import fm.rizx.player.ui.icons.RizxIcons
import fm.rizx.player.ui.search.SearchUiState
import fm.rizx.player.ui.search.SearchViewModel
import fm.rizx.player.ui.theme.LocalBottomInset
import fm.rizx.player.ui.theme.RizxTheme
import fm.rizx.player.ui.theme.catBg
import fm.rizx.player.ui.theme.code
import fm.rizx.player.ui.theme.mr
import fm.rizx.player.ui.theme.paperElevation
import fm.rizx.player.ui.theme.sg
import fm.rizx.player.ui.theme.staggeredReveal

@Composable
fun SearchScreen(
    onOpenQueue: () -> Unit,
    onPlay: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddNext: (Track) -> Unit,
    onOpenAlbum: (fm.rizx.player.domain.model.ProviderRef) -> Unit,
    onOpenArtist: (fm.rizx.player.domain.model.ProviderRef) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    queueCount: Int,
    vm: SearchViewModel = hiltViewModel(),
) {
    val c = RizxTheme.colors
    val query by vm.query.collectAsStateWithLifecycle()
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val favoriteSources by vm.favoriteSources.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 22.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.action_search), style = sg(28, FontWeight.Bold, -0.02f), color = c.text, modifier = Modifier.weight(1f))
            if (queueCount > 0) QueueChip(count = queueCount, onClick = onOpenQueue)
        }

        // The suggestions hang *over* the tabs rather than pushing them down: a hint shouldn't move the
        // page under the user's thumb while they type.
        var fieldHeightPx by remember { mutableIntStateOf(0) }
        // zIndex belongs on this Box, not on the list inside it: it orders siblings *within the Column*,
        // which is what lifts the suggestions over the tab chips that follow.
        Box(Modifier.zIndex(1f)) {
            Box(Modifier.onSizeChanged { fieldHeightPx = it.height }) {
                SearchField(
                    query = query,
                    onQueryChange = vm::onQueryChange,
                    onClear = vm::clear,
                    onSubmit = vm::dismissSuggestions,
                )
            }
            // Offset by the measured field rather than aligned to it: the list is taller than the field,
            // so any bottom-alignment would grow upwards and swallow the box being typed into.
            SuggestionList(
                suggestions = suggestions,
                onPick = vm::applySuggestion,
                modifier = Modifier.offset { IntOffset(0, fieldHeightPx) },
            )
        }

        // Source tabs: the normal catalog, or straight from YouTube + SoundCloud (remixes, edits, indies).
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchTab.entries.forEach { entry ->
                RizxChip(entry.displayLabel(), active = tab == entry, onClick = { vm.selectTab(entry) })
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (query.isBlank()) {
                IdleContent(onSearch = vm::onQueryChange)
            } else {
                when (val s = uiState) {
                    SearchUiState.Idle, SearchUiState.Loading -> LoadingState()
                    SearchUiState.Empty -> EmptyState(query)
                    SearchUiState.Offline -> RetryState(
                        title = stringResource(R.string.search_offline_title),
                        message = stringResource(R.string.search_offline_message),
                        onRetry = vm::retry,
                    )
                    is SearchUiState.Error -> RetryState(
                        title = stringResource(R.string.search_error_title),
                        message = s.message,
                        onRetry = vm::retry,
                    )
                    is SearchUiState.Results -> ResultsContent(s.results, tab, onPlay, onAddToQueue, onAddNext, onOpenAlbum, onOpenArtist, onOpenPlaylist, favoriteSources, vm::toggleFavorite)
                }
            }
        }
    }
}

/** Localized tab label — [SearchTab] is defined outside Composable scope, so the mapping lives here. */
@Composable
private fun SearchTab.displayLabel(): String = when (this) {
    SearchTab.Songs -> stringResource(R.string.search_tab_songs)
    SearchTab.Artists -> stringResource(R.string.search_tab_artists)
    SearchTab.Albums -> stringResource(R.string.search_tab_albums)
    SearchTab.Playlists -> stringResource(R.string.search_tab_playlists)
    SearchTab.Underground -> stringResource(R.string.search_tab_underground)
}

@Composable
private fun QueueChip(count: Int, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .clip(RectangleShape)
            .background(c.fill)
            .border(1.dp, c.line, RectangleShape)
            .clickableScale(scale = 0.94f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(RizxIcons.QueueMusic, stringResource(R.string.search_open_queue), tint = c.onFill, modifier = Modifier.size(17.dp))
        Text("$count", style = sg(13, FontWeight.Bold), color = c.onFill)
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val c = RizxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val active = focused || query.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RectangleShape)
            .background(c.inset)
            .border(1.dp, if (active) c.redAccent else c.line, RectangleShape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(RizxIcons.Search, null, tint = if (active) c.redAccent else c.muted, modifier = Modifier.size(22.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).padding(vertical = 16.dp),
            singleLine = true,
            textStyle = mr(15, FontWeight.Medium).copy(color = c.text),
            cursorBrush = SolidColor(c.redAccent),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // The field asked for a Search key but never did anything with it.
            keyboardActions = KeyboardActions(onSearch = { onSubmit(); keyboard?.hide() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.search_hint), style = mr(15, FontWeight.Medium), color = c.muted, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Icon(
                RizxIcons.Close,
                stringResource(R.string.action_clear),
                tint = c.text2,
                modifier = Modifier.size(20.dp).clickableScale(scale = 0.9f, onClick = onClear),
            )
        }
    }
}

/**
 * Autocomplete under the field: at most a handful of one-line rows, drawn over whatever follows.
 *
 * Deliberately small. A suggestion is a shortcut for finishing a word, not a destination — taking the
 * whole screen for it (as most apps do) hides the results the user may already be looking at, and turns
 * every keystroke into a full-page repaint.
 */
@Composable
private fun SuggestionList(suggestions: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    if (suggestions.isEmpty()) return
    val c = RizxTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.elev)
            .border(1.dp, c.hardLine, RectangleShape),
    ) {
        suggestions.forEach { suggestion ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickableScale(scale = 0.99f, onClick = { onPick(suggestion) })
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Icon(RizxIcons.Search, null, tint = c.muted, modifier = Modifier.size(15.dp))
                Text(
                    suggestion,
                    style = mr(13, FontWeight.Medium),
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---- States ----

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DotMatrixSpinner(color = RizxTheme.colors.accent, diameter = 34.dp)
    }
}

@Composable
private fun EmptyState(query: String) {
    val c = RizxTheme.colors
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.search_no_results_title), style = sg(18, FontWeight.Bold), color = c.text)
            Text(
                stringResource(R.string.search_no_results_message, query),
                style = mr(13, FontWeight.Medium),
                color = c.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Error / offline state with a Retry affordance (crash-safe, beta hardening — spec 014). */
@Composable
private fun RetryState(title: String, message: String, onRetry: () -> Unit) {
    val c = RizxTheme.colors
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = sg(18, FontWeight.Bold), color = c.text)
            Text(message, style = mr(13, FontWeight.Medium), color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            Text(
                stringResource(R.string.action_retry),
                style = sg(14, FontWeight.Bold),
                color = c.onFill,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clip(RectangleShape)
                    .background(c.fill)
                    .clickableScale(scale = 0.94f, onClick = onRetry)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ResultsContent(
    results: SearchResults,
    tab: SearchTab,
    onPlay: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddNext: (Track) -> Unit,
    onOpenAlbum: (fm.rizx.player.domain.model.ProviderRef) -> Unit,
    onOpenArtist: (fm.rizx.player.domain.model.ProviderRef) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    favoriteSources: Set<ProviderRef>,
    onToggleFavorite: (Track) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when (tab) {
            // Songs from YouTube + SoundCloud, grouped by source so it's clear which are from where.
            SearchTab.Underground -> {
                val yt = results.tracks.filter { it.source.provider == YoutubeIds.STREAMING }
                val sc = results.tracks.filter { it.source.provider == SoundcloudIds.STREAMING }
                if (yt.isNotEmpty()) {
                    SectionLabel("YouTube")
                    yt.forEachIndexed { index, track ->
                        Box(Modifier.staggeredReveal(index)) {
                            ResultTrackRow(track, onPlay, onAddToQueue, onAddNext, liked = track.source in favoriteSources, onToggleFavorite = { onToggleFavorite(track) })
                        }
                    }
                }
                if (sc.isNotEmpty()) {
                    SectionLabel("SoundCloud")
                    sc.forEachIndexed { index, track ->
                        Box(Modifier.staggeredReveal(index)) {
                            ResultTrackRow(track, onPlay, onAddToQueue, onAddNext, liked = track.source in favoriteSources, onToggleFavorite = { onToggleFavorite(track) })
                        }
                    }
                }
            }
            SearchTab.Artists -> {
                results.artists.forEachIndexed { index, artist ->
                    Box(Modifier.staggeredReveal(index)) { ResultArtistRow(artist) { onOpenArtist(artist.source) } }
                }
            }
            SearchTab.Albums -> {
                results.albums.forEachIndexed { index, album ->
                    Box(Modifier.staggeredReveal(index)) { ResultAlbumRow(album) { onOpenAlbum(album.source) } }
                }
            }
            // Playlists grouped by source (Deezer, then YouTube), tap to open.
            SearchTab.Playlists -> {
                val dz = results.playlists.filter { it.source.provider == DeezerIds.PROVIDER }
                val yt = results.playlists.filter { it.source.provider == YoutubeIds.STREAMING }
                if (dz.isNotEmpty()) {
                    SectionLabel("Deezer")
                    dz.forEachIndexed { index, playlist ->
                        Box(Modifier.staggeredReveal(index)) { ResultPlaylistRow(playlist) { onOpenPlaylist(playlist) } }
                    }
                }
                if (yt.isNotEmpty()) {
                    SectionLabel("YouTube")
                    yt.forEachIndexed { index, playlist ->
                        Box(Modifier.staggeredReveal(index)) { ResultPlaylistRow(playlist) { onOpenPlaylist(playlist) } }
                    }
                }
            }
            // Songs (the default catalog tab) — tracks only; artists/albums have their own tabs now.
            SearchTab.Songs -> {
                results.tracks.forEachIndexed { index, track ->
                    Box(Modifier.staggeredReveal(index)) {
                        ResultTrackRow(track, onPlay, onAddToQueue, onAddNext, liked = track.source in favoriteSources, onToggleFavorite = { onToggleFavorite(track) })
                    }
                }
            }
        }
        Spacer(Modifier.height(LocalBottomInset.current + 16.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = code(11, FontWeight.Bold), color = RizxTheme.colors.muted, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
}

@Composable
private fun ResultTrackRow(
    track: Track,
    onPlay: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddNext: (Track) -> Unit,
    liked: Boolean,
    onToggleFavorite: () -> Unit,
) {
    val c = RizxTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            // Tap a result to play it as a radio; the "+" menu still adds to queue / plays next.
            .clickableScale(scale = 0.99f, pressColor = c.rowHover) { onPlay(track) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverArt(tintFor(track.source.id), initial = null, Modifier.size(46.dp), imageUrl = track.artwork.coverUrl())
        Column(Modifier.weight(1f)) {
            Text(track.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artists.joinToString { it.name }, style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            if (liked) RizxIcons.Favorite else RizxIcons.FavoriteBorder,
            if (liked) stringResource(R.string.search_remove_from_liked) else stringResource(R.string.search_like),
            tint = if (liked) c.redAccent else c.text2,
            modifier = Modifier.size(22.dp).clickableScale(scale = 0.84f, onClick = onToggleFavorite),
        )
        Box {
            Icon(
                RizxIcons.Add,
                stringResource(R.string.search_add_to_queue),
                tint = c.text2,
                modifier = Modifier.size(24.dp).clickableScale(scale = 0.86f) { menuOpen = true },
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_add_to_queue), style = mr(14, FontWeight.Medium), color = c.text) },
                    onClick = { onAddToQueue(track); menuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_play_next), style = mr(14, FontWeight.Medium), color = c.text) },
                    onClick = { onAddNext(track); menuOpen = false },
                )
            }
        }
    }
}

@Composable
private fun ResultArtistRow(artist: ArtistRef, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CoverArt(tintFor(artist.source.id), initial = artist.name.take(1), Modifier.size(46.dp), initialSize = 18, circle = true, imageUrl = artist.artwork.coverUrl())
        Text(artist.name, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.search_result_artist_label), style = mr(11, FontWeight.Medium), color = c.muted)
    }
}

@Composable
private fun ResultAlbumRow(album: AlbumRef, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CoverArt(tintFor(album.source.id), initial = album.title.take(1), Modifier.size(46.dp), initialSize = 18, imageUrl = album.artwork.coverUrl())
        Column(Modifier.weight(1f)) {
            Text(album.title, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artists.joinToString { it.name }.ifEmpty { stringResource(R.string.search_album_fallback) }, style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ResultPlaylistRow(playlist: PlaylistRef, onClick: () -> Unit) {
    val c = RizxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableScale(scale = 0.99f, pressColor = c.rowHover, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CoverArt(tintFor(playlist.source.id), initial = playlist.name.take(1), Modifier.size(46.dp), initialSize = 18, imageUrl = playlist.artwork.coverUrl())
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = mr(14, FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val trackCountLabel = playlist.trackCount?.let { stringResource(R.string.search_track_count, it) }
                ?: stringResource(R.string.search_playlist_label)
            Text(
                trackCountLabel,
                style = mr(12, FontWeight.Medium), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- Idle (no query): suggested searches + genre tiles (all trigger a real search) ----

private val SEARCH_SUGGESTIONS = listOf("Daft Punk", "The Weeknd", "Coldplay", "Bad Bunny", "Lo-fi beats", "Tame Impala")

/** A browse-genre tile: display [labelRes], the [query] it searches, a Deezer genre [image], a fallback [tint] and a HUD [code]. */
private data class Genre(val labelRes: Int, val query: String, val image: String, val tint: Int, val code: String)

/** Deezer genre artwork — keyless CDN, the same host Coil already loads album covers from. */
private fun dzGenre(hash: String) = "https://cdn-images.dzcdn.net/images/misc/$hash/500x500-000000-80-0-0.jpg"

// NB: `query` is the literal string sent to Deezer's genre search — it must stay in English and is
// intentionally NOT localized, unlike `labelRes` (what's shown on the tile).
private val BROWSE_GENRES = listOf(
    Genre(R.string.search_genre_pop, "Pop", dzGenre("db7a604d9e7634a67d45cfc86b48370a"), 1, "G01"),
    Genre(R.string.search_genre_hiphop, "Hip Hop", dzGenre("5c27115d3b797954afff59199dad98d1"), 2, "G02"),
    Genre(R.string.search_genre_rock, "Rock", dzGenre("b36ca681666d617edd0dcb5ab389a6ac"), 5, "G03"),
    Genre(R.string.search_genre_electronic, "Electronic", dzGenre("15df4502c1c58137dae5bdd1cc6f0251"), 3, "G04"),
    Genre(R.string.search_genre_rnb, "R&B", dzGenre("68a43aec844708e693cb99f47814153b"), 0, "G05"),
    Genre(R.string.search_genre_jazz, "Jazz", dzGenre("91468ecc5dfdd19c42a43d2cbdf27059"), 4, "G06"),
    Genre(R.string.search_genre_latin, "Latin", dzGenre("069c9888538799748960781f098b5f4b"), 6, "G07"),
    Genre(R.string.search_genre_classical, "Classical", dzGenre("609f69b669b242252aa8ee09b5597655"), 7, "G08"),
    Genre(R.string.search_genre_metal, "Metal", dzGenre("f14f9fde9feb38ca6d61960f00681860"), 5, "G09"),
    Genre(R.string.search_genre_reggaeton, "Reggaeton", dzGenre("44dfebf3cf943dd82759d9bd9063767a"), 6, "G10"),
    Genre(R.string.search_genre_reggae, "Reggae", dzGenre("7b901a98628cf879e1465f1dfd697e00"), 4, "G11"),
    Genre(R.string.search_genre_soul_funk, "Funk", dzGenre("3d5e8aab99b95bfa7ac7e9e466e7781e"), 0, "G12"),
    Genre(R.string.search_genre_blues, "Blues", dzGenre("1abb6810098d4015bdc860c91bcfd2b6"), 3, "G13"),
    Genre(R.string.search_genre_country, "Country", dzGenre("6eca3188f724f04843a15e3e575751a5"), 1, "G14"),
    Genre(R.string.search_genre_folk, "Folk", dzGenre("f9e070848998df8870ba65cd0d22b2b3"), 2, "G15"),
    Genre(R.string.search_genre_dance, "Dance", dzGenre("bd5fdfa1a23e02e2697818e09e008e69"), 7, "G16"),
)

@Composable
private fun IdleContent(onSearch: (String) -> Unit) {
    val c = RizxTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.search_try_searching), style = sg(16, FontWeight.Bold), color = c.text, modifier = Modifier.padding(top = 22.dp))
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SEARCH_SUGGESTIONS.forEach { s ->
                Row(
                    Modifier
                        .clip(RectangleShape)
                        .background(c.elev)
                        .border(1.dp, c.line, RectangleShape)
                        .clickableScale(scale = 0.95f) { onSearch(s) }
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(Icons.Filled.History, null, tint = c.muted, modifier = Modifier.size(16.dp))
                    Text(s, style = mr(13, FontWeight.Medium), color = c.text2)
                }
            }
        }

        Text(stringResource(R.string.search_browse_genres), style = sg(19, FontWeight.Bold, -0.01f), color = c.text, modifier = Modifier.padding(top = 24.dp))
        Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BROWSE_GENRES.chunked(2).forEachIndexed { rowIndex, rowGenres ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowGenres.forEachIndexed { colIndex, genre ->
                        GenreTile(genre, onSearch, Modifier.weight(1f).staggeredReveal(rowIndex * 2 + colIndex))
                    }
                    if (rowGenres.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(LocalBottomInset.current + 16.dp))
    }
}

/** A genre mosaic: a full-bleed Deezer genre photo under a bottom-weighted scrim, with a HUD serial + display-font label. */
@Composable
private fun GenreTile(genre: Genre, onSearch: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = RizxTheme.colors
    Box(
        modifier
            .height(116.dp)
            .paperElevation()
            .clip(RectangleShape)
            .background(catBg(genre.tint, c.isDark))
            .border(1.dp, c.line, RectangleShape)
            .clickableScale(scale = 0.98f) { onSearch(genre.query) },
    ) {
        coil.compose.AsyncImage(
            model = genre.image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        // Bottom-weighted scrim keeps the label legible over bright or busy photos, in either theme.
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.10f),
                        0.55f to Color.Black.copy(alpha = 0.30f),
                        1f to Color.Black.copy(alpha = 0.80f),
                    ),
                ),
        )
        // HUD serial (red tick + mono code) — ties the photo tiles into the spec-sheet language.
        Row(
            Modifier.align(Alignment.TopStart).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).background(c.redAccent))
            Text(genre.code, style = code(10, FontWeight.Bold), color = Color.White.copy(alpha = 0.82f))
        }
        Text(
            stringResource(genre.labelRes),
            style = sg(18, FontWeight.Bold, -0.01f),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 13.dp, end = 12.dp, bottom = 11.dp),
        )
    }
}

private fun tintFor(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % 7
