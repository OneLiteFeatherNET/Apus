<script setup lang="ts">
/**
 * An empty screen is an invitation to act, not a shrug.
 *
 * One sentence saying what would fill this space and why it is worth filling, and at most one
 * action. Two actions on an empty state means the product has not decided what someone should do
 * first, and the reader has to make that decision instead.
 */
defineProps<{ title: string, description: string }>()
</script>

<template>
  <div class="border-default bg-muted flex flex-col items-start gap-3 border p-8">
    <div class="apus-empty-mark" aria-hidden="true">
      <span v-for="cell in 9" :key="cell" class="apus-empty-cell" />
    </div>
    <h2 class="text-highlighted text-base font-medium">
      {{ title }}
    </h2>
    <p class="text-muted max-w-prose text-sm">
      {{ description }}
    </p>
    <div v-if="$slots.action" class="mt-1">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
/* An empty three-by-three of the same cells the meters use: the space is not broken, it is
   unfilled, and it is the same grid that fills up once there is something to show. */
.apus-empty-mark {
  display: grid;
  grid-template-columns: repeat(3, var(--apus-cell));
  gap: var(--apus-cell-gap);
}

.apus-empty-cell {
  width: var(--apus-cell);
  height: var(--apus-cell);
  border: 1px solid var(--ui-border-accented);
}
</style>
