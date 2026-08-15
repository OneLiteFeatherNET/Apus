<script setup lang="ts">
/**
 * This design's signature, and the only way the product draws a proportion.
 *
 * World data is a grid -- regions of 32x32 chunks, chunks of 16x16 blocks -- and BlueMap renders
 * it as a tile pyramid, so progress here is squares completing rather than liquid rising. The
 * cells deliberately do not map to real tiles; see cellsFilled's own comment for why that
 * distinction is load-bearing rather than pedantic.
 *
 * Accessibility: the row is one progressbar carrying a real value, and the numeric readout beside
 * it is not decoration -- it is that value made visible, so the meter never communicates through
 * colour or cell count alone.
 */
import { computed } from 'vue'
import { cellsFilled, displayPercent } from '#core/utils/pipeline'

const props = withDefaults(defineProps<{
  percent: number
  cells?: number
  label: string
  /** Renders the pulse on the leading cell. Pass true only while work is genuinely in flight. */
  live?: boolean
  tone?: 'primary' | 'success' | 'warning' | 'error'
  /** Hides the numeric readout. Only for rows that print the same number themselves. */
  hideValue?: boolean
}>(), {
  cells: 24,
  live: false,
  tone: 'primary',
  hideValue: false
})

const filled = computed(() => cellsFilled(props.percent, props.cells))
// Not Math.round: the readout has to obey the same rule as the cells, or the meter contradicts
// itself at both ends. See displayPercent.
const rounded = computed(() => displayPercent(props.percent))

const filledTone = computed(() => ({
  primary: 'bg-primary',
  success: 'bg-success',
  warning: 'bg-warning',
  error: 'bg-error'
}[props.tone]))
</script>

<template>
  <div class="flex items-center gap-3">
    <div
      class="apus-cells flex"
      role="progressbar"
      :aria-label="label"
      :aria-valuenow="rounded"
      aria-valuemin="0"
      aria-valuemax="100"
    >
      <span
        v-for="cell in props.cells"
        :key="cell"
        class="apus-cell"
        :class="[
          cell <= filled ? filledTone : 'apus-cell--empty',
          props.live && cell === filled ? 'apus-cell--live' : ''
        ]"
      />
    </div>
    <span v-if="!hideValue" class="apus-value text-highlighted text-sm">{{ rounded }}%</span>
  </div>
</template>

<style scoped>
.apus-cells {
  gap: var(--apus-cell-gap);
}

/* Square, flush, no radius: the cell is the grammar of the whole design. */
.apus-cell {
  flex: none;
  width: var(--apus-cell);
  height: var(--apus-cell);
  border-radius: 0;
}

.apus-cell--empty {
  background-color: var(--ui-bg-accented);
}

/* The only animation in the product, and only on the leading cell of a live render -- the single
   place where "right now" is itself the information. A slow, low-contrast pulse, so a dashboard
   left open on a second monitor does not flicker in someone's peripheral vision all afternoon.
   The reduced-motion rule in tokens.css switches it off entirely. */
.apus-cell--live {
  animation: apus-cell-pulse 1.6s ease-in-out infinite;
}

@keyframes apus-cell-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}
</style>
