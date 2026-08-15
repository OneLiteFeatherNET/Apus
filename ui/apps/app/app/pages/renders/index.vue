<script setup lang="ts">
/**
 * Render history across every world. The world-centric pages answer "is my map current"; this one
 * answers "what has this tenant been doing", which is a different question and deserves its own
 * page rather than a tab someone has to find.
 */
import { ApusApiError } from '#core/utils/apiErrors'
import { formatTimestamp } from '#core/utils/formatTimestamp'
import type { BlueMapRenderResponse } from '#core/utils/apiTypes'
import type { DataTableColumn } from '#design/components/DataTable.vue'

const api = useApiClient()
const renders = ref<BlueMapRenderResponse[]>([])
const loading = ref(true)
const error = ref<ApusApiError | null>(null)

async function refresh(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const list = await api.listRenders()
    // Newest first; the API promises no order and a reshuffling list is disorienting.
    renders.value = list.slice().sort((a, b) => {
      const left = a.startTime ? Date.parse(a.startTime) : 0
      const right = b.startTime ? Date.parse(b.startTime) : 0
      return (Number.isNaN(right) ? 0 : right) - (Number.isNaN(left) ? 0 : left)
    })
  } catch (caught) {
    error.value = caught instanceof ApusApiError
      ? caught
      : new ApusApiError({ status: 0, message: 'Could not load renders.' })
  } finally {
    loading.value = false
  }
}

onMounted(refresh)

const rows = computed(() => renders.value.map(render => ({ ...render, id: render.name })))

const columns: DataTableColumn[] = [
  { key: 'name', label: 'Render' },
  { key: 'mapRef', label: 'World' },
  { key: 'phase', label: 'Phase' },
  { key: 'startTime', label: 'Started', secondary: true },
  { key: 'progress', label: 'Progress', numeric: true }
]
</script>

<template>
  <div class="mx-auto flex max-w-6xl flex-col gap-8 p-6 sm:p-10">
    <PageHeader
      eyebrow="Render"
      title="Render history"
      description="Every render this tenant has run, newest first."
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
      caption="Every render this tenant has run"
    >
      <template #empty>
        <p class="text-muted text-sm">
          Nothing has been rendered yet. Start a render from one of your worlds.
        </p>
      </template>

      <template #cell-name="{ row }">
        <NuxtLink
          :to="`/renders/${encodeURIComponent(row.name)}`"
          class="apus-value text-highlighted hover:text-primary"
        >{{ row.name }}</NuxtLink>
      </template>

      <template #cell-mapRef="{ row }">
        <NuxtLink
          v-if="row.mapRef"
          :to="`/worlds/${encodeURIComponent(row.mapRef)}`"
          class="apus-value text-muted hover:text-primary"
        >{{ row.mapRef }}</NuxtLink>
        <span v-else class="text-dimmed">—</span>
      </template>

      <template #cell-phase="{ row }">
        <StatusPill :phase="row.phase" />
      </template>

      <template #cell-startTime="{ row }">
        <span class="apus-value text-muted text-xs">{{ formatTimestamp(row.startTime) }}</span>
      </template>

      <template #cell-progress="{ row }">
        <span class="apus-value text-muted text-xs">{{ row.progress.percent }}%</span>
      </template>
    </DataTable>
  </div>
</template>
