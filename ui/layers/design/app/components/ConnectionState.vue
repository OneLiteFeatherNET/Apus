<script setup lang="ts">
/**
 * Whether a live stream is actually live.
 *
 * The failure this exists to prevent: an SSE connection drops and the progress meter simply stops
 * moving. Nothing is wrong on screen, so the reader waits -- sometimes for a long time -- for a
 * number that will never change again. A meter that has stopped must say whether it stopped
 * because the work finished or because the connection went away.
 */
import { computed } from 'vue'

const props = defineProps<{ state: 'connecting' | 'live' | 'reconnecting' | 'closed' | 'error' }>()

const text = computed(() => ({
  connecting: 'Connecting',
  live: 'Live',
  reconnecting: 'Reconnecting',
  closed: 'Stream ended',
  error: 'Disconnected'
}[props.state]))

const tone = computed(() => ({
  connecting: 'text-muted',
  live: 'text-success',
  reconnecting: 'text-warning',
  closed: 'text-muted',
  error: 'text-error'
}[props.state]))
</script>

<template>
  <span class="apus-value inline-flex items-center gap-1.5 text-xs" :class="tone">
    <span
      class="size-1.5 shrink-0"
      aria-hidden="true"
      :class="{
        'bg-success': state === 'live',
        'bg-warning': state === 'reconnecting',
        'bg-error': state === 'error',
        'bg-muted': state === 'connecting' || state === 'closed'
      }"
    />
    <!-- aria-live, because this changing is itself the news, and a reader watching a render is
         not necessarily watching this corner of the page. -->
    <span aria-live="polite">{{ text }}</span>
  </span>
</template>
