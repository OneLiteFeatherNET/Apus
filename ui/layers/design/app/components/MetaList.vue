<script setup lang="ts">
/**
 * Label/value pairs for resource metadata. A real <dl>, so the pairing survives being read aloud.
 *
 * Values are machine values by default -- versions, paths, identifiers, timestamps -- and get the
 * mono treatment. Pass `prose: true` for the rare entry that is a sentence.
 */
export interface MetaItem {
  label: string
  value: string | null
  prose?: boolean
}

defineProps<{ items: MetaItem[] }>()
</script>

<template>
  <dl class="grid grid-cols-1 gap-x-8 gap-y-3 sm:grid-cols-2">
    <div v-for="item in items" :key="item.label" class="flex min-w-0 flex-col gap-1">
      <dt class="apus-eyebrow text-dimmed">
        {{ item.label }}
      </dt>
      <dd
        class="text-highlighted truncate text-sm"
        :class="item.prose ? '' : 'apus-value'"
      >
        <!-- An em dash, not an empty cell: "the API sent nothing here" is different from "this
             row failed to render", and only one of them is worth investigating. -->
        {{ item.value ?? '—' }}
      </dd>
    </div>
  </dl>
</template>
