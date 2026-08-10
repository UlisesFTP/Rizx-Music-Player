package fm.rizx.player.domain.plugin

/**
 * Which bundled archives this launch should install.
 *
 * A pure function because the rule is the whole feature and the code around it is not testable on the
 * JVM: the repository reaches an Android `DataStore` and a QuickJS runtime, while what matters here is
 * one decision made from three lists.
 *
 * @param bundled archives shipped in this APK
 * @param installedVersions installed plugin id → its version
 * @param seeded asset names already auto-installed at some point, on any earlier build
 */
fun bundledToSeed(
    bundled: List<BundledPlugin>,
    installedVersions: Map<String, String>,
    seeded: Set<String>,
): List<BundledPlugin> = bundled.filter { entry ->
    val installedVersion = installedVersions[entry.id]
    when {
        // Never seen here: the first launch of a build that carries it.
        entry.assetName !in seeded -> true
        // Seeded and still installed, but this build ships a different version. The archive wins —
        // a bundled plugin has no public URL, so nothing else in the app can ever update it.
        installedVersion != null && entry.version.isNotBlank() && installedVersion != entry.version -> true
        // Seeded and no longer installed: the user removed it. Putting it back every launch would
        // make it impossible to be rid of, which is the obvious way for auto-install to go wrong.
        else -> false
    }
}
