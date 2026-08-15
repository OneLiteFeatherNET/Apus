<script setup lang="ts">
/**
 * One status vocabulary for both applications.
 *
 * A "Failed" render must look identical in a tenant's world list and in an admin's cluster view.
 * An operator reads both, often within the same minute, and two dialects would cost them a beat
 * every single time. So the phase-to-tone mapping lives here and nowhere else.
 *
 * Colour never carries the meaning alone: the label is always rendered, and the marker is a
 * square rather than a dot -- the cell grammar, at its smallest size.
 */
import { computed } from 'vue'

const props = defineProps<{ phase: string | null }>()

const tone = computed(() => {
  switch (props.phase) {
    case 'Succeeded':
    case 'Ready':
    case 'Bound':
      return 'success'
    case 'Running':
    case 'Pending':
      return 'info'
    case 'Failed':
    case 'Error':
      return 'error'
    case 'Degraded':
      return 'warning'
    default:
      // Includes null. "Unknown" is the honest word for a phase the API did not send; inventing
      // "Pending" here would turn a gap in the data into a claim about the world.
      return 'neutral'
  }
})

const label = computed(() => props.phase ?? 'Unknown')
</script>

<template>
  <span
    class="apus-value inline-flex items-center gap-1.5 border px-2 py-0.5 text-xs"
    :class="{
      'border-success/40 text-success bg-success/10': tone === 'success',
      'border-info/40 text-info bg-info/10': tone === 'info',
      'border-error/40 text-error bg-error/10': tone === 'error',
      'border-warning/40 text-warning bg-warning/10': tone === 'warning',
      'border-default text-muted': tone === 'neutral'
    }"
  >
    <span
      class="size-1.5 shrink-0"
      aria-hidden="true"
      :class="{
        'bg-success': tone === 'success',
        'bg-info': tone === 'info',
        'bg-error': tone === 'error',
        'bg-warning': tone === 'warning',
        'bg-muted': tone === 'neutral'
      }"
    />
    {{ label }}
  </span>
</template>
