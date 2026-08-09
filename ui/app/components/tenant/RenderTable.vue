<script setup lang="ts">
import type { BlueMapRenderResponse } from '~/utils/apiTypes'
import { isRenderTerminal, type RenderProgressSnapshot } from './renderProgress'
import { formatTimestamp } from './formatTimestamp'

// Design spec §11.2: "Renders: Verlauf mit Zustand, und für den laufenden Render Fortschritt in
// Prozent mit geschätzter Restzeit, live." The history itself comes from one GET (a snapshot);
// any render that isn't terminal yet gets a live RenderProgressBar inline, on top of that
// snapshot, rather than the page re-polling GET /api/renders.
const props = defineProps<{ renders: BlueMapRenderResponse[] }>()

function initialSnapshot(render: BlueMapRenderResponse): RenderProgressSnapshot {
  return {
    phase: render.phase,
    percent: render.progress.percent,
    etaSeconds: render.progress.etaSeconds,
    degraded: render.progress.degraded
  }
}
</script>

<template>
  <p v-if="props.renders.length === 0" class="text-sm text-muted">
    No renders yet for this tenant.
  </p>
  <ul v-else class="space-y-4">
    <li
      v-for="render in props.renders"
      :key="render.name"
      class="rounded border border-default p-4"
    >
      <div class="flex flex-wrap items-center justify-between gap-2">
        <NuxtLink :to="`/tenant/renders/${encodeURIComponent(render.name)}`" class="font-medium underline">
          {{ render.name }}
        </NuxtLink>
        <span class="text-sm text-muted">{{ render.mapRef ?? 'unknown map' }}</span>
      </div>

      <dl class="mt-2 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
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

      <div class="mt-3">
        <TenantRenderProgressBar
          v-if="!isRenderTerminal(render.phase)"
          :render-id="render.name"
          :initial="initialSnapshot(render)"
        />
        <TenantConditionsBadgeList v-else :conditions="render.conditions" />
      </div>
    </li>
  </ul>
</template>
