<script setup lang="ts">
import type { BlueMapRenderResponse } from '~/utils/apiTypes'
import { ApusApiError } from '~/utils/apiErrors'
import { isRenderTerminal, type RenderProgressSnapshot } from '~/utils/renderProgress'
import { formatTimestamp } from '~/utils/formatTimestamp'

const { principal } = useAuth()
const hasAccess = computed(() => canReadTenant(principal.value))

const route = useRoute()
const renderId = computed(() => String(route.params.id))

const api = useApiClient()
const render = ref<BlueMapRenderResponse | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

const initialSnapshot = computed<RenderProgressSnapshot | null>(() => {
  if (!render.value) return null
  return {
    phase: render.value.phase,
    percent: render.value.progress.percent,
    etaSeconds: render.value.progress.etaSeconds,
    degraded: render.value.progress.degraded
  }
})

onMounted(async () => {
  if (!hasAccess.value) {
    loading.value = false
    return
  }
  try {
    render.value = await api.getRender(renderId.value)
  } catch (caught) {
    error.value = caught instanceof ApusApiError ? caught.message : 'Could not load this render.'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <TenantNav />
    <h1 class="mb-4 text-2xl font-semibold">
      Render {{ renderId }}
    </h1>
    <TenantAccessGate>
      <UAlert
        v-if="error"
        color="error"
        variant="subtle"
        title="Could not load this render"
        :description="error"
      />
      <p v-else-if="loading" class="text-sm text-muted">
        Loading…
      </p>
      <div v-else-if="render" class="space-y-6">
        <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt class="text-muted">
            Map
          </dt>
          <dd>{{ render.mapRef ?? 'unknown' }}</dd>

          <dt class="text-muted">
            Phase
          </dt>
          <dd>{{ render.phase ?? 'unknown' }}</dd>

          <dt class="text-muted">
            Started
          </dt>
          <dd>{{ formatTimestamp(render.startTime) }}</dd>

          <dt class="text-muted">
            Completed
          </dt>
          <dd>{{ formatTimestamp(render.completionTime) }}</dd>
        </dl>

        <UCard>
          <template #header>
            <h2 class="font-medium">
              Progress
            </h2>
          </template>
          <TenantRenderProgressBar
            v-if="!isRenderTerminal(render.phase) && initialSnapshot"
            :render-id="render.name"
            :initial="initialSnapshot"
          />
          <TenantConditionsBadgeList v-else :conditions="render.conditions" />
        </UCard>

        <UCard>
          <template #header>
            <h2 class="font-medium">
              Log output
            </h2>
          </template>
          <TenantRenderLogViewer :render-id="render.name" />
        </UCard>
      </div>
    </TenantAccessGate>
  </div>
</template>
