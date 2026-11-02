package fm.rizx.player.domain.discovery

import fm.rizx.player.domain.model.SoundGenre

/**
 * Where each [SoundGenre] lives on each platform. Static on purpose: none of these platforms publishes a
 * keyless genre index, so the mapping is the app's, and every id here was **verified live on 2026-11-02**
 * (ADR 0033) — an id that later disappears only costs its own cards.
 *
 * - **Deezer** genre ids are Deezer's own (`GET /genre`), the same ones `chart/{genreId}` takes.
 * - **Apple** curators are its genre editors (`music.apple.com/{storefront}/curator/<slug>/<id>`). The
 *   ids are global; a few curators exist only in some storefronts, so the source falls back to `us`.
 * - **Spotify** ids are editorial playlists checked through the public embed (name + track count came
 *   back). Genres with no verifiable editorial id are simply absent — Spotify has no keyless search to
 *   find one with.
 * - **YouTube Music** is searched, so it needs a query, not an id.
 */
object GenreCatalog {

    data class Entry(
        val deezerGenreId: Int? = null,
        val appleCurator: AppleCurator? = null,
        val spotifyPlaylistIds: List<String> = emptyList(),
        val youtubeQuery: String? = null,
    )

    /** An Apple genre editor's curator id ("Apple Music Pop" = 976439548). */
    data class AppleCurator(val id: String)

