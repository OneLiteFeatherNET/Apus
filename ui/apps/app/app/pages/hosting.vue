<script setup lang="ts">
/**
 * Where this tenant's maps are published. Read-only: hostings are declared by the platform, not
 * from here, and the api module has no endpoint that would let this page pretend otherwise.
 *
 * The URL is the primary element on every card, because reaching the rendered map is the reason
 * this product exists.
 */
import { ApusApiError } from '#core/utils/apiErrors'
import type { BlueMapHostingResponse } from '#core/utils/apiTypes'

const api = useApiClient()
const hostings = ref<BlueMapHostingResponse[]>([])
const loading = ref(true)
const error = ref<ApusApiError | null>(null)

async function refresh(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    hostings.value = await api.listHostings()
  } catch (caught) {
    error.value = caught instanceof ApusApiError
      ? caught
      : new ApusApiError({ status: 0, message: 'Could not load hosting.' })
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <div class="mx-auto flex max-w-4xl flex-col gap-8 p-6 sm:p-10">
    <PageHeader
      eyebrow="Hosting"
      title="Published maps"
      description="The public addresses your rendered maps are served from."
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

    <p v-else-if="loading" class="text-muted text-sm">
      Loading…
    </p>

    <EmptyState
      v-else-if="hostings.length === 0"
      title="Nothing is published yet"
      description="A hosting serves one or more rendered maps at a public address. They are declared by the platform rather than from here — once one exists for this tenant, its address appears on this page."
    />

    <ul v-else class="flex flex-col gap-6">
      <li
        v-for="hosting in hostings"
        :key="hosting.name"
        class="border-default flex flex-col gap-4 border p-6"
      >
        <div class="flex flex-wrap items-center justify-between gap-3">
          <span class="apus-value text-highlighted text-sm">{{ hosting.name }}</span>
          <StatusPill :phase="hosting.ready ? 'Ready' : 'Pending'" />
        </div>

        <CopyField
          v-if="hosting.url"
          :value="hosting.url"
          :href="hosting.url"
          :label="`the public address of ${hosting.name}`"
        />
        <p v-else class="text-muted text-sm">
          No address yet. This host is assigned but not serving.
        </p>

        <div class="flex flex-col gap-2">
          <SectionLabel>Maps served</SectionLabel>
          <ul v-if="hosting.maps.length" class="flex flex-wrap gap-2">
            <li v-for="map in hosting.maps" :key="String(map)">
              <NuxtLink
                v-if="map"
                :to="`/worlds/${encodeURIComponent(map)}`"
                class="apus-value border-default text-highlighted hover:text-primary border px-2 py-0.5 text-xs"
              >{{ map }}</NuxtLink>
            </li>
          </ul>
          <p v-else class="text-muted text-sm">
            None yet.
          </p>
        </div>
      </li>
    </ul>
  </div>
</template>
