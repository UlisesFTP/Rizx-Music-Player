package fm.rizx.player.data.lossless

/**
 * Builds FLAC heads byte by byte, including the malformed ones.
 *
 * Hand-built rather than checked in as a binary because a real file only ever proves the one layout it
 * happens to have, and the failures worth testing — a truncated block, a lying length, a 36-bit sample
 * count — are precisely the ones no valid file contains.
 */
internal object FlacFixtures {

    /** 44.1 kHz, 16-bit, stereo, three minutes: what a CD rip looks like. */
    fun head(
        sampleRate: Int = 44_100,
        channels: Int = 2,
        bits: Int = 16,
        totalSamples: Long = 44_100L * 180,
        md5: ByteArray = ByteArray(16) { 0x11 },
        declaredBlockLength: Int = 34,
        leadingPaddingBytes: Int = 0,
        omitStreamInfo: Boolean = false,
        magic: String = "fLaC",
    ): ByteArray {
        val out = ArrayList<Byte>()
        out += magic.toByteArray().toList()

        if (leadingPaddingBytes > 0) {
            out += 0x01.toByte() // PADDING, not last
            out += uint24(leadingPaddingBytes)
            repeat(leadingPaddingBytes) { out += 0 }
        }

        if (omitStreamInfo) {
            out += 0x81.toByte() // PADDING and *last* — the walk must give up rather than read on
            out += uint24(4)
            repeat(4) { out += 0 }
            return out.toByteArray()
        }

        out += 0x00.toByte() // STREAMINFO, not last
        out += uint24(declaredBlockLength)

        out += uint16(4096) // min block size
        out += uint16(4096) // max block size
        out += uint24(1000) // min frame size
        out += uint24(8000) // max frame size

        // The packed half: 20 bits of sample rate, 3 of channels-1, 5 of bits-1, 36 of total samples.
        val sr = sampleRate.toLong()
        val ch = (channels - 1).toLong()
        val bps = (bits - 1).toLong()
        out += ((sr shr 12) and 0xFF).toByte()
        out += ((sr shr 4) and 0xFF).toByte()
        out += (((sr and 0x0F) shl 4) or (ch shl 1) or (bps shr 4)).toByte()
        out += (((bps and 0x0F) shl 4) or ((totalSamples shr 32) and 0x0F)).toByte()
        out += ((totalSamples shr 24) and 0xFF).toByte()
        out += ((totalSamples shr 16) and 0xFF).toByte()
        out += ((totalSamples shr 8) and 0xFF).toByte()
        out += (totalSamples and 0xFF).toByte()
        out += md5.toList()

        return out.toByteArray()
    }

    /** A head padded out to [size] with audio-frame-ish filler, for tests that care about body length. */
    fun file(size: Int, sampleRate: Int = 44_100, totalSamples: Long = 44_100L * 180): ByteArray {
        val head = head(sampleRate = sampleRate, totalSamples = totalSamples)
        return head + ByteArray(maxOf(0, size - head.size)) { 0x7F }
    }

    private fun uint16(value: Int): List<Byte> =
        listOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    private fun uint24(value: Int): List<Byte> =
        listOf(((value shr 16) and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
}
