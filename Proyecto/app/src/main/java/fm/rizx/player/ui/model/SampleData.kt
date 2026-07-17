package fm.rizx.player.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker

/** Static sample content mirroring `RizxScreens.dc.html`'s `renderVals()` data. */
object SampleData {

    val homeChips = listOf("All", "New Release", "Trending", "Charts", "Mood")
    // `libTabs` and `albumFilters` lived here unused since the mockup. Their design intent is now built
    // for real: LibraryTab drives the Library's chips, and "Downloaded" is the Downloads tab.
    val artistFilters = listOf("All", "Following", "Recently played")
    val recents = listOf("maya sol", "slow r&b", "amber tide", "noelle")

    val mixes = listOf(
        Mix("After Hours", "38 tracks", 0),
        Mix("Slow Burn", "24 tracks", 1),
        Mix("Honeyed", "40 tracks", 2),
        Mix("Lowlight", "19 tracks", 3),
    )

    val homeAlbums = listOf(
        AlbumCard("Amber Tide", "Maya Sol", 4),
        AlbumCard("Saudade", "Noelle", 5),
        AlbumCard("Neon Soul", "Kwame B.", 6),
        AlbumCard("Cherry Lacquer", "Lila Verde", 7),
        AlbumCard("Moonlit", "Tea Mori", 8),
    )

    val libraryItems = listOf(
        LibraryItem("Liked Songs", "128 songs", liked = true, tintIndex = 2),
        LibraryItem("After Hours", "Playlist · Maya Sol", liked = false, tintIndex = 3),
        LibraryItem("Saudade", "Album · Noelle", liked = false, tintIndex = 4),
        LibraryItem("Slow Burn", "Playlist · You", liked = false, tintIndex = 5),
        LibraryItem("Neon Soul", "Album · Kwame B.", liked = false, tintIndex = 6),
        LibraryItem("Lowlight", "Playlist · Sasha Brooke", liked = false, tintIndex = 7),
        LibraryItem("Honeyed", "Playlist · You", liked = false, tintIndex = 8),
    )

    val browseCategories = listOf(
        "R&B", "Pop", "Hip-Hop", "Chill", "Workout", "Sleep", "Charts", "New",
    ).mapIndexed { i, label -> BrowseCategory(label, i) }

    val albumTracks = listOf(
        TrackRow("Velvet Hours", "Maya Sol", "3:24", playing = true, tintIndex = 0),
        TrackRow("Goldenrod", "Maya Sol", "2:58", playing = false, tintIndex = 1),
        TrackRow("Saudade", "Maya Sol, Noelle", "4:11", playing = false, tintIndex = 2),
        TrackRow("Lowlight", "Maya Sol", "3:02", playing = false, tintIndex = 3),
        TrackRow("Cherry Wine", "Maya Sol", "3:45", playing = false, tintIndex = 4),
        TrackRow("Afterglow", "Maya Sol", "2:39", playing = false, tintIndex = 5),
    )

    val albumGrid = listOf(
        AlbumCard("Amber Tide", "Maya Sol", 0),
        AlbumCard("Velvet Asphalt", "Maya Sol", 1),
        AlbumCard("Saudade", "Noelle", 2),
        AlbumCard("Neon Soul", "Kwame B.", 3),
        AlbumCard("Cherry Lacquer", "Lila Verde", 4),
        AlbumCard("Moonlit", "Tea Mori", 5),
        AlbumCard("Lowlight", "Sasha Brooke", 6),
        AlbumCard("Goldenrod", "Maya Sol", 7),
    )

    val artistGrid = listOf(
        ArtistCard("Maya Sol", "1.2M followers", 1),
        ArtistCard("Noelle", "840K followers", 2),
        ArtistCard("Kwame B.", "612K followers", 3),
        ArtistCard("Lila Verde", "430K followers", 4),
        ArtistCard("Tea Mori", "2.1M followers", 5),
        ArtistCard("Sasha Brooke", "305K followers", 6),
        ArtistCard("Onyx Park", "778K followers", 7),
        ArtistCard("June Haze", "156K followers", 8),
    )

    val playOnDevices = listOf(
        Device("This phone", "Active now", Icons.Filled.Smartphone, active = true),
        Device("Living Room", "Wi-Fi speaker", Icons.Filled.Speaker, active = false),
        Device("Kitchen Display", "Wi-Fi · Cast", Icons.Filled.SmartDisplay, active = false),
        Device("Studio Monitors", "Bluetooth", Icons.Filled.Headphones, active = false),
        Device("Maya’s Car", "Android Auto", Icons.Filled.DirectionsCar, active = false),
    )

    val musicFrom = listOf(
        MusicSource("Rizx Library", "Streaming · 38,402 tracks", Icons.Filled.Cloud),
        MusicSource("On this device", "Local files · 214 tracks", Icons.Filled.Folder),
        MusicSource("Radio & Podcasts", "Live stations", Icons.Filled.Radio),
    )

    // Now Playing sample track.
    const val NP_TITLE = "Velvet Asphalt"
    const val NP_ARTIST = "Maya Sol"
    const val NP_ALBUM = "Velvet Asphalt (2024)"
    const val NP_POSITION = "4 of 10"
    const val TRACK_DURATION_SEC = 204
}
