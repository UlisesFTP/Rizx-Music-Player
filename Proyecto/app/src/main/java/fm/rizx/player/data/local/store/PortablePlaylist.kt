package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtistRef
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.Playlist
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.repository.PlaylistExportArtifact
import fm.rizx.player.domain.repository.PlaylistExportFormat
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer

/** Security boundary for anything that leaves this installation. */
object PortableTrackSanitizer {
    private val safeId = Regex("^[A-Za-z0-9._:-]{1,256}$")

    fun sanitize(track: Track): Track {
        val source = safeSource(track.source, track)
        return track.copy(
            artists = track.artists.map { it.sanitize() },
            album = track.album?.sanitize(),
            artwork = track.artwork.sanitize(),
            source = source,
            localFile = null,
            streamCandidates = emptyList(),
        )
    }

    fun publicLocation(track: Track): String? = knownPublicUrl(sanitize(track).source)

    private fun safeSource(source: ProviderRef, track: Track): ProviderRef {
        val provider = source.provider.trim().lowercase().take(64).ifBlank { "portable" }
        val id = source.id.trim()
        if (provider in localProviders || !safeId.matches(id) || looksLocal(id)) {
            val seed = buildString {
                append(track.title); append('\u0000')
                append(track.artists.joinToString("\u0001") { it.name }); append('\u0000')
                append(track.durationMs ?: -1)
            }
            return ProviderRef("portable", sha256(seed).take(32), null)
        }
        return ProviderRef(provider, id, knownPublicUrl(ProviderRef(provider, id)))
    }

    private fun ArtistCredit.sanitize() = copy(source = source?.let { safeNestedRef(it) })

    private fun ArtistRef.sanitize() = copy(
        artwork = artwork.sanitize(),
        source = safeNestedRef(source) ?: ProviderRef("portable", sha256(name).take(32)),
    )

    private fun AlbumRef.sanitize() = copy(
        artists = artists.map { it.sanitize() },
        artwork = artwork.sanitize(),
        source = safeNestedRef(source) ?: ProviderRef("portable", sha256(title).take(32)),
    )

    private fun ArtworkSet?.sanitize(): ArtworkSet? {
        val safe = this?.items.orEmpty().mapNotNull { art ->
            sanitizeHttps(art.url)?.let { url ->
                Artwork(url, art.width, art.height, art.purpose, art.source?.let(::safeNestedRef))
            }
        }
        return safe.takeIf { it.isNotEmpty() }?.let(::ArtworkSet)
    }

    private fun safeNestedRef(ref: ProviderRef): ProviderRef? {
        val provider = ref.provider.trim().lowercase().take(64)
        val id = ref.id.trim()
        if (provider.isBlank() || provider in localProviders || !safeId.matches(id) || looksLocal(id)) return null
        return ProviderRef(provider, id, knownPublicUrl(ProviderRef(provider, id)))
    }

    private fun knownPublicUrl(ref: ProviderRef): String? = when (ref.provider.lowercase()) {
        "spotify" -> ref.id.takeIf { it.matches(Regex("[A-Za-z0-9]{10,64}")) }
            ?.let { "https://open.spotify.com/track/$it" }
        "youtube", "youtube-music", "ytmusic" -> ref.id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,64}")) }
            ?.let { "https://music.youtube.com/watch?v=$it" }
        "deezer" -> ref.id.takeIf { it.all(Char::isDigit) }?.let { "https://www.deezer.com/track/$it" }
        "apple", "apple-music", "itunes" -> ref.id.takeIf { it.all(Char::isDigit) }
            ?.let { "https://music.apple.com/song/$it" }
        else -> null
    }

    private fun sanitizeHttps(value: String): String? = runCatching {
        val uri = URI(value.trim())
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        URI("https", null, uri.host.lowercase(), uri.port, uri.path, null, null).toASCIIString()
    }.getOrNull()

    private fun looksLocal(value: String): Boolean {
        val v = value.trim()
        return v.startsWith("file:", true) || v.startsWith("content:", true) ||
            v.startsWith("/") || Regex("^[A-Za-z]:[\\\\/].*").matches(v) || v.contains('\\')
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val localProviders = setOf("local", "local-file", "mediastore", "opened-file", "file")
}

object PortablePlaylistFormats {
    fun artifact(playlist: Playlist, format: PlaylistExportFormat, exportedAtIso: String): PlaylistExportArtifact {
        val safeName = fileStem(playlist.name)
        return when (format) {
            PlaylistExportFormat.RIZX_JSON -> {
                val body = PlaylistTransfer.encode(playlist, exportedAtIso)
                PlaylistExportArtifact("$safeName.json", format.mimeType, body, playlist.items.size, 0)
            }
            PlaylistExportFormat.XSPF -> xspf(playlist, safeName)
            PlaylistExportFormat.M3U8 -> m3u8(playlist, safeName)
        }
    }

    private fun xspf(playlist: Playlist, stem: String): PlaylistExportArtifact {
        val tracks = playlist.items.map { PortableTrackSanitizer.sanitize(it.track) }
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\"><title>")
            append(xml(playlist.name)); append("</title><trackList>\n")
            tracks.forEach { track ->
                append("<track><title>"); append(xml(track.title)); append("</title>")
                track.artists.firstOrNull()?.name?.let { append("<creator>"); append(xml(it)); append("</creator>") }
                track.album?.title?.let { append("<album>"); append(xml(it)); append("</album>") }
                track.durationMs?.let { append("<duration>"); append(it); append("</duration>") }
                append("<identifier>"); append(xml(track.source.identityKey)); append("</identifier>")
                PortableTrackSanitizer.publicLocation(track)?.let { append("<location>"); append(xml(it)); append("</location>") }
                append("</track>\n")
            }
            append("</trackList></playlist>\n")
        }
        return PlaylistExportArtifact("$stem.xspf", PlaylistExportFormat.XSPF.mimeType, body, tracks.size, 0)
    }

    private fun m3u8(playlist: Playlist, stem: String): PlaylistExportArtifact {
        val rows = playlist.items.mapNotNull { item ->
            val track = PortableTrackSanitizer.sanitize(item.track)
            val location = PortableTrackSanitizer.publicLocation(track) ?: return@mapNotNull null
            val seconds = track.durationMs?.div(1_000) ?: -1
            val artist = track.artists.joinToString(", ") { it.name }
            val label = listOf(artist, track.title).filter { it.isNotBlank() }.joinToString(" - ")
                .replace('\r', ' ').replace('\n', ' ')
            "#EXTINF:$seconds,$label\n$location"
        }
        val body = buildString {
            append("#EXTM3U\n")
            rows.forEach { append(it); append('\n') }
        }
        return PlaylistExportArtifact(
            "$stem.m3u8", PlaylistExportFormat.M3U8.mimeType, body,
            includedItems = rows.size, omittedItems = playlist.items.size - rows.size,
        )
    }

    private fun fileStem(name: String): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFKC)
            .replace(Regex("[\\x00-\\x1f<>:\"/\\\\|?*]"), "_").trim().trim('.')
        return normalized.take(80).ifBlank { "playlist" }
    }

    private fun xml(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(when (ch) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"; else -> ch })
        }
    }
}

