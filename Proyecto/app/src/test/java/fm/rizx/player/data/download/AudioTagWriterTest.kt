package fm.rizx.player.data.download

import fm.rizx.player.domain.model.AlbumRef
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.math.PI
import kotlin.math.sin

/**
 * What a finished download actually carries once it leaves the app: cover, artist, album, date.
 *
 * These run against the **real** tag libraries and real files — a genuine LAME stream from the encoder
 * the MP3 format uses, a FLAC assembled to spec, and an Ogg Opus stream — because the failure this
 * guards against is not a wrong string but a format silently going untagged. Every assertion reads the
 * file back the way another player would, never through the writer's own return value.
 */
class AudioTagWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var writer: AudioTagWriter

    private val cover = jpeg(width = 640, height = 640, padding = 3_000)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(cover)))
        writer = AudioTagWriter(OkHttpClient(), io = Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun track() = Track(
        title = "Blinding Lights",
        artists = listOf(ArtistCredit(name = "The Weeknd")),
        album = AlbumRef(title = "After Hours", source = ProviderRef("deezer", "album:1")),
        trackNumber = 9,
        artwork = ArtworkSet(listOf(Artwork(url = server.url("/cover.jpg").toString()))),
        source = ProviderRef("youtube", "abc"),
    )

    // ---- the formats jaudiotagger writes ----

    @Test
    fun `an MP3 from the encoder carries title artist album and the cover`() = runBlocking {
        val file = temp.newFile("song.mp3").apply { writeBytes(realMp3()) }

        assertTrue(writer.tag(file, track(), releaseDateIso = "2020-03-20"))

        val tag = AudioFileIO.read(file).tag
        assertEquals("Blinding Lights", tag.getFirst(FieldKey.TITLE))
        assertEquals("The Weeknd", tag.getFirst(FieldKey.ARTIST))
        assertEquals("After Hours", tag.getFirst(FieldKey.ALBUM))
        assertEquals("9", tag.getFirst(FieldKey.TRACK))
        assertTrue("2020-03-20 lost", tag.getFirst(FieldKey.YEAR).startsWith("2020"))
        assertTrue(cover.contentEquals(tag.firstArtwork!!.binaryData))
    }

    @Test
    fun `a FLAC carries them too — the lossless download is not a second-class file`() = runBlocking {
        val file = temp.newFile("song.flac").apply { writeBytes(realFlac()) }

        assertTrue(writer.tag(file, track(), releaseDateIso = "2020-03-20"))

        val tag = AudioFileIO.read(file).tag
        assertEquals("Blinding Lights", tag.getFirst(FieldKey.TITLE))
        assertEquals("The Weeknd", tag.getFirst(FieldKey.ARTIST))
        assertEquals("After Hours", tag.getFirst(FieldKey.ALBUM))
        assertTrue(cover.contentEquals(tag.firstArtwork!!.binaryData))
    }

    @Test
    fun `an album the track itself does not name is still written when the caller recovered one`() =
        runBlocking {
            val file = temp.newFile("song.flac").apply { writeBytes(realFlac()) }
            val orphan = track().copy(album = null)

            writer.tag(file, orphan, albumTitle = "After Hours")

            assertEquals("After Hours", AudioFileIO.read(file).tag.getFirst(FieldKey.ALBUM))
        }

    // ---- Opus, which jaudiotagger cannot write at all ----

    @Test
    fun `an Ogg Opus download is tagged through the Opus writer, cover included`() = runBlocking {
        val file = temp.newFile("song.opus").apply { writeBytes(oggOpus()) }

        assertTrue(writer.tag(file, track(), releaseDateIso = "2020-03-20"))

        val fields = oggComments(file)
        assertEquals("Blinding Lights", fields["TITLE"])
        assertEquals("The Weeknd", fields["ARTIST"])
        assertEquals("The Weeknd", fields["ALBUMARTIST"])
        assertEquals("After Hours", fields["ALBUM"])
        assertEquals("2020-03-20", fields["DATE"])
        assertEquals("9", fields["TRACKNUMBER"])
        val picture = Base64.getDecoder().decode(fields["METADATA_BLOCK_PICTURE"])
        assertTrue("the cover bytes are not in the picture block", picture.indexOfSlice(cover) >= 0)
    }

    @Test
    fun `an Opus stream that arrived with a wrong extension is still recognised by its bytes`() =
        runBlocking {
            // jaudiotagger would read a `.ogg` as Vorbis and fail; the routing is by content, not name.
            val file = temp.newFile("song.ogg").apply { writeBytes(oggOpus()) }

            assertTrue(writer.tag(file, track()))

            assertEquals("Blinding Lights", oggComments(file)["TITLE"])
        }

    @Test
    fun `a WebM is left alone — nothing here can tag it, and a half-written file would be worse`() =
        runBlocking {
            val file = temp.newFile("song.webm").apply { writeBytes(ByteArray(4_096) { it.toByte() }) }
            val before = file.readBytes()

            assertFalse(writer.tag(file, track()))

            assertTrue(before.contentEquals(file.readBytes()))
        }

    @Test
    fun `a cover that cannot be fetched still leaves the text tags written`() = runBlocking {
        server.shutdown() // no cover server at all
        val file = temp.newFile("song.opus").apply { writeBytes(oggOpus()) }

        assertTrue(writer.tag(file, track()))

        val fields = oggComments(file)
        assertEquals("Blinding Lights", fields["TITLE"])
        assertEquals(null, fields["METADATA_BLOCK_PICTURE"])
    }

    // ---- fixtures ----

    /** A genuine MPEG-1 Layer III stream, from the same encoder the MP3 download format uses. */
    private fun realMp3(): ByteArray {
        val out = ByteArrayOutputStream()
        val encoder = Jump3rMp3Encoder(44_100, 2)
        val frames = 44_100 // one second
        val pcm = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val sample = (sin(2.0 * PI * 440.0 * f / 44_100) * 12_000).toInt().toShort()
            pcm[f * 2] = sample
            pcm[f * 2 + 1] = sample
        }
        encoder.encode(pcm, frames, out)
        encoder.finish(out)
        return out.toByteArray()
    }

    /**
     * A FLAC to spec: the `fLaC` marker, a STREAMINFO block flagged as the last one, and some bytes
     * standing in for frames. No padding block on purpose — that is the harder path for the writer,
     * which then has to insert its blocks and shift the audio, exactly as with a foreign file.
     */
    private fun realFlac(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        out.write(0x80) // last-block flag + type 0 (STREAMINFO)
        out.write(byteArrayOf(0, 0, 34))
        out.write(byteArrayOf(0x10, 0x00, 0x10, 0x00)) // min/max block size 4096
        out.write(ByteArray(6)) // min/max frame size unknown
        // 20 bits sample rate | 3 bits (channels - 1) | 5 bits (bits per sample - 1) | 36 bits samples
        val packed = (44_100L shl 44) or (1L shl 41) or (15L shl 36) or 44_100L
        for (i in 7 downTo 0) out.write(((packed ushr (8 * i)) and 0xFF).toInt())
        out.write(ByteArray(16)) // md5 of the unencoded audio: unknown is a legal 0
        out.write(ByteArray(4_096) { (it % 251).toByte() })
        return out.toByteArray()
    }

    /** `OpusHead` + `OpusTags` + one audio page, the shape [OpusRemuxer] produces. */
    private fun oggOpus(): ByteArray {
        val out = ByteArrayOutputStream()
        val head = ByteArray(19).also {
            "OpusHead".toByteArray().copyInto(it)
            it[8] = 1
            it[9] = 2
        }
        val tags = ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray())
            writeLE("Rizx".length)
            write("Rizx".toByteArray())
            writeLE(0)
        }.toByteArray()
        out.write(oggPage(head, sequence = 0, type = 0x02))
        out.write(oggPage(tags, sequence = 1))
        out.write(oggPage(ByteArray(512) { it.toByte() }, sequence = 2, granule = 960))
        return out.toByteArray()
    }

    private fun oggPage(packet: ByteArray, sequence: Int, granule: Long = 0, type: Int = 0): ByteArray {
        val laces = buildList {
            var left = packet.size
            while (left >= 255) {
                add(255); left -= 255
            }
            add(left)
        }
        val header = ByteArray(27 + laces.size)
        "OggS".toByteArray().copyInto(header)
        header[5] = type.toByte()
        for (i in 0 until 8) header[6 + i] = ((granule ushr (8 * i)) and 0xFF).toByte()
        for (i in 0 until 4) header[14 + i] = ((SERIAL ushr (8 * i)) and 0xFF).toByte()
        for (i in 0 until 4) header[18 + i] = ((sequence ushr (8 * i)) and 0xFF).toByte()
        header[26] = laces.size.toByte()
        laces.forEachIndexed { i, lace -> header[27 + i] = lace.toByte() }
        val page = header + packet
        var crc = 0
        for (byte in page) {
            var value = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) {
                value = if (value and 0x80000000.toInt() != 0) (value shl 1) xor 0x04c11db7 else value shl 1
            }
            crc = value
        }
        for (i in 0 until 4) page[22 + i] = ((crc ushr (8 * i)) and 0xFF).toByte()
        return page
    }

    /** Reads the comment fields back out of an Ogg Opus file, the way another player would. */
    private fun oggComments(file: File): Map<String, String> {
        val bytes = file.readBytes()
        val packet = ByteArrayOutputStream()
        var at = 0
        var page = 0
        while (at < bytes.size) {
            val segments = bytes[at + 26].toInt() and 0xFF
            val laces = (0 until segments).map { bytes[at + 27 + it].toInt() and 0xFF }
            val start = at + 27 + segments
            val size = laces.sum()
            if (page >= 1) {
                packet.write(bytes, start, size)
                if (laces.last() != 255) break
            }
            at = start + size
            page++
        }
        val tags = packet.toByteArray()
        var i = "OpusTags".length
        i += 4 + intLE(tags, i) // vendor
        val count = intLE(tags, i)
        i += 4
        val fields = mutableMapOf<String, String>()
        repeat(count) {
            val length = intLE(tags, i)
            i += 4
            val field = String(tags, i, length, Charsets.UTF_8)
            i += length
            fields[field.substringBefore('=')] = field.substringAfter('=')
        }
        return fields
    }

    private fun jpeg(width: Int, height: Int, padding: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11))
        out.write(8)
        out.write(byteArrayOf((height ushr 8).toByte(), height.toByte()))
        out.write(byteArrayOf((width ushr 8).toByte(), width.toByte()))
        out.write(ByteArray(10))
        out.write(ByteArray(padding) { (it % 251).toByte() })
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun intLE(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.indexOfSlice(slice: ByteArray): Int {
        outer@ for (start in 0..size - slice.size) {
            for (i in slice.indices) if (this[start + i] != slice[i]) continue@outer
            return start
        }
        return -1
    }

    private companion object {
        const val SERIAL = 0x0BADF00D
    }
}
