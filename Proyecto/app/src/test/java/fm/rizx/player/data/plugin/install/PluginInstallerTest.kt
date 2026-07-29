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
    fun `entry resolution tolerates the legacy src-stripped key`() {
        val sources = mapOf("src/index" to PluginSourceFile("", PluginSourceKind.TS))

        assertEquals("src/index", PluginInstaller.resolveEntry(sources, "index"))
        assertEquals("src/index", PluginInstaller.resolveEntry(sources, "src/index"))
        assertNull(PluginInstaller.resolveEntry(sources, "nope"))
    }
}
