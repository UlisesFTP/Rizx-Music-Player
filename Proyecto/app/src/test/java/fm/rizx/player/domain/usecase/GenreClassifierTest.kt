package fm.rizx.player.domain.usecase

import fm.rizx.player.domain.model.SoundGenre
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every string here is one a provider actually returns: Deezer's English genre names from
 * `/album/{id}`, and Apple/iTunes' `primaryGenreName` in the four storefront languages the app ships.
 */
class GenreClassifierTest {

    private fun assertGenre(expected: SoundGenre, vararg raw: String) {
        raw.forEach { assertEquals(it, expected, GenreClassifier.classify(it)) }
    }

    @Test
    fun `latin urban is claimed before reggae and before rap`() {
        assertGenre(
            SoundGenre.REGGAETON,
            "Reggaeton", "Reggaetón", "Urbano latino", "Latin Urban", "Reggaetón y hip-hop",
            "Latin Trap", "Trap Latino", "Urbano",
        )
    }

    @Test
    fun `hip hop in every spelling the catalogues use`() {
        assertGenre(SoundGenre.HIPHOP, "Hip-Hop/Rap", "Rap/Hip Hop", "Hip hop/Rap", "Trap", "Drill", "Rap français")
    }

    @Test
    fun `regional mexican is its own family, not generic latin`() {
        assertGenre(
            SoundGenre.LATIN_REGIONAL,
            "Regional Mexicano", "Música mexicana", "Musica Mexicana", "Regional Mexican",
            "Banda", "Corridos", "Mariachi", "Norteño", "Sierreño", "Sertanejo",
        )
    }

    @Test
    fun `the tropical half of latin, including a bare Latin`() {
        assertGenre(
            SoundGenre.LATIN_TROPICAL,
            "Salsa", "Cumbia", "Bachata", "Latin", "Música latina", "Brazilian Music", "Samba", "Bossa Nova",
        )
    }

    @Test
    fun `alternative rock is rock, but a bare alternativa is indie`() {
        assertGenre(SoundGenre.ROCK, "Rock", "Alternative Rock", "Punk", "Hard Rock", "Blues", "Pop Rock")
        assertGenre(SoundGenre.INDIE_ALT, "Indie", "Alternativa", "Alternatif", "Alternative", "Shoegaze")
    }

    @Test
    fun `electronic and its clubs, in four languages`() {
        assertGenre(
            SoundGenre.ELECTRONIC,
            "Electro", "Electronic", "Electrónica", "Électronique", "Eletrônica",
            "Dance", "House", "Techno/House", "Drum and Bass", "EDM",
        )
    }

    @Test
    fun `chill is claimed before electronic so it keeps its calmer curve`() {
        assertGenre(SoundGenre.LOFI_CHILL, "Lo-Fi", "Lofi Hip Hop", "Chillout", "Chill House", "Ambient", "New Age")
    }

    @Test
    fun `the families that share the substring pop are claimed before pop itself`() {
        assertGenre(SoundGenre.KPOP_JPOP, "K-Pop", "J-Pop", "Anime", "Korean Pop")
        assertGenre(SoundGenre.POP, "Pop", "Synth Pop", "Variété française", "Kids", "Música infantil")
    }

    @Test
    fun `soul, funk and the rest of the r and b family`() {
        assertGenre(SoundGenre.RNB_SOUL, "R&B", "R&B/Soul", "Soul & Funk", "Funk", "Motown", "Disco", "Gospel")
    }

    @Test
    fun `the quiet families`() {
        assertGenre(SoundGenre.JAZZ, "Jazz", "Swing", "Bebop")
        assertGenre(SoundGenre.CLASSICAL, "Classical", "Clásica", "Musique classique", "Ópera", "Orchestral")
        assertGenre(SoundGenre.ACOUSTIC_FOLK, "Folk", "Acoustic", "Singer/Songwriter", "Chanson française", "Musique du monde")
        assertGenre(SoundGenre.COUNTRY, "Country", "Bluegrass")
    }

    @Test
    fun `metal, reggae, soundtracks and speech`() {
        assertGenre(SoundGenre.METAL, "Metal", "Heavy Metal", "Hardcore", "Death Metal")
        assertGenre(SoundGenre.REGGAE_DANCEHALL, "Reggae", "Dancehall/Ragga", "Dub", "Ska")
        assertGenre(SoundGenre.SOUNDTRACK, "Soundtrack", "Bandas sonoras", "Bande originale", "Films/Games", "Trilha sonora")
        assertGenre(SoundGenre.SPOKEN, "Podcasts", "Audiolibros", "Comedy", "Spoken Word")
    }

    @Test
    fun `nothing recognisable is UNKNOWN, and so is nothing at all`() {
        assertGenre(SoundGenre.UNKNOWN, "Zzyzx", "", "   ", "1234")
        assertEquals(SoundGenre.UNKNOWN, GenreClassifier.classify(null))
    }
}
