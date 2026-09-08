package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.stripResolutionState
import kotlinx.serialization.json.Json

/**
 * kotlinx.serialization codec for persisted domain entities. Tracks are run through
 * [stripResolutionState] before encoding, so ephemeral stream URLs (`Track.streamCandidates`) are
 * **never** written to disk (AGENTS.md). `ignoreUnknownKeys` keeps
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

    /**
     * The same decode, but `null` for a row this build cannot read.
     *
     * Rows arrive from outside this app — another device writes them through sync, an import or a
     * plugin supplies them — so a stored string is not guaranteed to fit the current model. It has
     * already happened once: the web wrote `"album":{…,"source":null,…}` for a track from the
     * YouTube radio, `AlbumRef.source` is not nullable here, and kotlinx.serialization threw
     * `Expected start of the object '{' … at path: ${'$'}.album.source` from inside a Flow that feeds
     * Home — so the app died on launch, over and over, with no way out from the UI.
     *
     * One unreadable row must cost that row, not the screen. Callers that render a list use these
     * and skip what will not decode.
     */
    fun decodeTrackOrNull(text: String): Track? =
        runCatching { decodeTrack(text) }.getOrNull()

    fun encodeAlbum(album: AlbumRef): String = json.encodeToString(AlbumRef.serializer(), album)

    fun decodeAlbum(text: String): AlbumRef = json.decodeFromString(AlbumRef.serializer(), text)

    /** [decodeAlbum], skipping a row this build cannot read — see [decodeTrackOrNull]. */
    fun decodeAlbumOrNull(text: String): AlbumRef? =
        runCatching { decodeAlbum(text) }.getOrNull()

    fun encodeArtist(artist: ArtistRef): String = json.encodeToString(ArtistRef.serializer(), artist)

    fun decodeArtist(text: String): ArtistRef = json.decodeFromString(ArtistRef.serializer(), text)

    /** [decodeArtist], skipping a row this build cannot read — see [decodeTrackOrNull]. */
    fun decodeArtistOrNull(text: String): ArtistRef? =
        runCatching { decodeArtist(text) }.getOrNull()
}
