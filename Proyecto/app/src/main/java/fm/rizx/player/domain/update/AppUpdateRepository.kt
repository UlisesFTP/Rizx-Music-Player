package fm.rizx.player.domain.update

/** Where published builds come from. The one implementation reads GitHub Releases (ADR 0032). */
interface AppUpdateRepository {
    /**
     * The newest published, non-draft, non-prerelease build, or `null` when there is none — no release
     * yet, or the latest one carries no APK. Throws on network failure; the caller decides what a
     * failed lookup means.
     */
    suspend fun latest(): AppUpdate?
}
