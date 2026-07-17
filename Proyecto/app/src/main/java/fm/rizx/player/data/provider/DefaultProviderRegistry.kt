package fm.rizx.player.data.provider

import fm.rizx.player.domain.provider.ActiveProviderStore
import fm.rizx.player.domain.provider.ProviderDescriptor
import fm.rizx.player.domain.provider.ProviderKind
import fm.rizx.player.domain.provider.ProviderRegistry

/**
 * Default [ProviderRegistry]: two insertion-ordered indices (by id, and by kind) plus an injected
 * [ActiveProviderStore]. Activation is first-wins; unregistering the active provider falls back to
 * the first remaining provider of that kind, or clears the selection. Not thread-safe — intended to
 * be touched from a single (main) thread, matching the upstream host.
 */
class DefaultProviderRegistry(
    private val activeStore: ActiveProviderStore = InMemoryActiveProviderStore(),
) : ProviderRegistry {

    private val byId = LinkedHashMap<String, ProviderDescriptor>()
    private val byKind = mutableMapOf<ProviderKind, LinkedHashMap<String, ProviderDescriptor>>()

    override fun register(descriptor: ProviderDescriptor): String {
        byId[descriptor.id] = descriptor
        byKind.getOrPut(descriptor.kind) { LinkedHashMap() }[descriptor.id] = descriptor
        // First-wins: only auto-activate when nothing is active for this kind yet.
        if (activeStore.getActive(descriptor.kind) == null) {
            activeStore.setActive(descriptor.kind, descriptor.id)
        }
        return descriptor.id
    }

    override fun unregister(providerId: String): Boolean {
        val removed = byId.remove(providerId) ?: return false
        byKind[removed.kind]?.remove(providerId)
        if (activeStore.getActive(removed.kind) == providerId) {
            val fallback = byKind[removed.kind]?.values?.firstOrNull()?.id
            activeStore.setActive(removed.kind, fallback) // null clears the selection
        }
        return true
    }

    override fun list(kind: ProviderKind?): List<ProviderDescriptor> =
        if (kind == null) byId.values.toList() else byKind[kind]?.values?.toList().orEmpty()

    override fun get(providerId: String?, kind: ProviderKind): ProviderDescriptor? {
        val descriptor = providerId?.let { byId[it] } ?: return null
        return descriptor.takeIf { it.kind == kind }
    }

    override fun getActive(kind: ProviderKind): String? = activeStore.getActive(kind)

    override fun setActive(kind: ProviderKind, providerId: String) {
        val descriptor = byId[providerId]
        require(descriptor != null && descriptor.kind == kind) {
            "No $kind provider registered with id '$providerId'"
        }
        activeStore.setActive(kind, providerId)
    }

    override fun activeDescriptor(kind: ProviderKind): ProviderDescriptor? =
        getActive(kind)?.let { byId[it] }

    override fun clear() {
        byId.clear()
        byKind.clear()
        ProviderKind.entries.forEach { activeStore.setActive(it, null) }
    }
}
