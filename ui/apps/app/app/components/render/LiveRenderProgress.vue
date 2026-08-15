<script setup lang="ts">
import type { RenderProgressEvent } from '#core/utils/apiTypes'
import { describeRenderProgress, isRenderTerminal, type RenderProgressSnapshot } from '#core/utils/renderProgress'
import { openSseController, withAutoStopOnTerminal, type SseController } from '#core/utils/sseController'

/**
 * Live percent and ETA for one render, over `GET /api/renders/{id}/events`. The stream wiring is
 * unchanged from what this module has always done -- subscribe on mount, close on unmount or on a
 * terminal phase, never open one for a render that is already finished (the api module would end
 * it immediately anyway; see RenderStreamController's Javadoc).
 *
 * What is new is that the connection has a visible state. A dropped stream used to leave a bar
 * that had simply stopped moving, which is indistinguishable from a slow render -- so a reader
 * could wait a long time for a number that would never change again.
 */
const props = defineProps<{
  renderId: string
  initial: RenderProgressSnapshot
}>()

const snapshot = ref<RenderProgressSnapshot>({ ...props.initial })
const connection = ref<'connecting' | 'live' | 'closed' | 'error'>('connecting')

const display = computed(() => describeRenderProgress(snapshot.value))

const api = useApiClient()
let controller: SseController | undefined

function start(): void {
  if (isRenderTerminal(snapshot.value.phase)) {
    connection.value = 'closed'
    return
  }
  controller = openSseController<RenderProgressEvent>(
    (handlers, signal) => api.streamRenderEvents(props.renderId, handlers, signal),
    withAutoStopOnTerminal(
      {
        onMessage: (event) => {
          snapshot.value = {
            phase: event.phase,
            percent: event.percent,
            etaSeconds: event.etaSeconds,
            degraded: event.degraded
          }
          connection.value = 'live'
        },
        onError: () => {
          connection.value = 'error'
        },
        onClose: () => {
          if (connection.value !== 'error') connection.value = 'closed'
        }
      },
      () => controller?.stop()
    )
  )
}

onMounted(start)
onUnmounted(() => controller?.stop())
</script>

<template>
  <div class="flex flex-col gap-3">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <!-- No meter at all when the percentage is unknown. The api module sends -1 rather than 0
           for exactly this case (see renderProgress.ts), and an empty row of cells would turn
           "we cannot tell" into the far more alarming "nothing has happened". -->
      <CellMeter
        v-if="display.percentKnown && display.percent !== null"
        :percent="display.percent"
        :live="connection === 'live'"
        :label="`Render progress: ${display.percent}%`"
      />
      <span v-else class="apus-value text-muted text-sm">Progress unknown</span>
      <ConnectionState :state="connection" />
    </div>

    <p role="status" class="text-muted text-sm">
      <span v-if="display.etaKnown">About {{ display.etaLabel }} remaining.</span>
      <span v-else-if="!display.terminal">Remaining time unknown.</span>
    </p>

    <p v-if="display.degraded" class="border-warning/40 bg-warning/5 text-muted border p-3 text-sm">
      Progress reporting is degraded, so the percentage may lag or stall. The render itself is not
      at risk.
    </p>

    <p v-if="connection === 'error'" class="border-error/40 bg-error/5 text-muted border p-3 text-sm">
      The live connection dropped, so the value above may be stale. Reload the page to reconnect.
    </p>
  </div>
</template>
