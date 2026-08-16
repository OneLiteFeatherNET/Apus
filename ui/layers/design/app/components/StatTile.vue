<script setup lang="ts">
/**
 * A single number worth putting on an overview, with an optional meter under it.
 *
 * Deliberately restrained: a big number over a small label is the most template-shaped thing a
 * dashboard can do, so the number here is sized for reading rather than for impact, and a tile
 * only earns its place if someone would open the page to learn it.
 */
defineProps<{
  label: string
  value: string | number
  hint?: string
  /** Renders a cell meter beneath. 0-100. */
  percent?: number
  meterLabel?: string
  tone?: 'primary' | 'success' | 'warning' | 'error'
}>()
</script>

<template>
  <div class="border-default bg-muted flex flex-col gap-2 border p-4">
    <SectionLabel>{{ label }}</SectionLabel>
    <span class="apus-value text-highlighted text-xl">{{ value }}</span>
    <CellMeter
      v-if="percent !== undefined"
      :percent="percent"
      :cells="16"
      :tone="tone ?? 'primary'"
      :label="meterLabel ?? label"
    />
    <p v-if="hint" class="text-muted text-xs">
      {{ hint }}
    </p>
  </div>
</template>
