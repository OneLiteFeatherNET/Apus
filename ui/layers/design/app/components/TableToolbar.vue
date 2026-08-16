<script setup lang="ts">
/**
 * The filter/action row above a table. A landmark rather than a bare div, so a keyboard user can
 * jump to the controls without walking the table.
 *
 * Named TableToolbar rather than Toolbar because Vue's own convention wants a multi-word
 * component name -- and the longer name is the more accurate one anyway: this belongs above a
 * table, not anywhere a row of buttons is needed.
 */
defineProps<{ label: string }>()
</script>

<template>
  <div
    class="flex flex-wrap items-center justify-between gap-3"
    role="toolbar"
    :aria-label="label"
  >
    <div class="flex min-w-0 flex-wrap items-center gap-2">
      <slot name="filters" />
    </div>
    <div class="flex shrink-0 items-center gap-2">
      <slot name="actions" />
    </div>
  </div>
</template>
