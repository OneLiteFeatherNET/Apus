<script setup lang="ts">
/**
 * Every render on the platform, grouped by tenant. The one view a tenant cannot produce for
 * itself, and the reason `GET /api/renders/cluster` exists.
 */
import { ApusApiError } from '#core/utils/apiErrors'
import { formatTimestamp } from '#core/utils/formatTimestamp'
import type { ClusterRenderResponse } from '#core/utils/apiTypes'
import type { DataTableColumn } from '#design/components/DataTable.vue'

const api = useApiClient()
const entries = ref<ClusterRenderResponse[]>([])
const loading = ref(true)
const error = ref<ApusApiError | null>(null)

async function refresh(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    entries.value = await api.listClusterRenders()
  } catch (caught) {
    error.value = caught instanceof ApusApiError
      ? caught
      : new ApusApiError({ status: 0, message: 'Could not load cluster renders.' })
  } finally {
    loading.value = false
  }
}

onMounted(refresh)

const rows = computed(() =>
  entries.value
    // Running first: an operator opening this page is looking for what is happening, not for
    // what happened. Within a phase, newest first.
    .slice()
    .sort((a, b) => {
      const aRunning = a.render.phase === 'Running' ? 0 : 1
      const bRunning = b.render.phase === 'Running' ? 0 : 1
      if (aRunning !== bRunning) return aRunning - bRunning
      const aStart = a.render.startTime ? Date.parse(a.render.startTime) : 0
      const bStart = b.render.startTime ? Date.parse(b.render.startTime) : 0
      return (Number.isNaN(bStart) ? 0 : bStart) - (Number.isNaN(aStart) ? 0 : aStart)
    })
    .map(entry => ({
      id: `${entry.tenant}/${entry.render.name}`,
      tenant: entry.tenant,
      name: entry.render.name,
      mapRef: entry.render.mapRef,
      phase: entry.render.phase,
      percent: entry.render.progress.percent,
      startTime: entry.render.startTime
    }))
)

const columns: DataTableColumn[] = [
  { key: 'tenant', label: 'Tenant' },
  { key: 'name', label: 'Render' },
  { key: 'phase', label: 'Phase' },
  { key: 'percent', label: 'Progress' },
  { key: 'startTime', label: 'Started', secondary: true }
]
</script>

<template>
  <PlatformGate>
    <div class="flex flex-col gap-8 p-6 sm:p-10">
      <PageHeader
        eyebrow="Render"
        title="Cluster renders"
        description="Every render across every tenant, running first."
      >
        <template #actions>
          <UButton size="sm" variant="subtle" :loading="loading" @click="refresh">
            Refresh
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
        :rows="rows"
        row-key="id"
        :loading="loading"
        caption="Renders across every tenant"
      >
        <template #empty>
          <p class="text-muted text-sm">
            No renders anywhere on the platform.
          </p>
        </template>

        <template #cell-tenant="{ row }">
          <NuxtLink
            :to="`/tenants/${encodeURIComponent(row.tenant)}`"
            class="apus-value text-muted hover:text-primary text-xs"
          >{{ row.tenant }}</NuxtLink>
        </template>

        <template #cell-name="{ row }">
          <div class="flex flex-col gap-0.5">
            <span class="apus-value text-highlighted">{{ row.name }}</span>
            <span v-if="row.mapRef" class="apus-value text-dimmed text-xs">{{ row.mapRef }}</span>
          </div>
        </template>

        <template #cell-phase="{ row }">
          <StatusPill :phase="row.phase" />
        </template>

        <template #cell-percent="{ row }">
          <CellMeter
            :percent="row.percent"
            :cells="16"
            :live="row.phase === 'Running'"
            :tone="row.phase === 'Failed' ? 'error' : row.phase === 'Succeeded' ? 'success' : 'primary'"
            :label="`${row.name} progress`"
          />
        </template>

        <template #cell-startTime="{ row }">
          <span class="apus-value text-muted text-xs">{{ formatTimestamp(row.startTime) }}</span>
        </template>
      </DataTable>
    </div>
  </PlatformGate>
</template>