    private val catalog: Map<SoundGenre, Entry> = mapOf(
        SoundGenre.POP to Entry(
            deezerGenreId = 132,
            appleCurator = AppleCurator("976439548"),
            // "Songs to Sing in the Car", "Chill Hits", "All Out 80s"
            spotifyPlaylistIds = listOf("37i9dQZF1DWWMOmoXKqHTD", "37i9dQZF1DX4WYpdgoIcn6", "37i9dQZF1DX4UtSsGT1Sbe"),
            youtubeQuery = "pop hits",
        ),
        SoundGenre.HIPHOP to Entry(
            deezerGenreId = 116,
            appleCurator = AppleCurator("976439539"),
            // "Most Necessary", "Beast Mode"
            spotifyPlaylistIds = listOf("37i9dQZF1DX2RxBh64BHjQ", "37i9dQZF1DX76Wlfdnj7AP"),
            youtubeQuery = "hip hop hits",
        ),
        SoundGenre.REGGAETON to Entry(
            deezerGenreId = 122,
            appleCurator = AppleCurator("976439553"),
            // "MANSIÓN REGGAETÓN"
            spotifyPlaylistIds = listOf("37i9dQZF1DWZjqjZMudx9T"),
            youtubeQuery = "reggaeton hits",
        ),
        SoundGenre.ELECTRONIC to Entry(
            deezerGenreId = 106,
            appleCurator = AppleCurator("976439536"),
            // "Dance Party", "Tech House Operator"
            spotifyPlaylistIds = listOf("37i9dQZF1DXaXB8fQg7xif", "37i9dQZF1DWVY4eLfA3XFQ"),
            youtubeQuery = "electronic dance hits",
        ),
        SoundGenre.REGGAE_DANCEHALL to Entry(
            deezerGenreId = 144,
            appleCurator = AppleCurator("976439552"),
            // "Reggae Classics"
            spotifyPlaylistIds = listOf("37i9dQZF1DXbSbnqxMTGx9"),
            youtubeQuery = "reggae dancehall",
        ),
        SoundGenre.ROCK to Entry(
            deezerGenreId = 152,
            appleCurator = AppleCurator("976439554"),
            // "Walk Like A Badass"
            spotifyPlaylistIds = listOf("37i9dQZF1DX1tyCD9QhIWF"),
            youtubeQuery = "rock classics",
        ),
        SoundGenre.METAL to Entry(
            deezerGenreId = 464,
            appleCurator = AppleCurator("976439543"),
            // "Kickass Metal", "MARROW"
            spotifyPlaylistIds = listOf("37i9dQZF1DWTcqUzwhNmKv", "37i9dQZF1DXcF6B6QPhFDv"),
            youtubeQuery = "metal",
        ),
        SoundGenre.INDIE_ALT to Entry(
            deezerGenreId = 85,
            appleCurator = AppleCurator("976439526"),
            // "Lorem", "POLLEN", "it's alt good"
            spotifyPlaylistIds = listOf("37i9dQZF1DXdwmD5Q7Gxah", "37i9dQZF1DWWBHeXOYZf74", "37i9dQZF1DX2SK4ytI2KAZ"),
            youtubeQuery = "indie alternative",
        ),
        SoundGenre.LATIN_REGIONAL to Entry(
            deezerGenreId = 65,
            appleCurator = AppleCurator("976439544"),
            youtubeQuery = "corridos regional mexicano",
        ),
        SoundGenre.LATIN_TROPICAL to Entry(
            deezerGenreId = 67,
            appleCurator = AppleCurator("976439545"),
            // "Bachata Lovers"
            spotifyPlaylistIds = listOf("37i9dQZF1DX7MTlMMRl0MD"),
            youtubeQuery = "salsa bachata",
        ),
        SoundGenre.RNB_SOUL to Entry(
            deezerGenreId = 165,
            appleCurator = AppleCurator("976439551"),
            // "I Love My '00s R&B"
            spotifyPlaylistIds = listOf("37i9dQZF1DWYmmr74INQlb"),
            youtubeQuery = "r&b soul",
        ),
        SoundGenre.KPOP_JPOP to Entry(
            deezerGenreId = 16,
            // "K-Pop ON! (온)", "Tokyo Super Hits!"
            spotifyPlaylistIds = listOf("37i9dQZF1DX9tPFwDMOaN1", "37i9dQZF1DXafb0IuPwJyF"),
            youtubeQuery = "k-pop hits",
        ),
        SoundGenre.SOUNDTRACK to Entry(
            deezerGenreId = 173,
            appleCurator = AppleCurator("976439586"),
            youtubeQuery = "movie soundtrack",
        ),
        SoundGenre.ACOUSTIC_FOLK to Entry(
            deezerGenreId = 466,
            appleCurator = AppleCurator("976439527"),
            // "Essential Folk", "Your Favorite Coffeehouse"
            spotifyPlaylistIds = listOf("37i9dQZF1DWVmps5U8gHNv", "37i9dQZF1DX6ziVCJnEm59"),
            youtubeQuery = "acoustic folk",
        ),
        SoundGenre.COUNTRY to Entry(
            deezerGenreId = 84,
            appleCurator = AppleCurator("976439534"),
            // "Hot Country", "Chillin' on a Dirt Road"
            spotifyPlaylistIds = listOf("37i9dQZF1DX1lVhptIYRda", "37i9dQZF1DWTkxQvqMy4WW"),
            youtubeQuery = "country hits",
        ),
        SoundGenre.JAZZ to Entry(
            deezerGenreId = 129,
            appleCurator = AppleCurator("976439542"),
            // "Jazz Vibes", "Jazz Classics"
            spotifyPlaylistIds = listOf("37i9dQZF1DX0SM0LYsmbMT", "37i9dQZF1DXbITWG1ZJKYt"),
            youtubeQuery = "jazz",
        ),
        SoundGenre.CLASSICAL to Entry(
            deezerGenreId = 98,
            appleCurator = AppleCurator("976439532"),
            // "Classical Essentials", "Peaceful Piano"
            spotifyPlaylistIds = listOf("37i9dQZF1DWWEJlAGA9gs0", "37i9dQZF1DX4sWSpwq3LiO"),
            youtubeQuery = "classical music",
        ),
        SoundGenre.LOFI_CHILL to Entry(
            // "lofi beats", "calm vibes", "Instrumental Study"
            spotifyPlaylistIds = listOf("37i9dQZF1DWWQRwui0ExPn", "37i9dQZF1DX1s9knjP51Oa", "37i9dQZF1DX9sIqqvKsjG8"),
            youtubeQuery = "lofi chill",
        ),
    )

    /** Deezer genre id → family, for when a taste artist's tracks carry no tag of their own. */
    private val byDeezerGenre: Map<Int, SoundGenre> = buildMap {
        catalog.forEach { (genre, entry) -> entry.deezerGenreId?.let { put(it, genre) } }
        // Deezer families with no entry of their own, folded into the nearest one we do browse.
        put(113, SoundGenre.ELECTRONIC) // Dance
        put(197, SoundGenre.REGGAETON) // Latino
        put(71, SoundGenre.LATIN_TROPICAL) // Cumbia
        put(169, SoundGenre.RNB_SOUL) // Soul & Funk
        put(153, SoundGenre.ROCK) // Blues
    }

    fun entry(genre: SoundGenre): Entry? = catalog[genre]

    fun fromDeezerGenre(id: Long?): SoundGenre = id?.toInt()?.let { byDeezerGenre[it] } ?: SoundGenre.UNKNOWN

    /** Every genre the catalogue can build a shelf for. */
    val browsable: Set<SoundGenre> get() = catalog.keys
}
