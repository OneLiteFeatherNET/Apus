<script setup lang="ts">
import type { RenderProgressEvent } from '#core/utils/apiTypes'
import { describeRenderProgress, isRenderTerminal, type RenderProgressSnapshot } from '#core/utils/renderProgress'
import { openSseController, withAutoStopOnTerminal, type SseController } from '#core/utils/sseController'

/**
 * Live percent/ETA for one render (design spec §11.2). Subscribes to
 * `GET /api/renders/{id}/events` on mount and closes the stream again on unmount or once the
 * render reaches a terminal phase -- see sseController.ts for the shared cleanup wiring this is
 * built on. `initial` seeds the display before the first live event arrives (and is all that's
 * shown for an already-terminal render, which never opens a stream at all).
 */
const props = defineProps<{
  renderId: string
  initial: RenderProgressSnapshot
}>()

const snapshot = ref<RenderProgressSnapshot>({ ...props.initial })
const streamError = ref(false)

const display = computed(() => describeRenderProgress(snapshot.value))

const progressAriaLabel = computed(() =>
  display.value.percentKnown ? `Render progress: ${display.value.percent}%` : 'Render progress: unknown'
)

const api = useApiClient()
let controller: SseController | undefined

function start(): void {
  if (isRenderTerminal(snapshot.value.phase)) {
    // Nothing left to watch -- the api module would end the stream immediately anyway (see
    // RenderStreamController's Javadoc), so don't bother opening one.
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
          streamError.value = false
        },
        onError: () => {
          streamError.value = true
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
  <div class="space-y-2">
    <UProgress :model-value="display.percent" :aria-label="progressAriaLabel" />
    <p role="status" class="text-sm text-muted">
      <span v-if="display.percentKnown">{{ display.percent }}%</span>
      <span v-else>Progress unknown</span>
      <span v-if="display.etaKnown"> — about {{ display.etaLabel }} remaining</span>
      <span v-else-if="!display.terminal"> — remaining time unknown</span>
    </p>
    <UBadge v-if="display.degraded" color="warning" variant="subtle">
      Progress reporting degraded — the render itself is not at risk
    </UBadge>
    <UAlert
      v-if="streamError"
      color="error"
      variant="subtle"
      title="Live updates interrupted"
      description="Could not keep the progress stream open. The value shown may be stale; reload to try again."
    />
  </div>
</template>
