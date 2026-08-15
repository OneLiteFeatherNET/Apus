<script setup lang="ts">
/**
 * What went wrong and what to do about it, in the interface's voice.
 *
 * The three cases below are the api module's own documented shapes (see ui/README.md, "Typed API
 * client"): 403 and 404 carry no body by design, and a 404 is deliberately indistinguishable
 * from "exists, but not in your tenant" -- so the copy for it must not promise that the thing is
 * gone. `status: 0` is a network failure that never reached an HTTP response, which is the one
 * case where retrying is genuinely the right advice.
 *
 * No apology, no exclamation mark, and never a raw stack trace.
 */
import { computed } from 'vue'

const props = defineProps<{ status: number, message: string, retryable?: boolean }>()
const emit = defineEmits<{ retry: [] }>()

const title = computed(() => {
  switch (props.status) {
    case 0: return 'Could not reach the server'
    case 403: return 'You do not have access to this'
    case 404: return 'Not found here'
    default: return 'Something went wrong'
  }
})

const advice = computed(() => {
  switch (props.status) {
    case 0: return 'The request never reached Apus. Check your connection and try again.'
    case 403: return 'Your account is missing the role this area needs. An administrator can grant it.'
    case 404: return 'This either does not exist or belongs to another tenant. Apus cannot tell you which.'
    default: return props.message
  }
})
</script>

<template>
  <div class="border-error/40 bg-error/5 flex flex-col items-start gap-2 border p-6">
    <SectionLabel>Error</SectionLabel>
    <h2 class="text-highlighted text-base font-medium">
      {{ title }}
    </h2>
    <p class="text-muted max-w-prose text-sm">
      {{ advice }}
    </p>
    <p v-if="status !== 0 && status !== 403 && status !== 404" class="apus-value text-dimmed text-xs">
      {{ message }}
    </p>
    <UButton
      v-if="retryable"
      class="mt-2"
      size="sm"
      variant="subtle"
      @click="emit('retry')"
    >
      Try again
    </UButton>
  </div>
</template>
