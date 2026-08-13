package fm.rizx.player.data.plugin.install

import fm.rizx.player.core.error.AppError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The installer's layout handling, because getting it wrong is invisible: a mis-stripped path yields
 * "module not found" at load time, which reads as "this plugin is broken" rather than "we corrupted
 * its layout". Every fixture here mirrors a real registry release shape.
 */
class PluginInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun installer() = PluginInstaller(OkHttpClient(), json, tmp.newFolder("plugins"))

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, body) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun pkg(main: String = "src/index.ts", version: String = "1.2.3") =
        """{"name":"acme-plugin","version":"$version","author":"acme","main":"$main","nuclear":{"category":"metadata"}}"""

    @Test
    fun `a flat plugin_zip keeps its paths — the release shape the registry actually ships`() = runBlocking {
        val zip = zipOf(
            "package.json" to pkg(),
            "src/index.ts" to "export default {}",
            "src/lib/client.ts" to "export const x = 1",
        )
        val extracted = installer().installFromZip(zip.inputStream())

        assertEquals("src/index", extracted.entryPath)
        assertNotNull(extracted.sources["src/index"])
        // A nested module keeps its full path: collapsing it would break relative requires.
        assertNotNull(extracted.sources["src/lib/client"])
        assertEquals("1.2.3", extracted.manifest.version)
    }

    @Test
    fun `a GitHub zipball has its wrapper directory stripped`() = runBlocking {
        val zip = zipOf(
            "acme-plugin-9f2c1a/package.json" to pkg(),
            "acme-plugin-9f2c1a/src/index.ts" to "export default {}",
        )
        val extracted = installer().installFromZip(zip.inputStream())

        assertEquals("src/index", extracted.entryPath)
        assertNotNull(extracted.sources["src/index"])
    }

    @Test
    fun `a bundled release is honoured verbatim, dist and all`() = runBlocking {
        val zip = zipOf(
            "package.json" to pkg(main = "dist/index.js"),
            "dist/index.js" to "module.exports = {}",
        )
        val extracted = installer().installFromZip(zip.inputStream())

        assertEquals("dist/index", extracted.entryPath)
        assertEquals(PluginSourceKind.JS, extracted.sources["dist/index"]?.kind)
    }

    @Test
    fun `tsx and json travel with the sources`() = runBlocking {
        val zip = zipOf(
            "package.json" to pkg(),
            "src/index.ts" to "export default {}",
            "src/Widget.tsx" to "export const W = () => null",
            "src/genres.json" to """{"pop":1}""",
        )
        val extracted = installer().installFromZip(zip.inputStream())

        assertEquals(PluginSourceKind.TSX, extracted.sources["src/Widget"]?.kind)
        assertEquals(PluginSourceKind.JSON, extracted.sources["src/genres"]?.kind)
    }

    @Test
    fun `a missing entry fails the install instead of loading a random file`() {
        val zip = zipOf(
            "package.json" to pkg(main = "src/index.ts"),
            "src/other.ts" to "export default {}",
        )
        val error = runCatching { runBlocking { installer().installFromZip(zip.inputStream()) } }.exceptionOrNull()

        assertTrue(error is AppError.ProviderFailure)
        assertTrue(error!!.message!!.contains("entry"))
    }

    @Test
    fun `an unbundled npm dependency fails with the specifier that caused it`() {
        val zip = zipOf(
            "package.json" to pkg(),
            "src/index.ts" to "import axios from 'axios'\nexport default {}",
        )
        val error = runCatching { runBlocking { installer().installFromZip(zip.inputStream()) } }.exceptionOrNull()

        assertTrue(error is AppError.ProviderFailure)
        assertTrue(error!!.message!!.contains("axios"))
    }

    @Test
    fun `the type-only SDK import and the UI stubs are not treated as unbundled deps`() = runBlocking {
        val zip = zipOf(
            "package.json" to pkg(),
            "src/index.ts" to """
                import type { NuclearPlugin } from '@nuclearplayer/plugin-sdk'
                import React from 'react'
                export default {}
            """.trimIndent(),
        )
        // Resolves rather than throwing: the runtime supplies both specifiers itself.
        assertNotNull(installer().installFromZip(zip.inputStream()).sources["src/index"])
    }

    @Test
    fun `a zip-slip entry is refused`() {
        val zip = zipOf(
            "package.json" to pkg(),
            "../../evil.ts" to "export default {}",
        )
        val error = runCatching { runBlocking { installer().installFromZip(zip.inputStream()) } }.exceptionOrNull()

        assertTrue(error is AppError.ProviderFailure)
    }

    @Test
    fun `reinstalling preserves the plugin's settings and storage`() = runBlocking {
        val installer = installer()
        val zip = zipOf("package.json" to pkg(), "src/index.ts" to "export default {}")
        val first = installer.installFromZip(zip.inputStream())
        java.io.File(first.dir, PluginInstaller.SETTINGS_FILE).writeText("""{"token":"abc"}""")

        val updated = installer.installFromZip(
            zipOf("package.json" to pkg(version = "2.0.0"), "src/index.ts" to "export default {}").inputStream(),
        )

        assertEquals("2.0.0", updated.manifest.version)
        assertEquals("""{"token":"abc"}""", java.io.File(updated.dir, PluginInstaller.SETTINGS_FILE).readText())
    }

    @Test
    fun `the id an archive installs under is derivable from its name alone`() {
        // The store compares a manifest name against the installed directory list to decide whether to
        // draw "Install" or "Installed", so the two have to agree before anything is installed.
        assertEquals("rizx-community-flac", PluginInstaller.pluginIdFor("rizx-community-flac"))
        assertEquals("rizx-lossless", PluginInstaller.pluginIdFor("Rizx Lossless"))
        assertEquals("rizx-lossless", PluginInstaller.pluginIdFor("@rizx/lossless"))
        assertEquals("nuclear-plugin-discogs", PluginInstaller.pluginIdFor("nuclear-plugin-discogs"))
    }

    @Test
    fun `a manifest named for the parent directory cannot be installed`() = runBlocking {
        // `File(pluginsRoot, "..")` is the app's whole files directory, and the installer deletes the
        // directory it is about to extract into. This archive used to take the database, the downloads
        // and every other plugin with it — from a pasted URL.
        val zip = zipOf(
            "package.json" to """{"name":"..","version":"1.0.0","main":"src/index.ts"}""",
            "src/index.ts" to "export default {}",
        )
        val root = tmp.newFolder("plugins")
        val sibling = java.io.File(root.parentFile, "precious.db").apply { writeText("data") }

        val failure = runCatching { PluginInstaller(OkHttpClient(), json, root).installFromZip(zip.inputStream()) }

        assertTrue(failure.exceptionOrNull() is AppError.ProviderFailure)
        assertTrue("the parent directory must be untouched", sibling.exists())
    }

    @Test
    fun `a zip entry escaping into a sibling plugin is refused`() = runBlocking {
        // The guard compared canonical paths with `startsWith` and no separator, so `…/plugins/acme`
        // was a prefix of `…/plugins/acme-victim` and this entry landed in the victim's source tree —
        // arbitrary code injected into a plugin the user already trusts.
        val zip = zipOf(
            "package.json" to pkg(),
            "src/index.ts" to "export default {}",
            "../acme-plugin-victim/src/index.ts" to "module.exports = { evil: true }",
        )

        val failure = runCatching { installer().installFromZip(zip.inputStream()) }

        assertTrue(failure.exceptionOrNull() is AppError.ProviderFailure)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("unsafe zip entry"))
    }

    @Test
    fun `a different plugin reusing an id does not inherit the stored credentials`() = runBlocking {
        val installer = installer()
        installer.installFromZip(
            zipOf("package.json" to """{"name":"Acme Plugin","version":"1.0.0","main":"src/index.ts"}""",
                "src/index.ts" to "export default {}").inputStream(),
        ).also { java.io.File(it.dir, PluginInstaller.SETTINGS_FILE).writeText("""{"token":"secret"}""") }

        // Normalizes to the same id, `acme-plugin`, but it is a different plugin. Carrying the settings
        // across would hand it the first one's API token with nothing exploited at all.
        val impostor = installer.installFromZip(
            zipOf("package.json" to """{"name":"acme/plugin","version":"1.0.0","main":"src/index.ts"}""",
                "src/index.ts" to "export default {}").inputStream(),
        )

        assertEquals("acme-plugin", impostor.dir.name)
        assertTrue(!java.io.File(impostor.dir, PluginInstaller.SETTINGS_FILE).exists())
    }

    @Test
    fun `a plugin written for a newer host is refused, and one that says nothing installs`() = runBlocking {
        val installer = installer()
        val tooNew = runCatching {
            installer.installFromZip(
                zipOf(
                    "package.json" to """{"name":"acme-plugin","version":"1.0.0","main":"src/index.ts","rizx":{"apiVersion":99}}""",
                    "src/index.ts" to "export default {}",
                ).inputStream(),
            )
        }
        assertTrue(tooNew.exceptionOrNull() is AppError.ProviderFailure)
        assertTrue(tooNew.exceptionOrNull()!!.message!!.contains("v99"))

        // Every Nuclear plugin declares nothing, and must keep working exactly as before.
        val legacy = installer.installFromZip(
            zipOf("package.json" to pkg(), "src/index.ts" to "export default {}").inputStream(),
        )
        assertNull(legacy.manifest.apiVersion)
    }

    @Test
    fun `entry resolution tolerates the legacy src-stripped key`() {
        val sources = mapOf("src/index" to PluginSourceFile("", PluginSourceKind.TS))

        assertEquals("src/index", PluginInstaller.resolveEntry(sources, "index"))
        assertEquals("src/index", PluginInstaller.resolveEntry(sources, "src/index"))
        assertNull(PluginInstaller.resolveEntry(sources, "nope"))
    }

    @Test
    fun `an archive with too many entries is refused`() {
        // Directory entries: they count against the cap but write nothing, so the test stays cheap while
        // proving the file/inode-exhaustion bomb is bounded before the install materializes it.
        val entries = buildList {
            add("package.json" to pkg())
            repeat(PluginInstaller.MAX_ENTRY_COUNT + 2) { add("d$it/" to "") }
        }.toTypedArray()

        val failure = runCatching { runBlocking { installer().installFromZip(zipOf(*entries).inputStream()) } }

        assertTrue(failure.exceptionOrNull() is AppError.ProviderFailure)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("too many entries"))
    }

    @Test
    fun `settings are not inherited when the previous manifest is unreadable`() = runBlocking {
        val installer = installer()
        val first = installer.installFromZip(
            zipOf("package.json" to pkg(), "src/index.ts" to "export default {}").inputStream(),
        )
        java.io.File(first.dir, PluginInstaller.SETTINGS_FILE).writeText("""{"token":"abc"}""")
        // Corrupted prior install: keep the credential file but lose the manifest, so previousName is null.
        // A null previous name must NOT be treated as "same plugin" — the kept settings are dropped.
        java.io.File(first.dir, "package.json").delete()

        val again = installer.installFromZip(
            zipOf("package.json" to pkg(), "src/index.ts" to "export default {}").inputStream(),
        )

        assertEquals("acme-plugin", again.dir.name)
        assertTrue(!java.io.File(again.dir, PluginInstaller.SETTINGS_FILE).exists())
    }

    // ---- archive integrity (SHA-256 verify-when-present + trust-on-first-use record) ----

    private fun hexSha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `the archive's sha256 is recorded on install`() = runBlocking {
        val zip = zipOf("package.json" to pkg(), "src/index.ts" to "export default {}")

        val extracted = installer().installFromZip(zip.inputStream())

        assertEquals(hexSha256(zip), extracted.sha256)
    }

    @Test
    fun `a matching expected checksum installs and records it`() = runBlocking {
        val zip = zipOf("package.json" to pkg(), "src/index.ts" to "export default {}")

        val extracted = installer().installFromZipBytes("acme-plugin", zip, origin = "registry", expectedSha256 = hexSha256(zip))

        assertEquals("acme-plugin", extracted.dir.name)
        assertEquals(hexSha256(zip), extracted.sha256)
    }

    @Test
    fun `a checksum mismatch is refused without destroying the previous install`() = runBlocking {
        val installer = installer()
        val good = installer.installFromZip(
            zipOf("package.json" to pkg(), "src/index.ts" to "export default {}").inputStream(),
        )
        java.io.File(good.dir, PluginInstaller.SETTINGS_FILE).writeText("""{"token":"abc"}""")

        val other = zipOf("package.json" to pkg(version = "2.0.0"), "src/index.ts" to "export default {}")
        val failure = runCatching {
            installer.installFromZipBytes(good.dir.name, other, origin = "registry", expectedSha256 = "deadbeef")
        }

        assertTrue(failure.exceptionOrNull() is AppError.ProviderFailure)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("checksum"))
        // The verify runs before anything is deleted, so the previous good install and its token survive.
        assertEquals("""{"token":"abc"}""", java.io.File(good.dir, PluginInstaller.SETTINGS_FILE).readText())
    }
}
