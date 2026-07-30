package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.SoundGenre

/**
 * A catalogue's genre string → one [SoundGenre] family.
 *
 * The string is whatever the source happened to say, in whatever language its storefront speaks: Deezer
 * returns "Rap/Hip Hop" and "Musica Mexicana", Apple returns "Hip-Hop/Rap", "Música mexicana", "Urbano
 * latino", "Électronique", "Bandas sonoras". So matching folds accents and punctuation (via
 * [RecsBlender.nameKey], the same normalization the feed's dedup uses) and then looks for keywords in
 * **Spanish, English, Portuguese and French** — the app's four locales, and, more to the point, the four
 * languages its providers answer in.
 *
 * **Order is the whole design.** The first rule that matches wins, so the specific families come before
 * the general ones: "reggaeton" before "reggae", "k pop" before "pop", "latin trap" before "rap". A
 * string nothing recognises returns [SoundGenre.UNKNOWN], which the curve table deliberately shapes to
 * flat — guessing a family from an unknown word would be inventing a sound the user never asked for.
 */
object GenreClassifier {

    private val folder = RecsBlender()

    /** The family behind [raw], or [SoundGenre.UNKNOWN] when nothing matches (including a blank). */
    fun classify(raw: String?): SoundGenre {
        val folded = raw?.takeIf { it.isNotBlank() }?.let { folder.nameKey(it) } ?: return SoundGenre.UNKNOWN
        return RULES.firstOrNull { (_, keywords) -> keywords.any { it in folded } }?.first ?: SoundGenre.UNKNOWN
    }

    /**
     * Keyword → family, most specific first. Keywords are already folded (lowercase, no accents, no
     * punctuation) and matched as plain substrings, so stems work: "alternativ" covers "alternative",
     * "alternativa" and "alternatif".
     *
     * Substring matching does mean "rap" also matches "trap" — deliberate, both are the same family, and
     * the latin-urban forms of trap are claimed by the reggaeton rule above it.
     */
    private val RULES: List<Pair<SoundGenre, List<String>>> = listOf(
        // Speech, first: a podcast wants intelligibility, and no music rule should be allowed to claim it.
        SoundGenre.SPOKEN to listOf(
            "podcast", "audiobook", "audiolibro", "livre audio", "spoken", "hablado", "falado",
            "comedy", "comedia", "comedie", "humor", "conferencia", "entrevista", "noticias", "news talk",
        ),
        // Before hip hop, because "Lofi Hip Hop" contains it: lo-fi is *made* of hiss and vinyl noise, so
        // the calmer, top-end-safe curve is the one that genre actually wants.
        SoundGenre.LOFI_CHILL to listOf(
            "lo fi", "lofi", "chill", "ambient", "downtempo", "new age", "meditat", "sleep",
            "relax", "study", "instrumental beats",
        ),
        // Before regional Mexican, because "Bandas sonoras" contains "banda".
        SoundGenre.SOUNDTRACK to listOf(
            "soundtrack", "banda sonora", "bandas sonoras", "bande originale", "trilha sonora",
            "score", "films", "film", "cine", "games", "juegos", "musical", "broadway",
        ),
        // Latin urban before both reggae and hip hop: it shares words with both and sounds like neither.
        SoundGenre.REGGAETON to listOf(
            "reggaeton", "regueton", "reggeaton", "urbano latino", "latin urban", "urban latino",
            "dembow", "trap latino", "latin trap", "perreo", "urbano",
        ),
        SoundGenre.REGGAE_DANCEHALL to listOf("reggae", "dancehall", "ragga", "dub", "ska", "roots"),
        SoundGenre.HIPHOP to listOf("hip hop", "hiphop", "rap", "drill", "grime", "boom bap"),
        // Regional Mexican is this library's biggest family; it must not fall through to a generic "latin".
        SoundGenre.LATIN_REGIONAL to listOf(
            "regional mexicano", "musica mexicana", "mexican", "banda", "corrido", "mariachi",
            "norteno", "sierreno", "ranchera", "grupera", "tejano", "duranguense", "sertanejo",
        ),
        // Also the home of a bare "Latin" / "Música latina", which iTunes returns constantly: by now the
        // urban and regional forms have already been claimed above, so what is left is the tropical,
        // percussion-forward half of the word.
        SoundGenre.LATIN_TROPICAL to listOf(
            "salsa", "cumbia", "bachata", "merengue", "vallenato", "tropical", "bolero", "timba",
            "samba", "pagode", "forro", "axe", "mpb", "bossa", "son cubano", "cuban", "brazil",
            "brasil", "latin",
        ),
        SoundGenre.METAL to listOf("metal", "hardcore", "thrash", "grindcore", "screamo", "djent", "death"),
        // Before indie/alt so "Alternative Rock" is shaped as rock; a bare "Alternativa" still lands below.
        SoundGenre.ROCK to listOf("rock", "punk", "grunge", "britpop", "psychedelic", "blues"),
        // "alternati", not "alternativ": the French is "alternatif".
        SoundGenre.INDIE_ALT to listOf("indie", "alternati", "shoegaze", "emo", "post punk"),
        SoundGenre.ELECTRONIC to listOf(
            "electro", "electronic", "electronica", "electronique", "eletronica", "edm", "house",
            "techno", "trance", "dubstep", "drum and bass", "dnb", "dance", "club", "synthwave",
            "eurodance", "hardstyle", "garage",
        ),
        SoundGenre.RNB_SOUL to listOf("r b", "rnb", "soul", "funk", "motown", "disco", "gospel"),
        // Before pop, or every one of these would be swallowed by the substring "pop".
        SoundGenre.KPOP_JPOP to listOf("k pop", "kpop", "j pop", "jpop", "c pop", "cpop", "anime", "korean", "japanese", "asian"),
        SoundGenre.JAZZ to listOf("jazz", "swing", "bebop", "big band"),
        SoundGenre.CLASSICAL to listOf(
            "classical", "clasica", "clasico", "classique", "classica", "opera", "orchestr",
            "orquest", "symphon", "sinfon", "baroque", "barroc", "chamber", "erudita", "coral",
        ),
        SoundGenre.COUNTRY to listOf("country", "bluegrass", "americana"),
        SoundGenre.ACOUSTIC_FOLK to listOf(
            "folk", "acoustic", "acustic", "singer songwriter", "cantautor", "chanson", "world",
            "musique du monde", "musica del mundo", "celtic", "arabic", "african", "flamenco", "tango",
        ),
        // Nearly the catch-all, so it goes last: "pop" is a substring of half the genre names above.
        SoundGenre.POP to listOf("pop", "variete", "schlager", "kids", "infantil", "christian", "hits"),
    )
}
