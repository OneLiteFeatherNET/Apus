<script setup lang="ts">
/**
 * The sources feeding this tenant's worlds. A source is watched; each time it changes, Apus takes
 * a snapshot -- a bundle -- and that bundle is what a render consumes.
 */
import { ApusApiError } from '#core/utils/apiErrors'
import { formatTimestamp } from '#core/utils/formatTimestamp'
import type { WorldSourceResponse } from '#core/utils/apiTypes'
import type { DataTableColumn } from '#design/components/DataTable.vue'

const api = useApiClient()
const sources = ref<WorldSourceResponse[]>([])
const loading = ref(true)
const error = ref<ApusApiError | null>(null)

async function refresh(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    sources.value = await api.listSources()
  } catch (caught) {
    error.value = caught instanceof ApusApiError
      ? caught
      : new ApusApiError({ status: 0, message: 'Could not load sources.' })
  } finally {
    loading.value = false
  }
}

onMounted(refresh)

const rows = computed(() => sources.value.map(source => ({ ...source, id: source.name })))

const columns: DataTableColumn[] = [
  { key: 'name', label: 'Source' },
  { key: 'type', label: 'Type' },
  { key: 'latestBundle', label: 'Latest snapshot' },
  { key: 'lastPollTime', label: 'Last checked', secondary: true },
  { key: 'worlds', label: 'Worlds', numeric: true, secondary: true }
]
</script>

<template>
  <div class="mx-auto flex max-w-6xl flex-col gap-8 p-6 sm:p-10">
    <PageHeader
      eyebrow="Source"
      title="Sources"
      description="Where your world files come from. Apus watches each one and takes a snapshot whenever it changes."
    >
      <template #actions>
        <UButton to="/sources/new" size="sm">
          Connect a source
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
      caption="Sources connected to this tenant"
    >
      <template #empty>
        <div class="flex flex-col items-start gap-3">
          <p class="text-muted text-sm">
            No sources yet. Connect one and Apus starts watching it for world snapshots.
          </p>
          <UButton to="/sources/new" size="sm">
            Connect a source
          </UButton>
        </div>
      </template>

      <template #cell-name="{ row }">
        <span class="apus-value text-highlighted">{{ row.name }}</span>
      </template>

      <template #cell-type="{ row }">
        <span class="apus-value text-muted text-xs uppercase">{{ row.type }}</span>
      </template>

      <template #cell-latestBundle="{ row }">
        <span v-if="row.latestBundle" class="apus-value text-muted text-xs">
          {{ row.latestBundle.version }}
        </span>
        <span v-else class="text-dimmed text-xs">None yet</span>
      </template>

      <template #cell-lastPollTime="{ row }">
        <span class="apus-value text-muted text-xs">{{ formatTimestamp(row.lastPollTime) }}</span>
      </template>

      <template #cell-worlds="{ row }">
        <span class="apus-value text-muted text-xs">{{ row.worlds.length }}</span>
      </template>
    </DataTable>
  </div>
</template>
