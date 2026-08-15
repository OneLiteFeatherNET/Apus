<script setup lang="ts">
/**
 * The top of every page: what this is, in one line, and what you can do about it.
 *
 * `description` is a sentence, not a tagline. If a page needs more than one sentence to say what
 * it is, the page is doing more than one job.
 */
defineProps<{ title: string, eyebrow?: string, description?: string }>()
</script>

<template>
  <div class="flex flex-wrap items-start justify-between gap-4">
    <div class="flex min-w-0 flex-col gap-1">
      <SectionLabel v-if="eyebrow">
        {{ eyebrow }}
      </SectionLabel>
      <h1 class="text-highlighted text-2xl font-semibold tracking-tight">
        {{ title }}
      </h1>
      <p v-if="description" class="text-muted max-w-prose text-sm">
        {{ description }}
      </p>
    </div>
    <div class="flex shrink-0 items-center gap-2">
      <slot name="actions" />
    </div>
  </div>
</template>
