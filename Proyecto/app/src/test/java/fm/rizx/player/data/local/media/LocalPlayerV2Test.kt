package fm.rizx.player.data.local.media

import fm.rizx.player.domain.model.PlaylistSummary
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.codecForMime
import fm.rizx.player.domain.model.containerForMime
import fm.rizx.player.domain.repository.LocalSong
import fm.rizx.player.ui.local.LocalLibraryViewModel
import fm.rizx.player.ui.local.LocalSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure halves of the local player v2: what a mime is allowed to claim, how a picked file names
 * itself, how a folder becomes a play order, and how the Songs list sorts.
 */
class LocalPlayerV2Test {

    // ---- mime honesty ----

    @Test
    fun `a mime that names its codec is mapped, an ambiguous one claims nothing`() {
        assertEquals("FLAC", codecForMime("audio/flac"))
        assertEquals("FLAC", codecForMime("audio/x-flac"))
        assertEquals("WAV", codecForMime("audio/x-wav"))
        assertEquals("MP3", codecForMime("audio/mpeg"))
        assertEquals("OPUS", codecForMime("audio/opus"))
        // audio/mp4 could be AAC or ALAC; audio/ogg Vorbis or Opus. Claiming would be guessing.
        assertNull(codecForMime("audio/mp4"))
        assertNull(codecForMime("audio/ogg"))
        assertNull(codecForMime(null))
        // Parameters and case must not defeat the mapping.
        assertEquals("FLAC", codecForMime("Audio/FLAC; charset=binary"))
    }

    @Test
    fun `containers map even where codecs refuse to`() {
        assertEquals("m4a", containerForMime("audio/mp4"))
        assertEquals("ogg", containerForMime("audio/ogg"))
        assertEquals("flac", containerForMime("audio/flac"))
        assertNull(containerForMime("application/pdf"))
    }

    // ---- picked files ----

    @Test
    fun `a tagged file shows its tags, an untagged one its file name without the extension`() {
        val tagged = fileTrack(
            uri = "content://com.android.providers/doc/123",
            displayName = "07 - song.flac", title = "Nocturne", artist = "Chopin", album = "Nocturnes",
            durationMs = 240_000,
        )
        assertEquals("Nocturne", tagged.title)
        assertEquals("Chopin", tagged.artists.single().name)
        assertEquals("Nocturnes", tagged.album?.title)

        val bare = fileTrack(
            uri = "content://x/doc/9", displayName = "voice memo 12.m4a",
            title = null, artist = null, album = null, durationMs = null,
        )
        assertEquals("voice memo 12", bare.title)
        assertTrue(bare.artists.isEmpty())
        assertNull(bare.album)
    }

    @Test
    fun `a picked file's identity is its document uri under the file provider`() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fx.flac"
        val track = fileTrack(uri, "x.flac", null, null, null, null)

        assertEquals(LocalIds.FILE_PROVIDER, track.source.provider)
        assertEquals(uri, track.source.id)
    }

    // ---- folder planning ----

    private fun entry(name: String, mime: String? = "audio/mpeg") =
        FolderAudioEntry(uri = "content://tree/doc/$name", displayName = name, mimeType = mime)

    @Test
    fun `folder order is natural - track 2 before track 10`() {
        val plan = planFolderQueue(
            listOf(entry("10 - Outro.mp3"), entry("2 - Intro.mp3"), entry("1 - Open.mp3")),
        )
        assertEquals(listOf("1 - Open.mp3", "2 - Intro.mp3", "10 - Outro.mp3"), plan.entries.map { it.displayName })
    }

    @Test
    fun `non-audio is filtered out, ogg's odd application mime stays`() {
        val plan = planFolderQueue(
            listOf(entry("a.mp3"), entry("cover.jpg", "image/jpeg"), entry("b.ogg", "application/ogg"), entry("notes.txt", "text/plain")),
        )
        assertEquals(listOf("a.mp3", "b.ogg"), plan.entries.map { it.displayName })
    }

    @Test
    fun `the cap is applied and reported, never silent`() {
        val many = (1..600).map { entry("song $it.mp3") }
        val plan = planFolderQueue(many)
        assertEquals(500, plan.entries.size)
        assertEquals(100, plan.skipped)
        // And the kept 500 are the naturally-first ones, not an arbitrary slice.
        assertEquals("song 1.mp3", plan.entries.first().displayName)
        assertEquals("song 500.mp3", plan.entries.last().displayName)
    }

    @Test
    fun `natural order ignores case and survives huge digit runs`() {
        val names = listOf("b.mp3", "A.mp3", "99999999999999999999999-x.mp3", "100-x.mp3")
        val sorted = names.sortedWith(NaturalOrder)
        assertEquals(listOf("100-x.mp3", "99999999999999999999999-x.mp3", "A.mp3", "b.mp3"), sorted)
    }

    // ---- songs sorting ----

    private fun song(title: String, artist: String? = null, added: Long = 0, durMs: Long = 0) = LocalSong(
        track = localTrack(
            id = title.hashCode().toLong(), title = title, artist = artist, artistId = 1,
            album = null, albumId = null, durationMs = durMs.takeIf { it > 0 }, trackNumber = null,
        ),
        dateAddedSec = added, sizeBytes = 0, mimeType = "audio/mpeg",
    )

    @Test
    fun `title sort is case-insensitive, recent sort is newest first`() {
        val scan = listOf(song("beta", added = 10), song("Alpha", added = 30), song("gamma", added = 20))

        assertEquals(
            listOf("Alpha", "beta", "gamma"),
            LocalLibraryViewModel.sortScan(scan, LocalSort.TITLE).map { it.title },
        )
        assertEquals(
            listOf("Alpha", "gamma", "beta"),
            LocalLibraryViewModel.sortScan(scan, LocalSort.RECENT).map { it.title },
        )
    }

    @Test
    fun `artist sort groups unknown artists last, duration sort is longest first`() {
        val scan = listOf(
            song("solo", artist = null, durMs = 100_000),
            song("zeta", artist = "Ana", durMs = 300_000),
            song("eta", artist = "Bruno", durMs = 200_000),
        )
        assertEquals(
            listOf("zeta", "eta", "solo"),
            LocalLibraryViewModel.sortScan(scan, LocalSort.ARTIST).map { it.title },
        )
        assertEquals(
            listOf("zeta", "eta", "solo"),
            LocalLibraryViewModel.sortScan(scan, LocalSort.DURATION).map { it.title },
        )
    }

    // ---- own playlists ----

    @Test
    fun `own playlists are the not-imported ones`() {
        val lists = listOf(
            PlaylistSummary(id = "a", name = "Mía"),
            PlaylistSummary(id = "b", name = "De Spotify", isImported = true),
        )
        assertEquals(listOf("a"), lists.filterNot { it.isImported }.map { it.id })
    }
}
