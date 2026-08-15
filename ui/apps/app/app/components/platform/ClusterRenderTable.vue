<script setup lang="ts">
// Cluster-wide job visibility for the platform dashboard (design spec §11.2: "laufende Jobs
// clusterweit"), backed by `GET /api/renders/cluster` (BlueMapRenderController#listCluster),
// platform-admin only.
//
// Deliberately a snapshot, not a live view: `GET /api/renders/{id}/events` (the SSE stream
// TenantRenderProgressBar.vue uses) resolves its namespace through the caller's own tenant
// claim, same as every other tenant-scoped endpoint -- a platform-admin viewing another
// tenant's render cannot subscribe to that stream. This table shows the same
// percent/eta/degraded fields the tenant dashboard's own snapshot shows before a live stream
// takes over, refreshed by re-fetching rather than a per-render event subscription.
import { ApusApiError } from '#core/utils/apiErrors'
import type { ClusterRenderResponse } from '#core/utils/apiTypes'
import { formatTimestamp } from '#core/utils/formatTimestamp'

const renders = ref<ClusterRenderResponse[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)

const api = useApiClient()

async function refresh(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    renders.value = await api.listClusterRenders()
  } catch (error) {
    loadError.value = error instanceof ApusApiError ? error.message : 'Could not load cluster-wide renders.'
  } finally {
    loading.value = false
  }
}

onMounted(refresh)

defineExpose({ refresh })
</script>

<template>
  <section aria-labelledby="cluster-jobs-heading" class="space-y-4">
    <div class="flex flex-wrap items-baseline justify-between gap-2">
      <h2 id="cluster-jobs-heading" class="text-lg font-medium">
        Running jobs across tenants
      </h2>
      <UButton size="xs" variant="ghost" color="neutral" icon="i-lucide-refresh-cw" :loading="loading" @click="refresh">
        Refresh
      </UButton>
    </div>

    <UAlert v-if="loadError" color="error" variant="subtle" :title="loadError" />

    <p v-if="loading && renders.length === 0" class="text-muted text-sm">
      Loading renders…
    </p>
    <p v-else-if="renders.length === 0" class="text-muted text-sm">
      No renders running anywhere on the platform right now.
    </p>

    <ul v-else class="space-y-4">
      <li
        v-for="entry in renders"
        :key="`${entry.tenant}/${entry.render.name}`"
        class="border-default rounded border p-4"
      >
        <div class="flex flex-wrap items-center justify-between gap-2">
          <span class="font-medium">{{ entry.render.name }}</span>
          <UBadge color="neutral" variant="subtle">
            {{ entry.tenant }}
          </UBadge>
        </div>

        <dl class="mt-2 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt class="text-muted">
            Map
          </dt>
          <dd>{{ entry.render.mapRef ?? 'unknown map' }}</dd>

          <dt class="text-muted">
            Phase
          </dt>
          <dd>{{ entry.render.phase ?? 'unknown' }}</dd>

          <dt class="text-muted">
            Progress
          </dt>
          <dd>
            <span v-if="entry.render.progress.percent >= 0">{{ entry.render.progress.percent.toFixed(1) }}%</span>
            <span v-else>unknown</span>
            <span v-if="entry.render.progress.degraded" class="text-muted"> (degraded measurement)</span>
          </dd>

          <dt class="text-muted">
            Started
          </dt>
          <dd>{{ formatTimestamp(entry.render.startTime) }}</dd>
        </dl>
      </li>
    </ul>
  </section>
</template>
