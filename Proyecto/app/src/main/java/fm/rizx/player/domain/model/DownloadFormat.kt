package fm.rizx.player.domain.model

/**
 * What lands on disk when a song is downloaded.
 *
 * Only one of these ever *re-encodes* ([MP3]), and only in one direction — towards the universally
 * compatible format. The other three are different answers to "which of the source's own renditions do
 * you keep": conversions that would invent quality (lossy → FLAC) or discard it for nothing are not
 * options here, by the same rule the downloader has always had.
 */
enum class DownloadFormat {
    /**
     * The bytes as they arrive, with the provider's taggable pick — YouTube's M4A/AAC, Audius's MP3.
     * The default, and byte-for-byte the behaviour every existing download was made under.
     */
    ORIGINAL,

    /**
     * The best compressed rendition the source has — YouTube's Opus 160k/48kHz — saved as-is, no
     * re-encode. The same pick streaming's high-quality mode already makes; downloads just never offered
     * it. Tradeoff, stated where the option is offered: its WebM container takes no embedded tags, so the
     * file is complete inside Rizx (the index carries metadata and artwork) but exports bare.
     */
    OPUS,

    /**
     * LAME 320 kbps CBR, re-encoded from the best source available (Opus over AAC, to hand the encoder
     * the most information). Exists for compatibility — car stereos, old players — not for quality: the
     * ceiling is the lossy source, and 320 is what loses the least *more* in the conversion. A source
     * that is already MP3 is kept as-is rather than re-encoded.
     */
    MP3,

    /**
     * A verified community FLAC when one exists for the song, falling back to [ORIGINAL] when none does.
     * The download-shaped half of `AudioQualityMode.LOSSLESS_PREFERRED`; the old "download FLAC" switch
     * migrates here.
     */
    FLAC,
    ;

    /** Whether this format wants the provider's best-sounding rendition rather than the taggable one. */
    val prefersBestSource: Boolean get() = this == OPUS || this == MP3
}
