package fm.rizx.player.data.download

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/**
 * Writes Vorbis comments — title, artist, album, date **and the cover art** — into an Ogg Opus file.
 *
 * Why this exists at all: jaudiotagger, which tags every other format the app downloads, has no Opus
 * support whatsoever (3.0.1 ships an Ogg *Vorbis* writer only), and YouTube delivers its Opus inside
 * **WebM**, which it cannot write either. Opus was therefore the one download format that reached the
 * user with no cover and no artist. [OpusRemuxer] moves the packets into Ogg; this writes the tags.
 *
 * Pure JVM and dependency-free on purpose — every byte it produces is exercised by unit tests.
 *
 * **The format** (RFC 7845 §5 for Opus, RFC 3533 for Ogg): a stream is a sequence of pages, each with a
 * 27-byte header plus a segment table. Page 0 carries the `OpusHead` identification packet; the
 * `OpusTags` comment packet occupies the pages right after it and must end on a page boundary, before
 * the first audio page. Replacing the comments means re-paginating that one packet — and because a
 * page's own header carries its sequence number and a CRC over itself, every page after it is
 * renumbered and re-checksummed on the way out.
 */
object OggOpusTagger {

    /** A cover to embed. [mimeType] is derived from the bytes, never trusted from the network. */
    data class Cover(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int) {
        // Data class equality on a ByteArray would compare references; these exist so the generated
        // equals/hashCode don't lie to a test that compares two covers.
        override fun equals(other: Any?): Boolean =
            other is Cover && bytes.contentEquals(other.bytes) && mimeType == other.mimeType &&
                width == other.width && height == other.height

        override fun hashCode(): Int = bytes.contentHashCode() * 31 + mimeType.hashCode()
    }

