<script setup lang="ts">
/**
 * Every tenant, dense enough to scan. Quota against observed usage is the column an operator
 * actually reads, so it gets a meter rather than a number to compare by eye.
 *
 * Observed usage is an observation, not a control -- Ceph enforces the quota, Apus only reports
 * what it sees (see storageUsage.ts's module Javadoc).
 */
import type { DataTableColumn } from '#design/components/DataTable.vue'

const { tenants, loading, error, refresh } = useTenants()

const columns: DataTableColumn[] = [
  { key: 'name', label: 'Tenant' },
  { key: 'storage', label: 'Storage' },
  { key: 'domains', label: 'Domains', numeric: true, secondary: true },
  { key: 'namespace', label: 'Namespace', secondary: true }
]
</script>

<template>
  <PlatformGate>
    <div class="flex flex-col gap-8 p-6 sm:p-10">
      <PageHeader
        eyebrow="Tenant"
        title="Tenants"
        description="Every tenant on this platform, their storage, and the hostnames they may publish maps on."
      >
        <template #actions>
          <UButton size="sm" variant="subtle" :loading="loading" @click="refresh">
            Refresh
          </UButton>
          <UButton to="/tenants/new" size="sm">
            Create tenant
          </UButton>
        </template>
      </PageHeader>

      <ErrorState
        v-if="error"
        :status="error.status"
        :message="error.message"
        retryable
        @retry="refresh"
      />

      <DataTable
        v-else
        :columns="columns"
        :rows="tenants"
        row-key="id"
        :loading="loading"
        caption="Tenants on this platform"
      >
        <template #empty>
          <div class="flex flex-col items-start gap-3">
            <p class="text-muted text-sm">
              No tenants yet. A tenant owns a namespace, a storage quota and the hostnames its maps
              may be published on.
            </p>
            <UButton to="/tenants/new" size="sm">
              Create the first tenant
            </UButton>
          </div>
        </template>

        <template #cell-name="{ row }">
          <div class="flex flex-col gap-0.5">
            <NuxtLink
              :to="`/tenants/${encodeURIComponent(row.name)}`"
              class="apus-value text-highlighted hover:text-primary"
            >{{ row.name }}</NuxtLink>
            <span v-if="row.displayName && row.displayName !== row.name" class="text-muted text-xs">
              {{ row.displayName }}
            </span>
          </div>
        </template>

        <template #cell-storage="{ row }">
          <div class="flex flex-col gap-1">
            <CellMeter
              v-if="row.usage.ratio !== null"
              :percent="row.usage.ratio * 100"
              :cells="16"
              :tone="row.usage.level === 'critical' ? 'error' : row.usage.level === 'warning' ? 'warning' : 'primary'"
              :label="`${row.name} storage use`"
            />
            <span class="apus-value text-muted text-xs">
              {{ row.usage.usedLabel }} of {{ row.usage.quotaLabel }}
            </span>
          </div>
        </template>

        <template #cell-domains="{ row }">
          <span class="apus-value text-muted text-xs">{{ row.allowedHostingDomains.length }}</span>
        </template>

        <template #cell-namespace="{ row }">
          <span class="apus-value text-muted text-xs">{{ row.namespace }}</span>
        </template>
      </DataTable>
    </div>
  </PlatformGate>
</template>
