import type { PolicyEntryResponse } from '#core/utils/apiTypes'
import { allowedSourceTypes, forceAllowed, maximumKeepVersions, minimumPollSeconds } from '#core/utils/policy'

/**
 * This tenant's options, as the four questions the interface actually asks.
 *
 * Convenience only, exactly like `role.ts`: the api module refuses a request that breaks a locked
 * option regardless of what this renders. What it buys is that a tenant meets a rule while
 * choosing rather than after submitting — which is the difference between a form that guides and
 * one that rejects.
 *
 * A failed read is not an error the page shows. The endpoint is a convenience, so losing it means
 * falling back to the pre-policy behaviour: offer everything, and let the API refuse what it must.
 * Blocking the source flow because a policy could not be read would be the worse failure.
 */
export function useTenantPolicy() {
  const api = useApiClient()
  const entries = ref<PolicyEntryResponse[]>([])
  const loaded = ref(false)

  onMounted(async () => {
    try {
      entries.value = await api.getTenantPolicy()
    } catch {
      entries.value = []
    } finally {
      loaded.value = true
    }
  })

  return {
    entries,
    loaded,
    /** The source types this tenant may create, or `null` when unregulated. */
    sourceTypes: computed(() => allowedSourceTypes(entries.value)),
    /** The shortest permitted poll interval in seconds, or `null` when unregulated. */
    pollMinimumSeconds: computed(() => minimumPollSeconds(entries.value)),
    /** The most snapshots this tenant may keep, or `null` when unregulated. */
    keepVersionsMaximum: computed(() => maximumKeepVersions(entries.value)),
    /** Whether forced renders are permitted, or `null` when unregulated. */
    forceRenderAllowed: computed(() => forceAllowed(entries.value))
  }
}
