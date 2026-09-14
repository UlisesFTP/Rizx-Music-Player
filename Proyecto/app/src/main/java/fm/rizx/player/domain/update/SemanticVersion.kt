package fm.rizx.player.domain.update

/**
 * The part of semantic versioning the update check needs: `MAJOR.MINOR.PATCH` with an optional
 * pre-release tail, read from a Gradle `versionName` or a release tag (`v1.2.0`, `1.2`, `2.0.0-rc.1`).
 *
 * Ordering follows SemVer 2.0: numbers compare numerically, a pre-release sorts *before* the same
 * numbers without one (`1.2.0-rc.1 < 1.2.0`), and pre-release identifiers compare left to right,
 * numerically when both are numbers. Build metadata (`+…`) is ignored. Missing minor/patch read as 0,
 * so `1.2` is `1.2.0`.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        // A release beats its own pre-releases.
        if (preRelease.isEmpty() != other.preRelease.isEmpty()) return if (preRelease.isEmpty()) 1 else -1
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val a = preRelease[i]
            val b = other.preRelease[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers sort before alphanumeric ones
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private val NUMBERS = Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?$""")

        /** `null` when [raw] is not a version at all (a tag like `latest`, an empty string). */
        fun parse(raw: String): SemanticVersion? {
            var s = raw.trim()
            if (s.startsWith("v", ignoreCase = true)) s = s.substring(1)
            s = s.substringBefore('+')
            val pre = s.substringAfter('-', missingDelimiterValue = "")
            val core = s.substringBefore('-')
            val m = NUMBERS.matchEntire(core) ?: return null
            val (major, minor, patch) = m.destructured
            return SemanticVersion(
                major = major.toIntOrNull() ?: return null,
                minor = minor.toIntOrNull() ?: 0,
                patch = patch.toIntOrNull() ?: 0,
                preRelease = if (pre.isEmpty()) emptyList() else pre.split('.').filter { it.isNotEmpty() },
            )
        }

        /** True when [candidate] parses and is strictly newer than [installed]. Unparseable input is never "newer". */
        fun isNewer(candidate: String, installed: String): Boolean {
            val c = parse(candidate) ?: return false
            val i = parse(installed) ?: return false
            return c > i
        }
    }
}
