package fm.rizx.player.data.lyrics

import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.LyricWord
import java.util.zip.Inflater

/**
 * Decodes and parses KuGou's **`krc`** karaoke format.
 *
 * A krc payload is base64 of: a 4-byte `krc1` magic, then zlib-compressed bytes XOR-ed with a fixed
 * 16-byte key. The key is a published constant of the format, not a credential — it obfuscates the
 * file, it doesn't protect anything.
 *
 * The decoded text is `[start,duration]<offset,duration,0>Word<offset,duration,0>Word`, where the word
 * offsets are **relative to the line start** (unlike NetEase's, which are absolute) — getting that wrong
 * would put every word at the top of the song.
 *
 * Everything degrades to an empty list: a format change must cost the user this provider, not the screen.
 */
object KrcParser {

    private val MAGIC = byteArrayOf('k'.code.toByte(), 'r'.code.toByte(), 'c'.code.toByte(), '1'.code.toByte())
    private val KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
    )

    private val LINE_HEADER = Regex("""^\[(\d+),(\d+)]""")
    private val WORD = Regex("""<(\d+),(\d+),\d+>([^<]*)""")
    private val METADATA_TAG = Regex("""^\[[a-zA-Z#]+:[^]]*]$""")

    /** base64 krc → plain krc text, or null when it isn't a krc payload we can read. */
    fun decode(base64: String?): String? = runCatching {
        if (base64.isNullOrBlank()) return null
        val raw = java.util.Base64.getDecoder().decode(base64.trim())
        if (raw.size <= MAGIC.size || !raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val body = ByteArray(raw.size - MAGIC.size) { i -> (raw[i + MAGIC.size].toInt() xor KEY[i % KEY.size].toInt()).toByte() }
        inflate(body)
    }.getOrNull()

    fun parse(krcText: String?): List<LyricLine> {
        if (krcText.isNullOrBlank()) return emptyList()
        val lines = mutableListOf<LyricLine>()
        for (rawLine in krcText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || METADATA_TAG.matches(line)) continue
            val header = LINE_HEADER.find(line) ?: continue
            val lineStart = header.groupValues[1].toLongOrNull() ?: continue
            // Same header shape as yrc: `[start,duration]`. The duration closes the line.
            val lineDuration = header.groupValues[2].toLongOrNull() ?: 0L

            // Offsets are relative to the line — lift them to absolute so every source shares one model.
            val words = WORD.findAll(line).mapNotNull { it.toWord(lineStart) }.toList()
            if (words.isEmpty()) continue
            lines += LyricLine(
                timeMs = lineStart,
                text = words.joinToString(separator = "") { it.text }.trim(),
                words = words.takeIf { w -> w.any { it.text.isNotBlank() } }.orEmpty(),
                endMs = if (lineDuration > 0L) lineStart + lineDuration else 0L,
            )
        }
        return lines.sortedBy(LyricLine::timeMs)
    }

    /** Convenience: base64 straight to lines. */
    fun parseEncoded(base64: String?): List<LyricLine> = parse(decode(base64))

    private fun MatchResult.toWord(lineStartMs: Long): LyricWord? {
        val offset = groupValues[1].toLongOrNull() ?: return null
        val duration = groupValues[2].toLongOrNull() ?: return null
        val start = lineStartMs + offset
        return LyricWord(startMs = start, endMs = start + duration, text = groupValues[3])
    }

    private fun inflate(data: ByteArray): String {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    }
}
