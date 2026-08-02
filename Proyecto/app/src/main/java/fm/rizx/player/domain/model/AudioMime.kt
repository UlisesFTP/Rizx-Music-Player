package fm.rizx.player.domain.model

/**
 * What a mime type says about the audio inside — pure string knowledge, shared by the local scan, the
 * file picker, and the rows that badge lossless files.
 */

/**
 * The codec a mime type states unambiguously, or null.
 *
 * Deliberately incomplete: `audio/mp4` could be AAC or ALAC and `audio/ogg` Vorbis or Opus, and a
 * readout that guesses stops being a readout. Only the mimes that name their codec are mapped; the rest
 * still play fine, they just don't claim anything.
 */
fun codecForMime(mime: String?): String? = when (mime?.lowercase()?.substringBefore(';')?.trim()) {
    "audio/flac", "audio/x-flac" -> "FLAC"
    "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "WAV"
    "audio/mpeg", "audio/mp3" -> "MP3"
    "audio/aac", "audio/aacp" -> "AAC"
    "audio/opus" -> "OPUS"
    else -> null
}

/** The file extension a mime maps to, for `Stream.container`. Null when the mime doesn't say. */
fun containerForMime(mime: String?): String? = when (mime?.lowercase()?.substringBefore(';')?.trim()) {
    "audio/flac", "audio/x-flac" -> "flac"
    "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
    "audio/ogg", "audio/opus" -> "ogg"
    "audio/webm" -> "webm"
    "audio/aac", "audio/aacp" -> "aac"
    else -> null
}
