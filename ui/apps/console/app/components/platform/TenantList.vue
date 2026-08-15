<script setup lang="ts">
// Tenant list for the platform dashboard (design spec §11.2: "Mandanten, Quotas mit
// Verbrauchsanzeige ... Domain-Freigaben"). Storage usage is deliberately presented as an
// observation, not a control -- see storageUsage.ts's module Javadoc: Ceph enforces the quota,
// not Apus. Domains are shown with an explanation of what they gate, so nobody mistakes the
// list for a harmless label -- see domainValidation.ts's module Javadoc.
import type { TenantResponse } from '#core/utils/apiTypes'
import { describeStorageUsage, storageUsageColor, type StorageUsageSummary } from '#core/utils/storageUsage'

const props = defineProps<{
  tenants: TenantResponse[]
  loading: boolean
}>()

const emit = defineEmits<{
  updated: []
}>()

/** Name of the tenant currently showing its edit form, or `null` if none is open. Only one at a
 * time -- keeps the list from turning into a wall of open forms. */
const editingTenant = ref<string | null>(null)

function onUpdated(): void {
  editingTenant.value = null
  emit('updated')
}

interface TenantRow {
  tenant: TenantResponse
  usage: StorageUsageSummary
  /** `UProgress`'s `model-value` wants a plain 0-100 number, capped at 100 even when usage is
   * observed beyond quota ('over') -- the label below still spells that case out in words. */
  progressValue: number | null
}

const rows = computed<TenantRow[]>(() =>
  props.tenants.map((tenant) => {
    const usage = describeStorageUsage(tenant.storageUsedBytes, tenant.storage.quota)
    return {
      tenant,
      usage,
      progressValue: usage.ratio === null ? null : Math.min(usage.ratio * 100, 100)
    }
  })
)

const usageLevelText: Record<StorageUsageSummary['level'], string> = {
  unknown: 'Usage unknown',
  ok: 'Within quota',
  warning: 'Approaching quota',
  critical: 'Near quota',
  over: 'At or over quota -- uploads will start failing'
}
</script>

<template>
  <section aria-labelledby="tenant-list-heading" class="space-y-4">
    <h2 id="tenant-list-heading" class="text-lg font-medium">
      Tenants
    </h2>

    <p v-if="loading" class="text-muted text-sm">
      Loading tenants…
    </p>
    <p v-else-if="rows.length === 0" class="text-muted text-sm">
      No tenants yet. Create the first one below.
    </p>

    <ul v-else class="space-y-4">
      <li v-for="row in rows" :key="row.tenant.name">
        <UCard>
          <template #header>
            <div class="flex flex-wrap items-baseline justify-between gap-2">
              <h3 class="font-medium">
                {{ row.tenant.displayName || row.tenant.name }}
              </h3>
              <div class="flex items-center gap-2">
                <span class="text-muted text-xs font-mono">{{ row.tenant.name }}</span>
                <UButton
                  size="xs"
                  variant="ghost"
                  color="neutral"
                  :icon="editingTenant === row.tenant.name ? 'i-lucide-x' : 'i-lucide-pencil'"
                  @click="editingTenant = editingTenant === row.tenant.name ? null : row.tenant.name"
                >
                  {{ editingTenant === row.tenant.name ? 'Close' : 'Edit quota / domains' }}
                </UButton>
              </div>
            </div>
          </template>

          <div class="space-y-4 text-sm">
            <div>
              <div class="flex items-center justify-between gap-2">
                <span class="text-muted">Storage usage (observed, not enforced by Apus)</span>
                <UBadge :color="storageUsageColor(row.usage.level)" variant="subtle">
                  {{ usageLevelText[row.usage.level] }}
                </UBadge>
              </div>
              <UProgress
                class="mt-2"
                :model-value="row.progressValue"
                :color="storageUsageColor(row.usage.level)"
                :aria-label="`Storage usage for ${row.tenant.displayName || row.tenant.name}`"
              />
              <p class="text-muted mt-1 text-xs">
                {{ row.usage.usedLabel }} of {{ row.usage.quotaLabel }} -- the limit itself is enforced by the
                storage backend (Ceph), not by this dashboard.
              </p>
            </div>

            <div>
              <span class="text-muted">Allowed hosting domains</span>
              <p class="text-muted mt-1 text-xs">
                Controls which hostnames this tenant's hosted maps may use. Kept narrow on purpose --
                a tenant can only claim a hostname listed here.
              </p>
              <ul v-if="row.tenant.allowedHostingDomains.length > 0" class="mt-2 flex flex-wrap gap-2">
                <li v-for="domain in row.tenant.allowedHostingDomains" :key="domain">
                  <UBadge color="neutral" variant="outline">
                    {{ domain }}
                  </UBadge>
                </li>
              </ul>
              <p v-else class="text-muted mt-2 text-xs italic">
                None yet -- this tenant cannot host any map until at least one domain is allowed.
              </p>
            </div>

            <PlatformEditTenantForm
              v-if="editingTenant === row.tenant.name"
              :tenant="row.tenant"
              class="border-default border-t pt-4"
              @updated="onUpdated"
              @cancel="editingTenant = null"
            />
          </div>
        </UCard>
      </li>
    </ul>
  </section>
</template>
