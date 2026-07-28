package fm.rizx.player.data.lyrics

import fm.rizx.player.domain.model.LyricLine
import fm.rizx.player.domain.model.LyricWord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parses Musixmatch's **richsync** body into word-timed [LyricLine]s.
 *
 * The body is a JSON array of lines: `ts`/`te` are the line's start/end in **seconds** (fractional), `l`
 * is the list of chunks with `c` (the characters) and `o` (an offset in seconds **relative to `ts`**),
 * and `x` is the whole line as plain text.
 *
 * Seconds→milliseconds is the only real trap here; the rest is shape. A malformed body yields an empty
 * list so the provider simply loses its turn in the chain.
 */
object RichSyncParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Serializable
    private data class RichLine(
        val ts: Double = 0.0,
        val te: Double = 0.0,
        @SerialName("l") val chunks: List<RichChunk> = emptyList(),
        @SerialName("x") val text: String = "",
    )

    @Serializable
    private data class RichChunk(
        @SerialName("c") val characters: String = "",
        @SerialName("o") val offsetSeconds: Double = 0.0,
    )

    fun parse(body: String?): List<LyricLine> {
        if (body.isNullOrBlank()) return emptyList()
        val parsed = runCatching { json.decodeFromString<List<RichLine>>(body) }.getOrNull() ?: return emptyList()

        return parsed.mapNotNull { line ->
            val startMs = (line.ts * 1000).toLong()
            val endMs = (line.te * 1000).toLong()
            val words = line.chunks.mapIndexed { i, chunk ->
                val wordStart = startMs + (chunk.offsetSeconds * 1000).toLong()
                // A chunk ends where the next begins; the last one runs to the line's own end.
                val wordEnd = line.chunks.getOrNull(i + 1)
                    ?.let { startMs + (it.offsetSeconds * 1000).toLong() }
                    ?: endMs
                LyricWord(startMs = wordStart, endMs = maxOf(wordEnd, wordStart), text = chunk.characters)
            }
            val text = line.text.takeIf { it.isNotBlank() }
                ?: words.joinToString(separator = "") { it.text }.trim()
            if (words.isEmpty() && text.isBlank()) return@mapNotNull null
            LyricLine(
                timeMs = startMs,
                text = text,
                words = words.takeIf { w -> w.any { it.text.isNotBlank() } }.orEmpty(),
            )
        }.sortedBy(LyricLine::timeMs)
    }
}
