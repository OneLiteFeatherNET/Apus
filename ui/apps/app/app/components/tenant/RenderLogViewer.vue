<script setup lang="ts">
import { openSseController, type SseController } from '#core/utils/sseController'

/**
 * Live log tail for one render (design spec §11.2: "Log view for a render, likewise
 * via SSE"), via `GET /api/renders/{id}/logs`. Started unconditionally on mount -- unlike
 * RenderProgressBar, the logs endpoint tails a completed job's log too (see
 * RenderStreamController's Javadoc: it only skips the *watch* half for a terminal render, not the
 * log tail itself), so this is useful for a finished render as well as a running one. Closed on
 * unmount either way, per the same "don't leave a stream open behind a closed view" rule.
 */
const props = defineProps<{ renderId: string }>()

/** Bounds memory for a render that logs for hours -- keep only the most recent lines. */
const MAX_LINES = 2000

const lines = ref<string[]>([])
const streamError = ref(false)
const streamClosed = ref(false)
const logRegion = ref<HTMLElement | null>(null)

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
        streamError.value = false
        nextTick(scrollToEnd)
      },
      onError: () => {
        streamError.value = true
      },
      onClose: () => {
        streamClosed.value = true
      }
    }
  )
}

function scrollToEnd(): void {
  const el = logRegion.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

onMounted(start)
onUnmounted(() => controller?.stop())
</script>

<template>
  <div class="space-y-2">
    <pre
      ref="logRegion"
      role="log"
      aria-label="Render log output"
      class="max-h-96 overflow-y-auto rounded bg-elevated p-3 text-xs whitespace-pre-wrap"
    ><span v-if="lines.length === 0" class="text-muted">No log lines yet.</span><template v-for="(line, index) in lines" :key="index">{{ line }}
</template></pre>
    <p v-if="streamClosed" class="text-sm text-muted">
      Log stream closed.
    </p>
    <UAlert
      v-if="streamError"
      color="error"
      variant="subtle"
      title="Live log stream interrupted"
      description="Could not keep the log stream open. Reload to try again."
    />
  </div>
</template>