    /** Whether [file] is an Ogg stream whose first packet is an Opus identification header. */
    fun isOggOpus(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(64)
            val read = input.readFully(head)
            if (read < 36) return@use false
            val segments = head[26].toInt() and 0xFF
            val payload = 27 + segments
            head.startsWith(OGG_MAGIC, 0) && payload + 8 <= read && head.startsWith(OPUS_HEAD, payload)
        }
    }.getOrDefault(false)

    /**
     * Rewrites [file] in place with [comments] (`FIELD` to value, e.g. `ARTIST`) and an optional [cover].
     * Returns false — leaving the file untouched — when it isn't an Ogg Opus stream or is malformed.
     *
     * Any existing comments are replaced wholesale rather than merged: the caller knows the track's real
     * metadata, and a half-updated tag ("our title, the encoder's artist") is worse than either.
     */
    fun write(file: File, comments: List<Pair<String, String>>, cover: Cover? = null): Boolean {
        if (!isOggOpus(file)) return false
        val temp = File(file.parentFile, "${file.name}.tags")
        return try {
            file.inputStream().buffered().use { input ->
                temp.outputStream().buffered().use { output -> rewrite(input, output, comments, cover) }
            }
            replace(temp, file)
        } catch (_: Throwable) {
            temp.delete()
            false
        }
    }

    /** Builds the `METADATA_BLOCK_PICTURE` value: a FLAC picture block, base64-encoded (RFC 7845 §5.2). */
    fun pictureComment(cover: Cover): String {
        val out = ByteArrayOutputStream()
        val mime = cover.mimeType.toByteArray(Charsets.US_ASCII)
        out.writeIntBE(FRONT_COVER)
        out.writeIntBE(mime.size)
        out.write(mime)
        out.writeIntBE(0) // empty description
        out.writeIntBE(cover.width)
        out.writeIntBE(cover.height)
        out.writeIntBE(COLOUR_DEPTH)
        out.writeIntBE(0) // not a palette image
        out.writeIntBE(cover.bytes.size)
        out.write(cover.bytes)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /**
     * The picture's mime type and pixel size, read from the bytes themselves.
     *
     * The dimensions are cosmetic — players read the image regardless — but they are part of the block,
     * and a few desktop players show them, so paying thirty lines for the truth beats writing zeros.
     * An unrecognised image is not a picture we should embed: null means "skip the cover".
     */
    fun describeImage(bytes: ByteArray): Cover? = when {
        bytes.startsWith(JPEG_MAGIC, 0) -> Cover(bytes, "image/jpeg", 0, 0).withSize(jpegSize(bytes))
        bytes.startsWith(PNG_MAGIC, 0) -> Cover(bytes, "image/png", 0, 0).withSize(pngSize(bytes))
        else -> null
    }

    private fun Cover.withSize(size: Pair<Int, Int>?): Cover =
        if (size == null) this else copy(width = size.first, height = size.second)

    // ---- rewriting ----

    private fun rewrite(input: InputStream, output: OutputStream, comments: List<Pair<String, String>>, cover: Cover?) {
        val head = readPage(input) ?: throw IOException("not an Ogg stream")
        if (!head.raw.startsWith(OPUS_HEAD, head.payloadOffset)) throw IOException("not an Opus stream")
        output.write(head.raw) // page 0 keeps its sequence number (0) and therefore its CRC

        // The old comment packet: however many pages it spans, all of them go.
        var page = readPage(input) ?: throw IOException("no comment header")
        if (!page.raw.startsWith(OPUS_TAGS, page.payloadOffset)) throw IOException("no comment header")
        val vendor = readVendor(page)
        while (page.continuesIntoNextPage) {
            page = readPage(input) ?: throw IOException("truncated comment header")
        }

        val packet = buildOpusTags(vendor, comments, cover)
        var sequence = writePacketPages(output, packet, head.serial)
        // Everything from here is audio, unchanged except for the renumbering our re-pagination forces.
        while (true) {
            val next = readPage(input) ?: break
            output.write(next.renumbered(sequence++))
        }
    }

    private fun buildOpusTags(vendor: ByteArray, comments: List<Pair<String, String>>, cover: Cover?): ByteArray {
        val fields = comments.filter { (_, value) -> value.isNotBlank() }
            .map { (key, value) -> "$key=$value".toByteArray(Charsets.UTF_8) }
            .toMutableList()
        cover?.let { fields += "$PICTURE_FIELD=${pictureComment(it)}".toByteArray(Charsets.UTF_8) }

        val out = ByteArrayOutputStream()
        out.write(OPUS_TAGS)
        out.writeIntLE(vendor.size)
        out.write(vendor)
        out.writeIntLE(fields.size)
        fields.forEach { field ->
            out.writeIntLE(field.size)
            out.write(field)
        }
        return out.toByteArray()
    }

    /**
     * Pages [packet] out as the comment header and returns the next free sequence number.
     *
     * A packet is laced into 255-byte segments (the final one shorter — a length that is an exact
     * multiple of 255 still needs its zero-length terminator, or readers would wait for a continuation
     * that never comes), and at most 255 segments fit in a page. A cover pushes the header well past one
     * page's 65025 bytes, so the multi-page path is the normal case here, not the exotic one.
     */
    private fun writePacketPages(output: OutputStream, packet: ByteArray, serial: Int): Int {
        val laces = buildList {
            var left = packet.size
            while (left >= MAX_LACE) {
                add(MAX_LACE)
                left -= MAX_LACE
            }
            add(left)
        }
        var sequence = 1
        var offset = 0
        laces.chunked(MAX_SEGMENTS).forEachIndexed { index, group ->
            val bytes = group.sum()
            val header = ByteArray(27 + group.size)
            OGG_MAGIC.copyInto(header)
            header[5] = if (index == 0) 0 else CONTINUED // the header packet is never BOS/EOS
            // granule position stays 0: a header page carries no audio (RFC 7845 §3).
            serial.intoLE(header, 14)
            sequence.intoLE(header, 18)
            header[26] = group.size.toByte()
            group.forEachIndexed { i, lace -> header[27 + i] = lace.toByte() }

            val page = header + packet.copyOfRange(offset, offset + bytes)
            output.write(page.checksummed())
            offset += bytes
            sequence++
        }
        return sequence
    }

    // ---- pages ----

    private class Page(val raw: ByteArray) {
        val segmentCount: Int get() = raw[26].toInt() and 0xFF
        val payloadOffset: Int get() = 27 + segmentCount

        /** A page whose last segment is 255 bytes has a packet running into the next page. */
        val continuesIntoNextPage: Boolean
            get() = segmentCount > 0 && (raw[26 + segmentCount].toInt() and 0xFF) == MAX_LACE

        val serial: Int get() = raw.intLE(14)

        fun renumbered(sequence: Int): ByteArray {
            val copy = raw.copyOf()
            sequence.intoLE(copy, 18)
            return copy.checksummed()
        }
    }

    /** Reads one whole page, or null at a clean end of stream. */
    private fun readPage(input: InputStream): Page? {
        val header = ByteArray(27)
        val read = input.readFully(header)
        if (read == 0) return null
        if (read < 27 || !header.startsWith(OGG_MAGIC, 0)) throw IOException("malformed Ogg page")
        val segments = header[26].toInt() and 0xFF
        val laces = ByteArray(segments)
        if (input.readFully(laces) < segments) throw IOException("truncated segment table")
        val payloadSize = laces.sumOf { it.toInt() and 0xFF }
        val payload = ByteArray(payloadSize)
        if (input.readFully(payload) < payloadSize) throw IOException("truncated page payload")
        return Page(header + laces + payload)
    }

    /** The vendor string of an existing comment header — kept, since it describes the encoder, not us. */
    private fun readVendor(page: Page): ByteArray {
        val start = page.payloadOffset + OPUS_TAGS.size
        if (start + 4 > page.raw.size) return DEFAULT_VENDOR
        val length = page.raw.intLE(start)
        if (length < 0 || start + 4 + length > page.raw.size) return DEFAULT_VENDOR
        return page.raw.copyOfRange(start + 4, start + 4 + length)
    }

    /** Zeroes the CRC field, checksums the whole page, and writes the result back into it. */
    private fun ByteArray.checksummed(): ByteArray {
        for (i in 22..25) this[i] = 0
        crc32(this).intoLE(this, 22)
        return this
    }

    /**
     * Ogg's own CRC: polynomial 0x04c11db7, no reflection of input or output, zero initial value and no
     * final XOR — every one of which differs from the usual zlib CRC-32, so the stock implementation
     * cannot be used here.
     */
    private fun crc32(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    private val CRC_TABLE = IntArray(256) { index ->
        var value = index shl 24
        repeat(8) {
            value = if (value and 0x80000000.toInt() != 0) (value shl 1) xor 0x04c11db7 else value shl 1
        }
        value
    }

    // ---- image probing ----

    /** Width/height from the first start-of-frame marker; null when the JPEG is unreadable. */
    private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) {
                i++
                continue
            }
            val marker = bytes[i + 1].toInt() and 0xFF
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            // SOF0..SOF15, minus the four markers that share the range but frame nothing.
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                return width to height
            }
            if (length <= 0) return null
            i += 2 + length
        }
        return null
    }

    /** PNG puts them at a fixed offset, in the mandatory first chunk. */
    private fun pngSize(bytes: ByteArray): Pair<Int, Int>? =
        if (bytes.size < 24) null else bytes.intBE(16) to bytes.intBE(20)

    // ---- bytes ----

    private fun InputStream.readFully(into: ByteArray): Int {
        var read = 0
        while (read < into.size) {
            val n = read(into, read, into.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun ByteArray.startsWith(prefix: ByteArray, at: Int): Boolean {
        if (at < 0 || at + prefix.size > size) return false
        return prefix.indices.all { this[at + it] == prefix[it] }
    }

    private fun ByteArray.intLE(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or ((this[at + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.intBE(at: Int): Int =
        ((this[at].toInt() and 0xFF) shl 24) or ((this[at + 1].toInt() and 0xFF) shl 16) or
            ((this[at + 2].toInt() and 0xFF) shl 8) or (this[at + 3].toInt() and 0xFF)

    private fun Int.intoLE(target: ByteArray, at: Int) {
        target[at] = (this and 0xFF).toByte()
        target[at + 1] = ((this ushr 8) and 0xFF).toByte()
        target[at + 2] = ((this ushr 16) and 0xFF).toByte()
        target[at + 3] = ((this ushr 24) and 0xFF).toByte()
    }

    private fun ByteArrayOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntBE(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    /** POSIX rename replaces atomically; the delete-first fallback is for the filesystems that don't. */
    private fun replace(temp: File, target: File): Boolean {
        if (temp.renameTo(target)) return true
        return target.delete() && temp.renameTo(target)
    }

    private val OGG_MAGIC = "OggS".toByteArray(Charsets.US_ASCII)
    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)
    private val OPUS_TAGS = "OpusTags".toByteArray(Charsets.US_ASCII)
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val DEFAULT_VENDOR = "Rizx".toByteArray(Charsets.UTF_8)

    private const val PICTURE_FIELD = "METADATA_BLOCK_PICTURE"
    private const val FRONT_COVER = 3
    private const val COLOUR_DEPTH = 24
    private const val MAX_LACE = 255
    private const val MAX_SEGMENTS = 255
    private const val CONTINUED = 0x01.toByte()
}
