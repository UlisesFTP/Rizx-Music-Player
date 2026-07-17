package fm.rizx.player.data.provider

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track

/**
 * A small in-memory catalog of domain entities that the fake providers search over. Identities use
 * the fake metadata provider as their [ProviderRef.provider]. Echoes the prototype's vibe so the UI
 * feels consistent once real domain results replace `SampleData`.
 */
internal object FakeCatalog {

    private fun ref(id: String) = ProviderRef(provider = FakeMetadataProvider.ID, id = id)

    val artists: List<ArtistRef> = listOf(
        ArtistRef(name = "Maya Sol", source = ref("ar-maya")),
        ArtistRef(name = "Noelle", source = ref("ar-noelle")),
        ArtistRef(name = "Kwame B.", source = ref("ar-kwame")),
        ArtistRef(name = "Lila Verde", source = ref("ar-lila")),
        ArtistRef(name = "Tea Mori", source = ref("ar-tea")),
    )

    val albums: List<AlbumRef> = listOf(
        album("al-amber", "Amber Tide", "Maya Sol", "ar-maya"),
        album("al-saudade", "Saudade", "Noelle", "ar-noelle"),
        album("al-neon", "Neon Soul", "Kwame B.", "ar-kwame"),
        album("al-cherry", "Cherry Lacquer", "Lila Verde", "ar-lila"),
        album("al-moonlit", "Moonlit", "Tea Mori", "ar-tea"),
    )

    val tracks: List<Track> = listOf(
        track("tr-velvet", "Velvet Hours", "Maya Sol", "ar-maya", "al-amber", "Amber Tide", 204_000),
        track("tr-golden", "Goldenrod", "Maya Sol", "ar-maya", "al-amber", "Amber Tide", 178_000),
        track("tr-saudade", "Saudade", "Noelle", "ar-noelle", "al-saudade", "Saudade", 251_000),
        track("tr-lowlight", "Lowlight", "Maya Sol", "ar-maya", "al-amber", "Amber Tide", 182_000),
        track("tr-cherry", "Cherry Wine", "Lila Verde", "ar-lila", "al-cherry", "Cherry Lacquer", 225_000),
        track("tr-afterglow", "Afterglow", "Tea Mori", "ar-tea", "al-moonlit", "Moonlit", 159_000),
        track("tr-neon", "Neon Soul", "Kwame B.", "ar-kwame", "al-neon", "Neon Soul", 213_000),
    )

    private fun album(id: String, title: String, artist: String, artistId: String) =
        AlbumRef(
            title = title,
            artists = listOf(ArtistRef(name = artist, source = ref(artistId))),
            source = ref(id),
        )

    private fun track(
        id: String,
        title: String,
        artist: String,
        artistId: String,
        albumId: String,
        albumTitle: String,
        durationMs: Long,
    ) = Track(
        title = title,
        artists = listOf(ArtistCredit(name = artist, source = ref(artistId))),
        album = AlbumRef(
            title = albumTitle,
            artists = listOf(ArtistRef(name = artist, source = ref(artistId))),
            source = ref(albumId),
        ),
        durationMs = durationMs,
        source = ref(id),
    )
}
