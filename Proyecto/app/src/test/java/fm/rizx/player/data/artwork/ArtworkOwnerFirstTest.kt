package fm.rizx.player.data.artwork

import fm.rizx.player.data.provider.DefaultProviderRegistry
import fm.rizx.player.domain.model.Artwork
import fm.rizx.player.domain.model.ArtworkPurpose
import fm.rizx.player.domain.model.ArtworkSet
import fm.rizx.player.domain.model.ArtistCredit
import fm.rizx.player.domain.model.ProviderRef
import fm.rizx.player.domain.model.SearchCapability
import fm.rizx.player.domain.model.SearchParams
import fm.rizx.player.domain.model.SearchResults
import fm.rizx.player.domain.model.Track
import fm.rizx.player.domain.model.coverUrl
import fm.rizx.player.domain.provider.MetadataProvider
import fm.rizx.player.domain.provider.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cover-art resolver, pinned against the two bugs it was rewritten for: a catalogue's rank-1 hit
 * being taken unverified (so a remix's sleeve landed on the original), and the lookup being memoized
 * by a search string (so every song called "Intro" shared one stranger's cover — on disk).
 */
class ArtworkOwnerFirstTest {

    private fun art(url: String) = ArtworkSet(listOf(Artwork(url = url, purpose = ArtworkPurpose.COVER)))

    private fun track(
        title: String,
        artist: String?,
        provider: String,
        id: String,
        artwork: ArtworkSet? = null,
    ) = Track(
        title = title,
        artists = listOfNotNull(artist?.let { ArtistCredit(it) }),
        artwork = artwork,
        source = ProviderRef(provider, id),
    )

    /** A catalogue that answers searches, counts them, and can own a namespace. */
    private class FakeCatalogue(
        override val id: String,
        override val ownedNamespaces: Set<String> = setOf(id),
        private val results: List<Track> = emptyList(),
        private val byId: Map<String, Track> = emptyMap(),
    ) : MetadataProvider {
        override val kind = ProviderKind.METADATA
        override val name = id
        override val searchCapabilities = setOf(SearchCapability.UNIFIED)
        var searches = 0
        var detailCalls = 0

        override suspend fun search(params: SearchParams): SearchResults {
            searches++
            return SearchResults(tracks = results)
        }

        override suspend fun trackDetail(source: ProviderRef): Track? {
            detailCalls++
            return byId[source.id]
        }
    }

    private fun enricher(vararg providers: MetadataProvider) = TrackArtworkEnricher(
        registry = DefaultProviderRegistry().apply { providers.forEach { register(it) } },
        io = Dispatchers.Unconfined,
    )

    // ---- Rule 1: the owner answers by id, and is never second-guessed ----

    @Test
    fun `the owning catalogue is asked by id, not searched`() = runTest {
        val owner = FakeCatalogue(
            id = "applemusic-metadata",
            ownedNamespaces = setOf("applemusic"),
            byId = mapOf("999" to track("Real Title", "Real Artist", "applemusic", "999", art("https://own/cover.jpg"))),
        )
        val other = FakeCatalogue("deezer", results = listOf(track("Anything", "Anyone", "deezer", "1", art("https://other/x.jpg"))))

        val out = enricher(owner, other).enrich(listOf(track("Been By Now", "Morgan Wallen", "applemusic", "999")))

        assertEquals("https://own/cover.jpg", out.single().artwork.coverUrl())
        assertEquals(1, owner.detailCalls)
        assertEquals(0, other.searches) // the owner answered; nobody else was asked
    }

    @Test
    fun `dispatch is by owned namespace, not by registry id`() = runTest {
        // The bug this pins: `applemusic-metadata` != the `applemusic` its refs carry, so looking the
        // owner up by source.provider finds nothing and the owner path is dead code.
        val owner = FakeCatalogue(
            id = "applemusic-metadata",
            ownedNamespaces = setOf("applemusic"),
            byId = mapOf("42" to track("T", "A", "applemusic", "42", art("https://own/42.jpg"))),
        )

        val out = enricher(owner).enrich(listOf(track("T", "A", "applemusic", "42")))

        assertEquals(1, owner.detailCalls)
        assertEquals("https://own/42.jpg", out.single().artwork.coverUrl())
    }

    // ---- Rule 2: borrowing is verified ----

    @Test
    fun `a remix is refused as a cover donor — the reported bug`() = runTest {
        // Verified live: Deezer's rank-1 for "Surf7 GOOSE COAT" is the Meek Mill remix.
        val deezer = FakeCatalogue(
            "deezer",
            results = listOf(track("GOOSE COAT (Remix) [feat. Meek Mill]", "Surf7", "deezer", "1", art("https://deezer/remix.jpg"))),
        )

        val out = enricher(deezer).enrich(listOf(track("GOOSE COAT", "Surf7", "soundcloud", "https://sc/goose")))

        assertNull(out.single().artwork.coverUrl())
    }

    @Test
    fun `a different artist is refused even when the title matches exactly`() = runTest {
        val deezer = FakeCatalogue(
            "deezer",
            results = listOf(track("Intro", "Josman", "deezer", "1", art("https://deezer/josman.jpg"))),
        )

        val out = enricher(deezer).enrich(listOf(track("Intro", "Some Other Band", "spotify", "s1")))

        assertNull(out.single().artwork.coverUrl())
    }

    @Test
    fun `a track with no artist credit is never searched — a bare title matches strangers`() = runTest {
        val deezer = FakeCatalogue("deezer", results = listOf(track("Intro", "Josman", "deezer", "1", art("https://deezer/josman.jpg"))))

        val out = enricher(deezer).enrich(listOf(track("Intro", artist = null, provider = "import", id = "hash1")))

        assertNull(out.single().artwork.coverUrl())
        assertEquals(0, deezer.searches)
    }

    @Test
    fun `a verified match does lend its cover, decoration and all`() = runTest {
        val deezer = FakeCatalogue("deezer", results = listOf(track("La Diabla", "Xavi", "deezer", "1", art("https://deezer/diabla.jpg"))))

        val out = enricher(deezer).enrich(
            listOf(track("Xavi - La Diabla (Official Video)", "Xavi Oficial", "youtube", "vid1")),
            upgradeFrom = setOf("youtube"),
        )

        assertEquals("https://deezer/diabla.jpg", out.single().artwork.coverUrl())
    }

    @Test
    fun `the right release is taken from further down the results, not just rank 1`() = runTest {
        val deezer = FakeCatalogue(
            "deezer",
            results = listOf(
                track("Lithium (Live)", "Nirvana", "deezer", "1", art("https://deezer/live.jpg")),
                track("Lithium", "Nirvana", "deezer", "2", art("https://deezer/nevermind.jpg")),
            ),
        )

        val out = enricher(deezer).enrich(listOf(track("Lithium", "Nirvana", "spotify", "s1")))

        assertEquals("https://deezer/nevermind.jpg", out.single().artwork.coverUrl())
    }

    // ---- Cost: verification must not turn into a fan-out ----

    @Test
    fun `the borrow ladder is capped so a rejection cannot walk every catalogue`() = runTest {
        val a = FakeCatalogue("deezer")
        val b = FakeCatalogue("itunes-metadata", ownedNamespaces = setOf("itunes"))
        val c = FakeCatalogue("applemusic-metadata", ownedNamespaces = setOf("applemusic"))
        val d = FakeCatalogue("soundcloud-metadata", ownedNamespaces = setOf("soundcloud-x"))

        enricher(a, b, c, d).enrich(listOf(track("Nothing Matches", "Nobody", "spotify", "s1")))

        assertEquals(2, listOf(a, b, c, d).count { it.searches > 0 })
    }

    // ---- Repair: a borrowed cover that no longer verifies is withdrawn ----

    @Test
    fun `repair withdraws a borrowed cover that can no longer be justified`() = runTest {
        val stale = ArtworkSet(
            listOf(Artwork(url = "https://deezer/wrong.jpg", purpose = ArtworkPurpose.COVER, source = ProviderRef("deezer", "9"))),
        )
        val deezer = FakeCatalogue("deezer") // matches nothing now

        val out = enricher(deezer).enrich(
            listOf(track("GOOSE COAT", "Surf7", "spotify", "s1", stale)),
            repairBorrowed = true,
        )

        assertNull(out.single().artwork.coverUrl())
    }

    @Test
    fun `repair leaves a track's own artwork alone — it is honest, not borrowed`() = runTest {
        val own = ArtworkSet(
            listOf(Artwork(url = "https://sc/own.jpg", purpose = ArtworkPurpose.COVER, source = ProviderRef("soundcloud", "t1"))),
        )
        val deezer = FakeCatalogue("deezer")

        val out = enricher(deezer).enrich(
            listOf(track("Song", "Artist", "soundcloud", "t1", own)),
            repairBorrowed = true,
        )

        assertEquals("https://sc/own.jpg", out.single().artwork.coverUrl())
    }

    @Test
    fun `a track that already has its own cover costs nothing`() = runTest {
        val deezer = FakeCatalogue("deezer")

        val out = enricher(deezer).enrich(listOf(track("Song", "Artist", "deezer", "1", art("https://deezer/own.jpg"))))

        assertEquals("https://deezer/own.jpg", out.single().artwork.coverUrl())
        assertEquals(0, deezer.searches)
    }
}
