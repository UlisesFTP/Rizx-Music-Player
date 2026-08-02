package fm.rizx.player.domain.model

/**
 * What is actually coming out of the speaker, as far as anyone can honestly tell.
 *
 * Every field is nullable because every field is *measured*, and most sources report almost none of it.
 * A YouTube stream knows its codec and bitrate and nothing else; a verified community FLAC knows all of
 * it, because 64 KiB of its header was read. Guessing the gaps — inferring 16/44.1 because that is what
 * most music is — would turn this from a readout into a decoration.
 */
data class AudioFormatUi(
    val codec: String? = null,
    val container: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val channels: Int? = null,
    val provenance: AudioProvenance? = null,
) {

    /**
     * Whether the codec is a lossless one — **decided by the codec, never by the bitrate**.
     *
     * A 900 kbps stream is not lossless because it is large, and a quiet passage encoded to 300 kbps of
     * FLAC has not stopped being lossless because it is small. Bitrate-based badges are how players end
     * up calling a high-bitrate MP3 "HD".
     */
    val isLosslessCodec: Boolean
        get() = codec?.uppercase()?.substringBefore(' ') in LOSSLESS_CODECS

    /**
     * The one line under the player: `FLAC · 16-bit · 48 kHz`, or `OPUS · 160 kbps`.
     *
     * Depth and rate for a lossless codec (they are the thing worth knowing and they were read from the
     * file); bitrate for a lossy one (depth and rate are meaningless once it has been through a
     * psychoacoustic model). Parts nobody reported are simply left out.
     *
     * Not localised, deliberately: `kbps`, `kHz` and `bit` are written the same way everywhere, and a
     * translated unit here would be less recognisable, not more.
     */
    val shortLabel: String?
        get() {
            val name = codec?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
            val parts = buildList {
                add(name)
                if (isLosslessCodec) {
                    bitsPerSample?.let { add("$it-bit") }
                    sampleRateHz?.let { add(kHz(it)) }
                } else {
                    bitrateKbps?.let { add("$it kbps") }
                    sampleRateHz?.let { add(kHz(it)) }
                }
            }
            return parts.joinToString(" · ")
        }

    /** 44100 → "44.1 kHz", 48000 → "48 kHz". */
    private fun kHz(hz: Int): String {
        val whole = hz / 1000
        val tenths = (hz % 1000) / 100
        return if (tenths == 0) "$whole kHz" else "$whole.$tenths kHz"
    }

    private companion object {
        /**
         * Codecs that reconstruct the original samples exactly. Deliberately short: anything not on this
         * list is treated as lossy, which is the safe direction to be wrong in.
         */
        val LOSSLESS_CODECS = setOf("FLAC", "ALAC", "PCM", "WAV", "AIFF", "WAVE")
    }
}
