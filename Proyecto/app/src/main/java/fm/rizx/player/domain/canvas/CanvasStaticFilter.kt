package fm.rizx.player.domain.canvas

import fm.rizx.player.domain.model.CanvasAspect
import fm.rizx.player.domain.usecase.ArtistNameMatching
import fm.rizx.player.domain.usecase.RecsBlender

/**
 * How good a YouTube upload is *as a canvas*, which is a different question from whether it is the
 * right song.
 *
 * [CanvasTrackMatcher] answers "is this the same recording"; this answers "will anything move". They
 * have to be separate, because the strongest evidence for the second question is exactly the text the
 * first one has to ignore.
 */
enum class CanvasSuitability {
    /** An upload that is a still image, or isn't a music video at all. Never shown. */
    REJECTED,

    /** Moves, but isn't the song's video — a lyric card or a visualiser. Used only if nothing better. */
    LAST_RESORT,

    /** A real video with nothing to say either way. */
    ACCEPTED,

    /** The artist's own music video: their channel, their VEVO, or it says so on the tin. */
    PREFERRED,
}

/**
 * Reads a YouTube row's **raw title and uploader** and decides whether it can be a canvas.
 *
 * **Why this can't live in [fm.rizx.player.domain.match.RecordingIdentity].** That object *deletes*
 * "official audio", "lyrics" and "visualizer" before comparing, filed as platform decoration — and it
 * is right to: "Xavi - La Diabla (Official Video)" and a catalogue's "La Diabla" are the same
 * recording, and the lyrics matcher and the artwork borrower both depend on that. Here the identical
 * words mean the opposite thing: they are the tell that **the picture does not move**. Adding them to
 * its `VERSION_WORDS` would silently break two other features.
 *
 * This is the fix for the canvas that "resolved correctly, played correctly, and stood perfectly
 * still": most of YouTube Music's catalogue is auto-generated `- Topic` uploads whose entire video is
 * the square cover art.
 *
 * Matching is by **whole phrase on a space-padded folded string**, never `contains` on a bare word — a
 * substring scan reads "a**band**oned" as a band and "a**dj**acent" as a DJ, which is a mistake this
 * codebase has already made once (see `WikipediaArtistBioSource`).
 *
 * Pure Kotlin, no Android. Folding is [RecsBlender.nameKey], the same normalisation the rest of the
 * app's matching uses, so accents fold and "Reacción" meets "reaccion".
 */
object CanvasStaticFilter {

    private val blender = RecsBlender()

    /**
     * How suitable [title] from [uploader] is. [artist] is the track's own credit, used only to notice
     * that the uploader *is* the artist.
     */
    fun rate(title: String, uploader: String? = null, artist: String? = null): CanvasSuitability {
        val folded = pad(title)
        if (isTopicChannel(uploader)) return CanvasSuitability.REJECTED
        if (STATIC_PHRASES.any { it in folded }) return CanvasSuitability.REJECTED
        // A qualifier that is *only* "audio" — "Song (Audio)" — is the same upload as "(Official Audio)"
        // with less ceremony. Read from qualifier position, so a song called "Audio" survives.
        if (qualifiers(title).any { pad(it).trim() == "audio" }) return CanvasSuitability.REJECTED

        // Before PREFERRED on purpose: "Official Lyric Video" is a lyric video first and foremost.
        if (LYRIC_PHRASES.any { it in folded }) return CanvasSuitability.LAST_RESORT
        if (VIDEO_PHRASES.any { it in folded }) return CanvasSuitability.PREFERRED
        if (isArtistChannel(uploader, artist)) return CanvasSuitability.PREFERRED
        return CanvasSuitability.ACCEPTED
    }

    /**
     * Whether the *extracted* stream is a still image dressed as a video — the last gate, and the only
     * one that reads the actual file.
     *
     * A real music video is 16:9. A **square** frame from YouTube is cover art on a timeline: the
     * auto-generated uploads measure exactly 360×360, which is how this was first diagnosed on device.
     * Free to check, because the mapper already has the dimensions in hand.
     *
     * Only for YouTube. Apple's motion artwork is square *because* it is a cover, and that is the point.
     */
    fun isStillFrame(aspect: CanvasAspect): Boolean = aspect == CanvasAspect.SQUARE

    /** `"Radiohead - Topic"`, YouTube's auto-generated art-track channel. */
    private fun isTopicChannel(uploader: String?): Boolean {
        val tokens = uploader?.let { blender.nameKey(it) }?.split(' ')?.filter { it.isNotEmpty() }
        return !tokens.isNullOrEmpty() && tokens.last() == "topic"
    }

    /** A VEVO channel, or one whose name reads back as the artist ("DualipaVEVO", "Xavi Oficial"). */
    private fun isArtistChannel(uploader: String?, artist: String?): Boolean {
        val name = uploader?.takeIf { it.isNotBlank() } ?: return false
        if (" vevo " in pad(name) || blender.nameKey(name).replace(" ", "").endsWith("vevo")) return true
        val credit = artist?.takeIf { it.isNotBlank() } ?: return false
        return ArtistNameMatching.sameArtist(credit, name)
    }

    /** The `(…)`/`[…]` segments and the after-dash tail — where a qualifier actually qualifies. */
    private fun qualifiers(title: String): List<String> =
        QUALIFIER.findAll(title).map { it.groupValues[1] }.toList() +
            DASH_TAIL.findAll(title).map { it.groupValues[1] }.toList()

    /** Folded and space-padded, so a phrase test is a whole-word test. */
    private fun pad(raw: String): String = " " + blender.nameKey(raw) + " "

    /**
     * Uploads whose "video" is a static frame, or that aren't this song at all.
     *
     * **"audio oficial" is the one that matters most here.** In regional Mexican — most of this app's
     * catalogue — `(Audio Oficial)` and `(Video Letra)` are the dominant upload styles, and the
     * English-only list this feature shipped with waved every one of them through.
     */
    private val STATIC_PHRASES = listOf(
        " official audio ", " audio official ", " audio oficial ", " oficial audio ",
        " audio only ", " solo audio ", " audio completo ",
        " full album ", " album completo ", " disco completo ", " full ep ",
        " reaction ", " reaccion ", " reacciona ", " review ", " resena ",
        // Deliberately no " topic " here: the channel check above is what catches those, and Le Tigre's
        // "Hot Topic" is a real song that would otherwise never get a canvas.
    )

    /**
     * Moves, but it is text on a card rather than the song's film. Kept as a last resort instead of
     * rejected: for an artist with no music video it is the only thing that will ever animate, and it
     * is unambiguously *this* song.
     */
    private val LYRIC_PHRASES = listOf(
        " lyric video ", " lyrics video ", " lyric ", " lyrics ",
        " letra ", " letras ", " con letra ", " video letra ", " video con letra ", " video lirico ",
        " visualizer ", " visualiser ", " visualizador ",
    )

    /** It says on the tin that this is the film. */
    private val VIDEO_PHRASES = listOf(
        " official video ", " official music video ", " official videoclip ",
        " video oficial ", " videoclip oficial ", " videoclip ", " video musical ",
    )

    /** A `(…)` or `[…]` segment. */
    private val QUALIFIER = Regex("""[(\[]([^)\]]*)[)\]]""")

    /** Everything after a spaced dash — "Song - Audio". */
    private val DASH_TAIL = Regex("""\s[-–—]\s([^-–—]+)$""")
}
