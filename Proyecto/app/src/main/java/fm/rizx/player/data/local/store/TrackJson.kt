package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.stripResolutionState
import kotlinx.serialization.json.Json

/**
 * kotlinx.serialization codec for persisted domain entities. Tracks are run through
 * [stripResolutionState] before encoding, so ephemeral stream URLs (`Track.streamCandidates`) are
 * **never** written to disk (AGENTS.md / NUCLEAR_UPSTREAM_STUDY.md §7.3). `ignoreUnknownKeys` keeps
 * forward/legacy compatibility for import.
 */
internal object TrackJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encodeTrack(track: Track): String =
        json.encodeToString(Track.serializer(), track.stripResolutionState())

    fun decodeTrack(text: String): Track = json.decodeFromString(Track.serializer(), text)

    fun encodeAlbum(album: AlbumRef): String = json.encodeToString(AlbumRef.serializer(), album)

    fun decodeAlbum(text: String): AlbumRef = json.decodeFromString(AlbumRef.serializer(), text)

    fun encodeArtist(artist: ArtistRef): String = json.encodeToString(ArtistRef.serializer(), artist)

    fun decodeArtist(text: String): ArtistRef = json.decodeFromString(ArtistRef.serializer(), text)
}
