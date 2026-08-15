<script setup lang="ts">
/**
 * A world's journey, in the order it actually happens: a server's files become a bundle, the
 * bundle feeds a map, the map is rendered, the render is served. Five stages because the system
 * has five -- the sequence is information, not a decorative numbering.
 *
 * This is where the design's signature lands hardest. The rail answers "where is this stuck?" at
 * a glance, and the active stage's own cell meter then answers "how far along?" without a second
 * component and without a second look.
 *
 * `compact` is the list variant: five squares and nothing else, so a row in a list of twenty
 * carries the same answer as the detail page at a twentieth of the height.
 */
import type { PipelineStage } from '#core/utils/pipeline'

defineProps<{ stages: PipelineStage[], compact?: boolean }>()

const stateClass: Record<string, string> = {
  done: 'bg-primary border-primary',
  active: 'border-primary bg-transparent',
  pending: 'bg-transparent border-accented',
  failed: 'bg-error border-error',
  skipped: 'bg-transparent border-muted'
}

// Colour never carries a state on its own: every square has this word beside it, or -- in the
// compact variant, where there is no room -- in its accessible name.
const stateLabel: Record<string, string> = {
  done: 'Done',
  active: 'In progress',
  pending: 'Waiting',
  failed: 'Failed',
  skipped: 'Not applicable'
}
</script>

<template>
  <ol v-if="compact" class="apus-rail-compact flex items-center">
    <li v-for="stage in stages" :key="stage.key" class="flex items-center">
      <span class="apus-stage-dot border" :class="stateClass[stage.state]" aria-hidden="true" />
      <span class="sr-only">{{ stage.label }}: {{ stateLabel[stage.state] }}.</span>
    </li>
  </ol>

  <ol v-else class="flex flex-col gap-6 sm:flex-row sm:gap-0">
    <li
      v-for="(stage, index) in stages"
      :key="stage.key"
      class="flex min-w-0 flex-col gap-2 sm:flex-1"
    >
      <div class="flex items-center">
        <span class="apus-stage shrink-0 border" :class="stateClass[stage.state]" aria-hidden="true" />
        <span
          v-if="index < stages.length - 1"
          class="bg-accented hidden h-px flex-1 sm:block"
        />
      </div>

      <span class="apus-eyebrow text-dimmed">{{ stage.label }}</span>
      <span class="text-highlighted text-sm">{{ stateLabel[stage.state] }}</span>
      <p class="text-muted max-w-[24ch] text-xs">{{ stage.detail }}</p>

      <CellMeter
        v-if="stage.state === 'active'"
        class="mt-1"
        :percent="stage.percent"
        :cells="12"
        :live="true"
        :label="`${stage.label} progress`"
      />
    </li>
  </ol>
</template>

<style scoped>
.apus-rail-compact {
  gap: var(--apus-cell-gap);
}

.apus-stage {
  width: var(--apus-rail-stage);
  height: var(--apus-rail-stage);
  border-radius: 0;
}

.apus-stage-dot {
  width: calc(var(--apus-cell) + 2px);
  height: calc(var(--apus-cell) + 2px);
  border-radius: 0;
}
</style>
