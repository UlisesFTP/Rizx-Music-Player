package fm.rizx.player.data.provider

import fm.rizx.player.domain.provider.ActiveProviderStore
import fm.rizx.player.domain.provider.ProviderKind

/**
 * In-memory [ActiveProviderStore] for development and tests. A persistent (DataStore-backed)
 * implementation arrives in Phase 10.
 */
class InMemoryActiveProviderStore : ActiveProviderStore {

    private val active = mutableMapOf<ProviderKind, String>()

    override fun getActive(kind: ProviderKind): String? = active[kind]

    override fun setActive(kind: ProviderKind, providerId: String?) {
        if (providerId == null) active.remove(kind) else active[kind] = providerId
    }

    override fun snapshot(): Map<ProviderKind, String> = active.toMap()
}
