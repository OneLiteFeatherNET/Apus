<script setup lang="ts">
import { openSseController, type SseController } from '#core/utils/sseController'

/**
 * Live log tail for one render, over `GET /api/renders/{id}/logs`.
 *
 * Started unconditionally, unlike the progress stream: the logs endpoint tails a completed job's
 * log too (RenderStreamController only skips the *watch* half for a terminal render), so this is
 * as useful after a failure as during a run -- which is when people actually read logs.
 *
 * The presentation is LogConsole's; the buffering and cleanup are unchanged from this module's
 * original viewer.
 */
const props = defineProps<{ renderId: string }>()

/** Bounds memory for a render that logs for hours -- keep only the most recent lines. */
const MAX_LINES = 2000

const lines = ref<string[]>([])
const connection = ref<'connecting' | 'live' | 'closed' | 'error'>('connecting')

const api = useApiClient()
let controller: SseController | undefined

function start(): void {
  controller = openSseController<string>(
    (handlers, signal) => api.streamRenderLogs(props.renderId, handlers, signal),
    {
      onMessage: (line) => {
        lines.value.push(line)
        if (lines.value.length > MAX_LINES) {
          lines.value.splice(0, lines.value.length - MAX_LINES)
        }
        connection.value = 'live'
      },
      onError: () => {
        connection.value = 'error'
      },
      onClose: () => {
        if (connection.value !== 'error') connection.value = 'closed'
      }
    }
  )
}

onMounted(start)
onUnmounted(() => controller?.stop())
</script>

<template>
  <div class="flex flex-col gap-2">
    <div class="flex items-center justify-between">
      <SectionLabel as="h2">
        Log
      </SectionLabel>
      <ConnectionState :state="connection" />
    </div>
    <LogConsole :lines="lines" label="Render log" />
    <p v-if="connection === 'error'" class="text-muted text-sm">
      The log stream dropped. Reload the page to reconnect; the lines above are what arrived
      before it did.
    </p>
  </div>
</template>
