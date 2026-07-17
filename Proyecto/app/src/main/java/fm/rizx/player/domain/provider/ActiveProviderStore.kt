package fm.rizx.player.domain.provider

/**
 * Holds which provider id is currently active per [ProviderKind]. Kept as a separate abstraction
 * so the active selection can later be persisted (DataStore, Phase 10) without touching the
 * registry. Passing `null` to [setActive] clears the selection for that kind.
 */
interface ActiveProviderStore {
    fun getActive(kind: ProviderKind): String?
    fun setActive(kind: ProviderKind, providerId: String?)
    fun snapshot(): Map<ProviderKind, String>
}
