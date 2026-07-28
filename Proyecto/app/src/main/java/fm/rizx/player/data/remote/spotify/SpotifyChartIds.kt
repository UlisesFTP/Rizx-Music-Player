package fm.rizx.player.data.remote.spotify

/**
 * Spotify's own chart playlists ("Top 50", "Viral 50") — stable, editorially-maintained ids fetched
 * through the same keyless embed page as any playlist import. Countries without an entry fall back
 * to the Global chart (same for no consent / no country). The map is curated, not exhaustive: it
 * covers the app's locales and the biggest storefronts; a wrong/retired id degrades to the Global
 * fallback through the provider's error isolation.
 */
object SpotifyChartIds {
    const val TOP_50_GLOBAL = "37i9dQZEVXbMDoHDwVN2tF"
    const val VIRAL_50_GLOBAL = "37i9dQZEVXbLiRSasKsNU9"

    private val TOP_50_BY_COUNTRY = mapOf(
        "mx" to "37i9dQZEVXbO3qyFxbkOE1",
        "us" to "37i9dQZEVXbLRQDuF5jeBp",
        "es" to "37i9dQZEVXbNFJfN1Vw8d9",
        "ar" to "37i9dQZEVXbMMy2roB9myp",
        "co" to "37i9dQZEVXbOa2lmxNORXQ",
        "cl" to "37i9dQZEVXbL0GavIqMTeb",
        "pe" to "37i9dQZEVXbJfdy5b0KP7W",
        "br" to "37i9dQZEVXbMXbN3EUUhlg",
        "pt" to "37i9dQZEVXbKyJS56d1pgi",
        "fr" to "37i9dQZEVXbIPWwFssbupI",
        "de" to "37i9dQZEVXbJiZcmkrIHGU",
        "gb" to "37i9dQZEVXbLnolsZ8PSNw",
        "it" to "37i9dQZEVXbIQnj7RRhdSX",
        "ca" to "37i9dQZEVXbKj23U1GF4IR",
        "au" to "37i9dQZEVXbJPcfkRz0wJ0",
        "jp" to "37i9dQZEVXbKXQ4mDTEBXq",
    )

    /** The country's Top 50 playlist id, or the Global chart when unknown/unmapped/no consent. */
    fun top50(country: String?): String =
        country?.lowercase()?.let { TOP_50_BY_COUNTRY[it] } ?: TOP_50_GLOBAL
}
