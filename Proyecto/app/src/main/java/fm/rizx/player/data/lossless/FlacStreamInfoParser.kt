package fm.rizx.player.data.lossless

import fm.rizx.player.domain.lossless.FlacStreamInfo

/**
 * Reads a FLAC file's STREAMINFO block out of its opening bytes.
 *
 * **The extension is not evidence.** A `.flac` URL can serve an MP3, an HTML error page, or a truncated
 * download, and all three would otherwise reach ExoPlayer and fail as a playback error the user sees.
 * Four bytes settle it, and the block behind them carries the only technical numbers this app is
 * entitled to display — a bit depth read here was measured, one inferred from a bitrate was invented.
 *
 * Reading it also does a second job the index cannot: it yields the file's **real duration**, which with
 * a minimal index (title, artist, URL and nothing else) is the only thing that can tell this recording
 * from another one with the same name. That is why [FlacInspector][fm.rizx.player.domain.lossless.FlacInspector]
 * runs inside the match rather than after it.
 *
 * Pure Kotlin over a byte array, so every branch is a unit test with a hand-built fixture — no network,
 * no files, no device.
 */
object FlacStreamInfoParser {

    /** `fLaC`, the four bytes every FLAC bitstream starts with. */
    private val MAGIC = byteArrayOf(0x66, 0x4C, 0x61, 0x43)

    private const val STREAMINFO_TYPE = 0
    private const val STREAMINFO_LENGTH = 34
    private const val BLOCK_HEADER_LENGTH = 4

    /**
     * A metadata block's length is 24 bits, so a corrupt header can claim ~16 MB and send the walk off
     * the end. The loop is bounded by the buffer anyway; this just stops it spinning through nonsense.
     */
    private const val MAX_BLOCKS = 64

    // The spec's own limits. Anything outside them means the bytes were misread, not that some exotic
    // file exists: 0 channels or 0 Hz is not a stream, it's a wrong offset.
    private val SAMPLE_RATE_RANGE = 8_000..768_000
    private val CHANNELS_RANGE = 1..8
    private val BITS_RANGE = 4..32

    /**
     * [bytes] is the head of the file (the inspector reads 64 KiB). Returns `null` for anything that
     * isn't a FLAC bitstream with a sane STREAMINFO — a miss, never an exception.
     */
    fun parse(bytes: ByteArray): FlacStreamInfo? {
        if (!hasMagic(bytes)) return null

        // STREAMINFO is required to be the first block, but walking to it costs nothing and survives a
        // file that puts something else first.
        var offset = MAGIC.size
        repeat(MAX_BLOCKS) {
            if (offset + BLOCK_HEADER_LENGTH > bytes.size) return null
            val header = bytes[offset].toInt()
            val isLast = header and 0x80 != 0
            val type = header and 0x7F
            val length = readUInt24(bytes, offset + 1)
            val body = offset + BLOCK_HEADER_LENGTH

            if (type == STREAMINFO_TYPE) {
                if (length != STREAMINFO_LENGTH) return null
                if (body + STREAMINFO_LENGTH > bytes.size) return null // truncated download
                return readStreamInfo(bytes, body)
            }
            if (isLast) return null // walked the whole header and there was no STREAMINFO
            offset = body + length
        }
        return null
    }

    private fun hasMagic(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /**
     * The 34-byte block, whose interesting half is bit-packed rather than byte-aligned:
     * ```
     *  [10..17]  sample rate 20 | channels-1 3 | bits-1 5 | total samples 36
     *  [18..33]  MD5 of the unencoded audio
     * ```
     */
    private fun readStreamInfo(b: ByteArray, at: Int): FlacStreamInfo? {
        val sampleRate = (u(b[at + 10]) shl 12) or (u(b[at + 11]) shl 4) or (u(b[at + 12]) shr 4)
        val channels = ((u(b[at + 12]) shr 1) and 0x07) + 1
        val bitsPerSample = (((u(b[at + 12]) and 0x01) shl 4) or ((u(b[at + 13]) shr 4) and 0x0F)) + 1

        // 36 bits: the low nibble of byte 13 plus the next four whole bytes. Long throughout — a
        // three-hour recording at 96 kHz overflows a signed Int, and the failure would be a negative
        // duration that silently rejects a perfectly good file.
        val totalSamples = ((u(b[at + 13]).toLong() and 0x0F) shl 32) or
            (u(b[at + 14]).toLong() shl 24) or
            (u(b[at + 15]).toLong() shl 16) or
            (u(b[at + 16]).toLong() shl 8) or
            u(b[at + 17]).toLong()

        if (sampleRate !in SAMPLE_RATE_RANGE) return null
        if (channels !in CHANNELS_RANGE) return null
        if (bitsPerSample !in BITS_RANGE) return null
        if (totalSamples <= 0L) return null // the spec's "unknown length"; nothing to match a duration against

        val durationMs = totalSamples * 1000L / sampleRate
        if (durationMs <= 0L) return null

        return FlacStreamInfo(
            sampleRateHz = sampleRate,
            bitsPerSample = bitsPerSample,
            channels = channels,
            totalSamples = totalSamples,
            durationMs = durationMs,
            streamInfoMd5Hex = readMd5(b, at + 18),
        )
    }

    /** All-zero means "the encoder didn't record one", which is absence rather than a value of zero. */
    private fun readMd5(b: ByteArray, at: Int): String? {
        val md5 = ByteArray(16) { b[at + it] }
        if (md5.all { it.toInt() == 0 }) return null
        return md5.joinToString("") { "%02x".format(it) }
    }

    private fun readUInt24(b: ByteArray, at: Int): Int =
        (u(b[at]) shl 16) or (u(b[at + 1]) shl 8) or u(b[at + 2])

    /** Kotlin's `Byte` is signed; every field here is not. */
    private fun u(value: Byte): Int = value.toInt() and 0xFF
}
