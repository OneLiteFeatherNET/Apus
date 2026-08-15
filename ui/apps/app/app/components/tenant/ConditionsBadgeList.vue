<script setup lang="ts">
import type { ConditionResponse } from '#core/utils/apiTypes'

// Shared rendering of a resource's `conditions` array (WorldSource/BlueMapMap/BlueMapRender/
// BlueMapHosting all carry one, design spec §11.1) -- kept in one place so "what does tenant-
// operator-facing status look like" is answered consistently across the four sections.
const props = defineProps<{ conditions: ConditionResponse[] }>()

function colorFor(status: string): 'success' | 'error' | 'neutral' {
  if (status === 'True') return 'success'
  if (status === 'False') return 'error'
  return 'neutral'
}
</script>

<template>
  <span v-if="props.conditions.length === 0" class="text-sm text-muted">No status reported yet</span>
  <ul v-else class="flex flex-wrap gap-2">
    <li v-for="condition in props.conditions" :key="condition.type">
      <UBadge
        :color="colorFor(condition.status)"
        variant="subtle"
        :title="condition.message || undefined"
      >
        {{ condition.type }}: {{ condition.reason }}
      </UBadge>
    </li>
  </ul>
</template>
