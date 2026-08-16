<script setup lang="ts">
/**
 * A machine value someone is going to paste somewhere: a public map URL, a bucket path, a render
 * name. Showing it without a way to copy it means every use is a manual selection, and these are
 * exactly the strings that are painful to select by hand.
 *
 * The confirmation is a state change on the button's own label, not a toast. The reader is
 * looking at the button when they press it.
 */
import { ref } from 'vue'

const props = defineProps<{ value: string, label: string, href?: string }>()

const copied = ref(false)
const { copy, isSupported } = useClipboard()

async function copyValue(): Promise<void> {
  await copy(props.value)
  copied.value = true
  setTimeout(() => { copied.value = false }, 2000)
}
</script>

<template>
  <div class="border-default bg-muted flex items-center gap-2 border px-3 py-2">
    <a
      v-if="href"
      :href="href"
      target="_blank"
      rel="noopener"
      class="apus-value text-primary min-w-0 flex-1 truncate text-sm hover:underline"
    >{{ value }}</a>
    <span v-else class="apus-value text-highlighted min-w-0 flex-1 truncate text-sm">{{ value }}</span>

    <UButton
      v-if="isSupported"
      size="xs"
      variant="ghost"
      :aria-label="`Copy ${label}`"
      @click="copyValue"
    >
      {{ copied ? 'Copied' : 'Copy' }}
    </UButton>
  </div>
</template>
