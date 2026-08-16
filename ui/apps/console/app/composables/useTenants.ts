import { ApusApiError } from '#core/utils/apiErrors'
import type { TenantResponse } from '#core/utils/apiTypes'
import { describeStorageUsage, type StorageUsageSummary } from '#core/utils/storageUsage'

export interface TenantRow extends TenantResponse {
  id: string
  usage: StorageUsageSummary
}

/**
 * Every tenant, with its storage usage already summarised.
 *
 * `describeStorageUsage` is the module that knows what "82% of quota" means, including the case
 * where the quota string cannot be parsed at all -- reimplementing any of that here would be a
 * second opinion on a question that already has a tested answer.
 */
export function useTenants() {
  const api = useApiClient()
  const tenants = ref<TenantRow[]>([])
  const loading = ref(true)
  const error = ref<ApusApiError | null>(null)

  async function refresh(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const list = await api.listTenants()
      tenants.value = list
        .map(tenant => ({
          ...tenant,
          id: tenant.name,
          usage: describeStorageUsage(tenant.storageUsedBytes, tenant.storage.quota)
        }))
        .sort((a, b) => a.name.localeCompare(b.name))
    } catch (caught) {
      error.value = caught instanceof ApusApiError
        ? caught
        : new ApusApiError({ status: 0, message: 'Could not load tenants.' })
    } finally {
      loading.value = false
    }
  }

  onMounted(refresh)

  return { tenants, loading, error, refresh }
}
