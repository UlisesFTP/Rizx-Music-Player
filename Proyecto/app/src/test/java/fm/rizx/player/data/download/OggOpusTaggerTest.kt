package fm.rizx.player.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * The Ogg Opus comment writer, against streams built here byte for byte — there is no library under
 * this, so the page framing, the lacing and Ogg's own CRC are all ours to get right.
 *
 * What the assertions are really protecting: a malformed page makes the file unplayable, not merely
 * untagged. So every test re-parses the whole output and checks the framing invariants — contiguous
 * sequence numbers, correct checksums, audio payload untouched — not just the tags that were written.
 */
class OggOpusTaggerTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- building a stream to tag ----

    /** An Ogg Opus file: `OpusHead`, an `OpusTags` packet with [oldComments], then [audioPages] pages. */
    private fun oggOpus(
        oldComments: List<String> = listOf("TITLE=Old"),
        audioPages: Int = 3,
        serial: Int = 0x0BADF00D,
    ): File {
        val out = ByteArrayOutputStream()
        var sequence = 0
        out.write(page(packet = opusHead(), serial = serial, sequence = sequence++, type = BOS, granule = 0))
        out.write(page(packet = opusTags(oldComments), serial = serial, sequence = sequence++, granule = 0))
        repeat(audioPages) { index ->
            val audio = ByteArray(400) { (index * 31 + it).toByte() }
            out.write(page(audio, serial, sequence++, granule = (index + 1) * 960L))
        }
        return temp.newFile("song-${counter++}.opus").apply { writeBytes(out.toByteArray()) }
    }

    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray().copyInto(head)
        head[8] = 1 // version
        head[9] = 2 // channels
        head[12] = 0x80.toByte() // pre-skip, low byte
        return head
    }

    private fun opusTags(comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OpusTags".toByteArray())
        val vendor = "libopus test".toByteArray()
        out.writeLE(vendor.size)
        out.write(vendor)
        out.writeLE(comments.size)
        comments.forEach {
            val bytes = it.toByteArray()
            out.writeLE(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /** One page holding [packet] whole (the fixtures never need a packet bigger than a page). */
    private fun page(packet: ByteArray, serial: Int, sequence: Int, granule: Long, type: Int = 0): ByteArray {
        val laces = buildList {
            var left = packet.size
            while (left >= 255) {
                add(255)
                left -= 255
            }
            add(left)
        }
        val header = ByteArray(27 + laces.size)
        "OggS".toByteArray().copyInto(header)
        header[5] = type.toByte()
        for (i in 0 until 8) header[6 + i] = ((granule ushr (8 * i)) and 0xFF).toByte()
        for (i in 0 until 4) header[14 + i] = ((serial ushr (8 * i)) and 0xFF).toByte()
        for (i in 0 until 4) header[18 + i] = ((sequence ushr (8 * i)) and 0xFF).toByte()
        header[26] = laces.size.toByte()
        laces.forEachIndexed { i, lace -> header[27 + i] = lace.toByte() }
        val whole = header + packet
        crcInto(whole)
        return whole
    }

    // ---- reading it back ----

    private class ParsedPage(
        val type: Int,
        val serial: Int,
        val sequence: Int,
        val granule: Long,
        val payload: ByteArray,
        val lastLace: Int,
        val crcValid: Boolean,
    )

    private fun parse(file: File): List<ParsedPage> {
        val bytes = file.readBytes()
        val pages = mutableListOf<ParsedPage>()
        var at = 0
        while (at < bytes.size) {
            assertEquals("page $at does not start with OggS", "OggS", String(bytes, at, 4))
            val segments = bytes[at + 26].toInt() and 0xFF
            val laces = (0 until segments).map { bytes[at + 27 + it].toInt() and 0xFF }
            val payloadStart = at + 27 + segments
            val payloadSize = laces.sum()
            val whole = bytes.copyOfRange(at, payloadStart + payloadSize)
            val declared = intLE(whole, 22)
            val recomputed = whole.copyOf().also { crcInto(it) }.let { intLE(it, 22) }
            pages += ParsedPage(
                type = whole[5].toInt() and 0xFF,
                serial = intLE(whole, 14),
                sequence = intLE(whole, 18),
                granule = (0 until 8).fold(0L) { acc, i -> acc or ((whole[6 + i].toLong() and 0xFF) shl (8 * i)) },
                payload = bytes.copyOfRange(payloadStart, payloadStart + payloadSize),
                lastLace = laces.lastOrNull() ?: 0,
                crcValid = declared == recomputed,
            )
            at = payloadStart + payloadSize
        }
        return pages
    }

    /** The comment packet, reassembled across however many pages it took. */
    private fun commentPacket(pages: List<ParsedPage>): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 1
        while (i < pages.size) {
            out.write(pages[i].payload)
            if (pages[i].lastLace != 255) break
            i++
        }
        return out.toByteArray()
    }

    private fun comments(file: File): Map<String, String> {
        val packet = commentPacket(parse(file))
        var at = "OpusTags".length
        val vendorLength = intLE(packet, at)
        at += 4 + vendorLength
        val count = intLE(packet, at)
        at += 4
        val fields = mutableMapOf<String, String>()
        repeat(count) {
            val length = intLE(packet, at)
            at += 4
            val field = String(packet, at, length, Charsets.UTF_8)
            at += length
            fields[field.substringBefore('=')] = field.substringAfter('=')
        }
        return fields
    }

    /** The pages the comment header occupies, i.e. everything between page 0 and the first audio page. */
    private fun headerPages(pages: List<ParsedPage>): List<ParsedPage> {
        val out = mutableListOf<ParsedPage>()
        var i = 1
        while (i < pages.size) {
            out += pages[i]
            if (pages[i].lastLace != 255) break
            i++
        }
        return out
    }

    // ---- tests ----

    @Test
    fun `title artist and album land in the comment header`() {
        val file = oggOpus()

        assertTrue(
            OggOpusTagger.write(
                file,
                listOf("TITLE" to "Blinding Lights", "ARTIST" to "The Weeknd", "ALBUM" to "After Hours"),
            ),
        )

        val fields = comments(file)
        assertEquals("Blinding Lights", fields["TITLE"])
        assertEquals("The Weeknd", fields["ARTIST"])
        assertEquals("After Hours", fields["ALBUM"])
    }

    @Test
    fun `the old comments are replaced, not merged`() {
        val file = oggOpus(oldComments = listOf("TITLE=Wrong", "ARTIST=Wrong", "ENCODER=youtube-dl"))

        OggOpusTagger.write(file, listOf("TITLE" to "Right"))

        val fields = comments(file)
        assertEquals("Right", fields["TITLE"])
        assertEquals(null, fields["ARTIST"])
        assertEquals(null, fields["ENCODER"])
    }

    @Test
    fun `blank values are dropped rather than written empty`() {
        val file = oggOpus()

        OggOpusTagger.write(file, listOf("TITLE" to "Song", "ALBUM" to "", "TRACKNUMBER" to ""))

        val fields = comments(file)
        assertEquals(setOf("TITLE"), fields.keys)
    }

    @Test
    fun `the cover round-trips through METADATA_BLOCK_PICTURE`() {
        val file = oggOpus()
        val jpeg = jpeg(width = 1000, height = 1000, padding = 4_096)

        OggOpusTagger.write(file, listOf("TITLE" to "Song"), OggOpusTagger.describeImage(jpeg))

        val encoded = comments(file)["METADATA_BLOCK_PICTURE"]!!
        val block = Base64.getDecoder().decode(encoded)
        assertEquals(3, intBE(block, 0)) // front cover
        assertEquals("image/jpeg", String(block, 8, intBE(block, 4)))
        var at = 8 + intBE(block, 4)
        at += 4 + intBE(block, at) // empty description
        assertEquals(1000, intBE(block, at))
        assertEquals(1000, intBE(block, at + 4))
        val dataLength = intBE(block, at + 16)
        assertEquals(jpeg.size, dataLength)
        assertTrue(block.copyOfRange(at + 20, at + 20 + dataLength).contentEquals(jpeg))
    }

    @Test
    fun `a cover too big for one page is laced across pages the readers can follow`() {
        val file = oggOpus()
        // 200 KB of JPEG is an ordinary cover and already three pages of base64 — the multi-page path
        // is the normal case here, so its framing has to be right, not merely survivable.
        val jpeg = jpeg(width = 1400, height = 1400, padding = 200_000)

        OggOpusTagger.write(file, listOf("TITLE" to "Song"), OggOpusTagger.describeImage(jpeg))

        val pages = parse(file)
        val header = headerPages(pages)
        assertTrue("expected the comment header to span pages, got ${header.size}", header.size > 1)
        // Every page but the last continues the packet; every page but the first says so.
        header.dropLast(1).forEach { assertEquals(255, it.lastLace) }
        assertTrue(header.last().lastLace < 255)
        assertEquals(0, header.first().type)
        header.drop(1).forEach { assertEquals(1, it.type) } // continued-packet flag
        header.forEach { assertEquals(0L, it.granule) }
        assertEquals(jpeg.size, Base64.getDecoder().decode(comments(file)["METADATA_BLOCK_PICTURE"]).let {
            intBE(it, it.size - jpeg.size - 4)
        })
    }

    @Test
    fun `every page keeps a valid checksum and a contiguous sequence number`() {
        val file = oggOpus(audioPages = 4)

        OggOpusTagger.write(file, listOf("TITLE" to "Song"), OggOpusTagger.describeImage(jpeg(64, 64, 90_000)))

        val pages = parse(file)
        pages.forEachIndexed { index, page ->
            assertTrue("page $index has a bad CRC", page.crcValid)
            assertEquals("page $index is out of sequence", index, page.sequence)
            assertEquals(0x0BADF00D, page.serial)
        }
    }

    @Test
    fun `the audio pages come through byte for byte`() {
        val file = oggOpus(audioPages = 3)
        val before = parse(file).takeLast(3)

        OggOpusTagger.write(file, listOf("TITLE" to "Song"))

        val after = parse(file).takeLast(3)
        before.zip(after).forEach { (old, new) ->
            assertTrue(old.payload.contentEquals(new.payload))
            assertEquals(old.granule, new.granule)
        }
    }

    @Test
    fun `the identification page is left exactly as it was`() {
        val file = oggOpus()
        val before = parse(file).first()

        OggOpusTagger.write(file, listOf("TITLE" to "Song"))

        val after = parse(file).first()
        assertTrue(before.payload.contentEquals(after.payload))
        assertEquals(BOS, after.type)
        assertEquals(0, after.sequence)
    }

    @Test
    fun `the encoder's vendor string survives — it describes the encoder, not us`() {
        val file = oggOpus()

        OggOpusTagger.write(file, listOf("TITLE" to "Song"))

        val packet = commentPacket(parse(file))
        assertEquals("libopus test", String(packet, 12, intLE(packet, 8)))
    }

    @Test
    fun `a file that is not Ogg Opus is refused and left untouched`() {
        val notOgg = temp.newFile("song.opus").apply { writeBytes(ByteArray(2_048) { 7 }) }
        val before = notOgg.readBytes()

        assertFalse(OggOpusTagger.write(notOgg, listOf("TITLE" to "Song")))

        assertTrue(before.contentEquals(notOgg.readBytes()))
    }

    @Test
    fun `an Ogg stream that is not Opus is refused`() {
        // An Ogg Vorbis file: same container, different codec — jaudiotagger's job, not this one.
        val vorbis = temp.newFile("song.ogg").apply {
            writeBytes(page(byteArrayOf(1) + "vorbis".toByteArray(), serial = 1, sequence = 0, granule = 0, type = BOS))
        }

        assertFalse(OggOpusTagger.isOggOpus(vorbis))
        assertFalse(OggOpusTagger.write(vorbis, listOf("TITLE" to "Song")))
    }

    @Test
    fun `a truncated stream leaves the original file alone`() {
        val file = oggOpus()
        val whole = file.readBytes()
        file.writeBytes(whole.copyOfRange(0, whole.size / 2 + 1)) // cut mid-page
        val before = file.readBytes()

        assertFalse(OggOpusTagger.write(file, listOf("TITLE" to "Song")))

        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(0, temp.root.listFiles { f -> f.name.endsWith(".tags") }!!.size)
    }

    @Test
    fun `tagging twice is idempotent — the second pass replaces the first`() {
        val file = oggOpus()

        OggOpusTagger.write(file, listOf("TITLE" to "First"), OggOpusTagger.describeImage(jpeg(64, 64, 50_000)))
        OggOpusTagger.write(file, listOf("TITLE" to "Second"))

        assertEquals("Second", comments(file)["TITLE"])
        assertEquals(null, comments(file)["METADATA_BLOCK_PICTURE"])
        parse(file).forEachIndexed { index, page ->
            assertTrue(page.crcValid)
            assertEquals(index, page.sequence)
        }
    }

    @Test
    fun `PNG covers are described as PNG`() {
        val png = ByteArray(64).also {
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47).copyInto(it)
            intoBE(it, 16, 500)
            intoBE(it, 20, 300)
        }

        val cover = OggOpusTagger.describeImage(png)!!

        assertEquals("image/png", cover.mimeType)
        assertEquals(500, cover.width)
        assertEquals(300, cover.height)
    }

    @Test
    fun `bytes that are not an image are not embedded`() {
        assertEquals(null, OggOpusTagger.describeImage("<html>404</html>".toByteArray()))
    }

    // ---- helpers ----

    /** A JPEG whose SOF0 declares [width]x[height], padded with [padding] bytes of "scan data". */
    private fun jpeg(width: Int, height: Int, padding: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)) // APP0, length 16
        out.write(ByteArray(14))
        out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11)) // SOF0, length 17
        out.write(8) // precision
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

    private fun intBE(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun intoBE(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }

    /** Ogg's CRC — written here independently of the production one so a shared bug can't hide. */
    private fun crcInto(page: ByteArray) {
        for (i in 22..25) page[i] = 0
        var crc = 0
        for (byte in page) {
            var value = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) {
                value = if (value and 0x80000000.toInt() != 0) (value shl 1) xor 0x04c11db7 else value shl 1
            }
            crc = value
        }
        page[22] = (crc and 0xFF).toByte()
        page[23] = ((crc ushr 8) and 0xFF).toByte()
        page[24] = ((crc ushr 16) and 0xFF).toByte()
        page[25] = ((crc ushr 24) and 0xFF).toByte()
    }

    private companion object {
        const val BOS = 0x02
        var counter = 0
    }
}
