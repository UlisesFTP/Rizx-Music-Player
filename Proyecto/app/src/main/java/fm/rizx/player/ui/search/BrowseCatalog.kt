package fm.rizx.player.ui.search

import androidx.annotation.StringRes
import fm.rizx.player.R

/**
 * One tile on Search's browse wall.
 *
 * [genreId] is a **Deezer genre id**, not a search term. That is the whole point of this file: tapping
 * "Pop" used to run a text search for the word *Pop*, which returns songs called Pop, Pop Smoke, and
 * "Pop Muzik" — a query, not a genre. The id addresses Deezer's own grouping of its catalogue instead.
 *
 * [labelRes] is localized; [genreId] never is. [image] is Deezer's published genre artwork (the same
 * keyless CDN host Coil already loads covers from); a tile without one falls back to its [tint] block.
 */
data class BrowseCategory(
    @StringRes val labelRes: Int,
    val genreId: String,
    val image: String?,
    val tint: Int,
    val code: String,
)

/** Deezer genre artwork by CDN hash — 500px, the size the tiles actually draw. */
private fun dzGenre(hash: String) = "https://cdn-images.dzcdn.net/images/misc/$hash/500x500-000000-80-0-0.jpg"

/**
 * The browse wall: Deezer's full published genre catalogue, ordered by how broadly listened it is
 * rather than by Deezer's own order, with the all-genres chart promoted to the first tile.
 *
 * Ids and artwork hashes come from Deezer's `/genre` endpoint and are stable; they are inlined rather
 * than fetched so the idle screen draws with **no network call at all** — the pills beside it are
 * already local, and a browse wall that arrives late is a layout jump on the app's most-opened screen.
 * Genre `0` is Deezer's everything-chart, which is why it reads as "Charts" rather than as a genre.
 */
val BROWSE_CATEGORIES: List<BrowseCategory> = listOf(
    BrowseCategory(R.string.search_genre_charts, "0", null, 1, "TOP"),
    BrowseCategory(R.string.search_genre_pop, "132", dzGenre("db7a604d9e7634a67d45cfc86b48370a"), 1, "G01"),
    BrowseCategory(R.string.search_genre_hiphop, "116", dzGenre("5c27115d3b797954afff59199dad98d1"), 2, "G02"),
    BrowseCategory(R.string.search_genre_reggaeton, "122", dzGenre("44dfebf3cf943dd82759d9bd9063767a"), 6, "G03"),
    BrowseCategory(R.string.search_genre_rock, "152", dzGenre("b36ca681666d617edd0dcb5ab389a6ac"), 5, "G04"),
    BrowseCategory(R.string.search_genre_latin, "197", dzGenre("069c9888538799748960781f098b5f4b"), 6, "G05"),
    BrowseCategory(R.string.search_genre_electronic, "106", dzGenre("15df4502c1c58137dae5bdd1cc6f0251"), 3, "G06"),
    BrowseCategory(R.string.search_genre_dance, "113", dzGenre("bd5fdfa1a23e02e2697818e09e008e69"), 7, "G07"),
    BrowseCategory(R.string.search_genre_rnb, "165", dzGenre("68a43aec844708e693cb99f47814153b"), 0, "G08"),
    BrowseCategory(R.string.search_genre_alternative, "85", dzGenre("fd252ab727d9a3b0b3c29014873f8f57"), 4, "G09"),
    BrowseCategory(R.string.search_genre_metal, "464", dzGenre("f14f9fde9feb38ca6d61960f00681860"), 5, "G10"),
    BrowseCategory(R.string.search_genre_folk, "466", dzGenre("f9e070848998df8870ba65cd0d22b2b3"), 2, "G11"),
    BrowseCategory(R.string.search_genre_jazz, "129", dzGenre("91468ecc5dfdd19c42a43d2cbdf27059"), 4, "G12"),
    BrowseCategory(R.string.search_genre_classical, "98", dzGenre("609f69b669b242252aa8ee09b5597655"), 7, "G13"),
    BrowseCategory(R.string.search_genre_soul_funk, "169", dzGenre("3d5e8aab99b95bfa7ac7e9e466e7781e"), 0, "G14"),
    BrowseCategory(R.string.search_genre_blues, "153", dzGenre("1abb6810098d4015bdc860c91bcfd2b6"), 3, "G15"),
    BrowseCategory(R.string.search_genre_country, "84", dzGenre("6eca3188f724f04843a15e3e575751a5"), 1, "G16"),
    BrowseCategory(R.string.search_genre_reggae, "144", dzGenre("7b901a98628cf879e1465f1dfd697e00"), 4, "G17"),
    BrowseCategory(R.string.search_genre_mexican, "65", dzGenre("0935a03b26d8a96964920a426de97dae"), 5, "G18"),
    BrowseCategory(R.string.search_genre_salsa, "67", dzGenre("71860f882f1e1d4950b7544e2d0b61ef"), 6, "G19"),
    BrowseCategory(R.string.search_genre_cumbia, "71", dzGenre("ffd77feba2c8fda79b18183861e4e69f"), 3, "G20"),
    BrowseCategory(R.string.search_genre_brazilian, "75", dzGenre("01b12a3f3582899a13b664cea703a335"), 7, "G21"),
    BrowseCategory(R.string.search_genre_african, "2", dzGenre("703413adf47ad8a6001b438f7608a2be"), 2, "G22"),
    BrowseCategory(R.string.search_genre_asian, "16", dzGenre("dd6d2756465b22488dff5d8663e86688"), 0, "G23"),
    BrowseCategory(R.string.search_genre_indian, "81", dzGenre("b098161d9a824eef314bc38b985594a1"), 4, "G24"),
    BrowseCategory(R.string.search_genre_christian, "186", dzGenre("e658e2ba682088f4d4b44f39c400d69d"), 7, "G25"),
    BrowseCategory(R.string.search_genre_soundtracks, "173", dzGenre("236d8057751d9c557728400dfe71483a"), 3, "G26"),
    BrowseCategory(R.string.search_genre_kids, "95", dzGenre("b0b8efcbc3cb688864ce69da0061e525"), 1, "G27"),
)

/** The tile for [genreId], so the genre screen can reuse its artwork without it riding the nav route. */
fun browseCategoryFor(genreId: String): BrowseCategory? =
    BROWSE_CATEGORIES.firstOrNull { it.genreId == genreId }
