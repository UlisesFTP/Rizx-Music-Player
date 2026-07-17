package fm.rizx.player.data.local.store

import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Stream
import fm.rizx.player.domain.model.StreamCandidate
import fm.rizx.player.domain.model.StreamProtocol
import fm.rizx.player.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTransferTest {

    private fun track(title: String) = Track(title = title, source = ProviderRef("itunes", "id-$title"))

    @Test
    fun `encode then decode round-trips name, description and tracks`() {
        val json = PlaylistTransfer.encode("Road Trip", "summer", listOf(track("A"), track("B")), "2026-07-13T00:00:00Z")

        val export = PlaylistTransfer.decode(json)

        assertEquals("rizx.playlist", export.format)
        assertEquals(1, export.version)
        assertEquals("Road Trip", export.name)
        assertEquals("summer", export.description)
        assertEquals(listOf("A", "B"), export.tracks.map { it.title })
        assertEquals(ProviderRef("itunes", "id-A"), export.tracks.first().source)
    }

    @Test
    fun `encode strips ephemeral resolution state`() {
        val resolved = track("A").copy(
            streamCandidates = listOf(
                StreamCandidate(
                    id = "c1", title = "A",
                    stream = Stream("https://ephemeral/x.m4a", StreamProtocol.HTTPS, source = ProviderRef("s", "c1")),
                    source = ProviderRef("s", "c1"),
                ),
            ),
        )

        val export = PlaylistTransfer.decode(PlaylistTransfer.encode("Mix", null, listOf(resolved), "t"))

        assertTrue(export.tracks.single().streamCandidates.isEmpty())
    }

    @Test
    fun `decode rejects a payload that is not a rizx playlist`() {
        val notOurs = """{"format":"spotify.export","name":"x","tracks":[]}"""
        assertThrows(IllegalArgumentException::class.java) { PlaylistTransfer.decode(notOurs) }
    }

    @Test
    fun `decode tolerates unknown keys for forward compatibility`() {
        val future = """{"format":"rizx.playlist","version":99,"name":"Mix","futureField":true,"tracks":[]}"""

        val export = PlaylistTransfer.decode(future)

        assertEquals("Mix", export.name)
        assertEquals(99, export.version)
    }

    // ---- decodeImport: the foreign formats we accept on import ----

    @Test
    fun `decodeImport reads a rizx export`() {
        val json = PlaylistTransfer.encode("Road Trip", "summer", listOf(track("A")), "2026-07-13T00:00:00Z")

        val imported = PlaylistTransfer.decodeImport(json)

        assertEquals("Road Trip", imported.name)
        assertEquals(listOf("A"), imported.tracks.map { it.title })
    }

    @Test
    fun `decodeImport reads a nuclear playlist, keeping a known youtube stream as identity`() {
        val nuclear = """
            {"id":"abc","name":"Nuclear Mix","tracks":[
              {"uuid":"u1","artist":"Mr. Kitty","name":"After Dark","album":"Time","duration":271,
               "stream":{"source":"Youtube","id":"dQw4w9WgXcQ","duration":271}},
              {"uuid":"u2","artist":"Daft Punk","name":"One More Time","duration":"320"}
            ]}
        """.trimIndent()

        val imported = PlaylistTransfer.decodeImport(nuclear)

        assertEquals("Nuclear Mix", imported.name)
        assertEquals(listOf("After Dark", "One More Time"), imported.tracks.map { it.title })
        assertEquals("Mr. Kitty", imported.tracks[0].artists.single().name)
        assertEquals(271_000L, imported.tracks[0].durationMs) // nuclear durations are seconds → ms
        assertEquals(320_000L, imported.tracks[1].durationMs) // …and may arrive as a string
        // A resolved Nuclear track keeps its real video id, so it plays that exact video.
        assertEquals("youtube:dQw4w9WgXcQ", imported.tracks[0].source.identityKey)
        // With no upstream id, identity is synthetic but deterministic (see the next test).
        assertEquals("import", imported.tracks[1].source.provider)
    }

    @Test
    fun `an imported track with no upstream id keeps the same identity across re-imports`() {
        val nuclear = """{"name":"Mix","tracks":[{"artist":"Daft Punk","name":"Aerodynamic"}]}"""

        val first = PlaylistTransfer.decodeImport(nuclear).tracks.single().source
        val second = PlaylistTransfer.decodeImport(nuclear).tracks.single().source

        assertEquals(first, second) // re-importing must not mint a new identity
    }

    @Test
    fun `decodeImport reads an exportify csv, naming it from the file`() {
        val csv = """
            "Track URI","Track Name","Artist Name(s)","Album Name","Track Duration (ms)"
            "spotify:track:65DbTqJKhbwqYbZ1Okr0rc","Choosin' Texas","Ella Langley","Choosin' Texas","232226"
            "spotify:track:2plbrEY59IikOBgBGLjaoe","Dai Dai","Shakira, Burna Boy","Dai Dai","180000"
        """.trimIndent()

        val imported = PlaylistTransfer.decodeImport(csv, fallbackName = "My Spotify Export")

        assertEquals("My Spotify Export", imported.name) // a CSV carries no name — the file name is it
        assertEquals(listOf("Choosin' Texas", "Dai Dai"), imported.tracks.map { it.title })
        assertEquals(232226L, imported.tracks[0].durationMs) // already ms
        assertEquals("spotify:65DbTqJKhbwqYbZ1Okr0rc", imported.tracks[0].source.identityKey)
        assertEquals(listOf("Shakira", "Burna Boy"), imported.tracks[1].artists.map { it.name })
    }

    @Test
    fun `decodeImport handles quoted csv fields containing commas`() {
        val csv = """
            "Track Name","Artist Name(s)","Album Name"
            "Hello, Goodbye","The Beatles","Magical Mystery Tour"
        """.trimIndent()

        val imported = PlaylistTransfer.decodeImport(csv, fallbackName = "Beatles")

        assertEquals("Hello, Goodbye", imported.tracks.single().title)
    }

    @Test
    fun `decodeImport rejects a payload in no known format`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaylistTransfer.decodeImport("""{"format":"spotify.export","name":"x","tracks":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) { PlaylistTransfer.decodeImport("just some text") }
    }
}
