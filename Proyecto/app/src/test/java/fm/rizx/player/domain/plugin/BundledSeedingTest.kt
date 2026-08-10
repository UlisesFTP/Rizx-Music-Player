package fm.rizx.player.domain.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a plugin shipped inside the APK installs itself.
 *
 * Shipping an archive and then asking the user to go find it in a store is a step with no decision in
 * it, so it installs on first launch. The interesting cases are the two ways that can go wrong: doing
 * it again after the user deliberately removed it, and *not* doing it when the build ships a new
 * version of an archive nothing else in the app can update.
 */
class BundledSeedingTest {

    private val flac = BundledPlugin(
        assetName = "rizx-lossless.zip",
        id = "rizx-community-flac",
        name = "Community FLAC",
        version = "1.0.0",
    )

    private fun seed(
        installedVersions: Map<String, String> = emptyMap(),
        seeded: Set<String> = emptySet(),
        bundled: List<BundledPlugin> = listOf(flac),
    ) = bundledToSeed(bundled, installedVersions, seeded)

    @Test
    fun `a never-seen archive installs`() {
        assertEquals(listOf(flac), seed())
    }

    @Test
    fun `an archive already seeded and installed is left alone`() {
        assertEquals(
            emptyList<BundledPlugin>(),
            seed(installedVersions = mapOf(flac.id to "1.0.0"), seeded = setOf(flac.assetName)),
        )
    }

    @Test
    fun `an archive the user uninstalled is not resurrected`() {
        // The whole point of remembering the seed separately: uninstalling empties the installed list,
        // so without this the next launch would put it straight back and it could never be removed.
        assertEquals(emptyList<BundledPlugin>(), seed(seeded = setOf(flac.assetName)))
    }

    @Test
    fun `a build shipping a newer archive replaces what is installed`() {
        val newer = flac.copy(version = "1.1.0")
        assertEquals(
            listOf(newer),
            bundledToSeed(listOf(newer), mapOf(flac.id to "1.0.0"), setOf(flac.assetName)),
        )
    }

    @Test
    fun `an archive with no version in its manifest is not reinstalled every launch`() {
        // Blank is "unknown", not "different" — comparing it against an installed version would make
        // every start re-extract and re-transpile the plugin for nothing.
        assertEquals(
            emptyList<BundledPlugin>(),
            bundledToSeed(
                listOf(flac.copy(version = "")),
                mapOf(flac.id to "1.0.0"),
                setOf(flac.assetName),
            ),
        )
    }

    @Test
    fun `a build carrying no archives asks for nothing`() {
        assertEquals(emptyList<BundledPlugin>(), seed(bundled = emptyList()))
    }
}
