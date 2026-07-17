package fm.rizx.player.domain.provider

/**
 * Registry of providers, keyed by id and grouped by [ProviderKind], with a single active provider
 * per kind. Activation is **first-wins**: registering the first provider for a kind makes it active;
 * later registrations do not steal the active slot. Unregistering the active provider falls back to
 * another registered provider of that kind, or clears the selection if none remain.
 *
 * Mirrors the single-active dispatch of upstream's `ProvidersHost` (NUCLEAR_UPSTREAM_STUDY.md §4).
 * All-active fan-out (dashboard) and url-matched dispatch (playlists) are out of scope for the MVP.
 */
interface ProviderRegistry {

    /** Registers [descriptor]; activates it if its kind has no active provider yet. Returns its id. */
    fun register(descriptor: ProviderDescriptor): String

    /** Removes a provider by id. If it was active, falls back to another of its kind (or clears). */
    fun unregister(providerId: String): Boolean

    /** All registered providers, or just those of [kind] when given. */
    fun list(kind: ProviderKind? = null): List<ProviderDescriptor>

    /** The descriptor for [providerId], but only if it exists **and** matches [kind]; else null. */
    fun get(providerId: String?, kind: ProviderKind): ProviderDescriptor?

    /** The active provider id for [kind], or null. */
    fun getActive(kind: ProviderKind): String?

    /** Selects the active provider for [kind]. Throws if [providerId] is not registered for [kind]. */
    fun setActive(kind: ProviderKind, providerId: String)

    /** Convenience: the active provider's descriptor for [kind], or null. */
    fun activeDescriptor(kind: ProviderKind): ProviderDescriptor?

    /** Removes all providers and clears all active selections. */
    fun clear()
}
