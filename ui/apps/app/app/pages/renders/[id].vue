<script setup lang="ts">
/**
 * One render, live. Progress and log sit on the same page because they answer the same question
 * at two levels of detail, and a reader watching a stalled percentage wants the log without
 * navigating away from the number they are watching.
 */
import { ApusApiError } from '#core/utils/apiErrors'
import { formatTimestamp } from '#core/utils/formatTimestamp'
import type { BlueMapRenderResponse } from '#core/utils/apiTypes'
import type { RenderProgressSnapshot } from '#core/utils/renderProgress'
import type { MetaItem } from '#design/components/MetaList.vue'

const route = useRoute()
const id = computed(() => String(route.params.id ?? ''))

const api = useApiClient()
const render = ref<BlueMapRenderResponse | null>(null)
const loading = ref(true)
const error = ref<ApusApiError | null>(null)

async function refresh(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    render.value = await api.getRender(id.value)
  } catch (caught) {
    error.value = caught instanceof ApusApiError
      ? caught
      : new ApusApiError({ status: 0, message: 'Could not load this render.' })
  } finally {
    loading.value = false
  }
}

onMounted(refresh)

const initial = computed<RenderProgressSnapshot>(() => ({
  phase: render.value?.phase ?? null,
  percent: render.value?.progress.percent ?? 0,
  etaSeconds: render.value?.progress.etaSeconds ?? 0,
  degraded: render.value?.progress.degraded ?? false
}))

const metadata = computed<MetaItem[]>(() => {
  const current = render.value
  if (!current) return []
  return [
    { label: 'World', value: current.mapRef },
    { label: 'Forced', value: current.force ? 'Yes' : 'No' },
    { label: 'Started', value: formatTimestamp(current.startTime) },
    { label: 'Completed', value: formatTimestamp(current.completionTime) }
  ]
})
</script>

<template>
  <div class="mx-auto flex max-w-4xl flex-col gap-8 p-6 sm:p-10">
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

    <template v-else-if="render">
      <PageHeader eyebrow="Render" :title="render.name">
        <template #actions>
          <StatusPill :phase="render.phase" />
        </template>
      </PageHeader>

      <NuxtLink
        v-if="render.mapRef"
        :to="`/worlds/${encodeURIComponent(render.mapRef)}`"
        class="apus-value text-primary text-sm hover:underline"
      >
        Back to {{ render.mapRef }}
      </NuxtLink>

      <section class="border-default border p-6">
        <RenderLiveRenderProgress :render-id="render.name" :initial="initial" />
      </section>

      <RenderLiveRenderLog :render-id="render.name" />

      <section class="flex flex-col gap-3">
        <SectionLabel as="h2">
          Details
        </SectionLabel>
        <MetaList :items="metadata" />
      </section>

      <section v-if="render.conditions.length" class="flex flex-col gap-3">
        <SectionLabel as="h2">
          Conditions
        </SectionLabel>
        <ul class="flex flex-col gap-2">
          <li
            v-for="condition in render.conditions"
            :key="condition.type"
            class="border-default flex flex-col gap-1 border p-3"
          >
            <span class="apus-value text-highlighted text-sm">
              {{ condition.type }} = {{ condition.status }}
            </span>
            <span v-if="condition.reason" class="apus-value text-dimmed text-xs">
              {{ condition.reason }}
            </span>
            <span v-if="condition.message" class="text-muted text-sm">
              {{ condition.message }}
            </span>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>
