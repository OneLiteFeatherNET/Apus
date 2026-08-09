/**
 * Lifecycle wiring shared by every live SSE view on the tenant dashboard (render progress,
 * render logs -- design spec §11.2: "Schließe die Ereignisströme wieder, wenn eine Ansicht
 * verlassen wird oder der Render terminal ist"). Kept outside `app/utils/`/`app/composables/`
 * (tenant-agent file-scope restriction) but deliberately framework-free itself, so the cleanup
 * behaviour is unit-testable without mounting a component -- see
 * tests/unit/tenant/sseController.spec.ts. The Vue components in this directory only call
 * `openSseController` from `onMounted` and its returned `stop()` from `onUnmounted`.
 */
import type { SseHandlers } from '~/utils/apiClient'
import { isRenderTerminal } from './renderProgress'

export interface SseController {
  /** Aborts the underlying stream. Safe to call more than once (`AbortController.abort()` is
   * idempotent) and safe to call after the stream already closed itself. */
  stop: () => void
}

/**
 * Starts one `ApusApiClient` SSE method (`streamRenderEvents`/`streamRenderLogs`) and returns a
 * handle to close it. `open` is the exact shape both client methods share: given handlers and an
 * `AbortSignal`, return the promise that resolves once the stream ends.
 */
export function openSseController<T>(
  open: (handlers: SseHandlers<T>, signal: AbortSignal) => Promise<void>,
  handlers: SseHandlers<T>
): SseController {
  const controller = new AbortController()

  open(handlers, controller.signal).catch(() => {
    // A rejection here means the stream failed to open or broke mid-stream; `apiClient.ts`'s
    // `streamSse` already routed that same error to `handlers.onError` before rethrowing it (or,
    // if we called `stop()` ourselves, it's the expected abort). Either way, nothing further to
    // do -- this catch exists only to keep that rejection from becoming an unhandled promise.
  })

  return {
    stop: () => controller.abort()
  }
}

/**
 * Wraps `handlers.onMessage` so the stream is stopped the moment a terminal render phase is
 * observed, instead of relying solely on the api module closing its end (which it does too, see
 * `RenderStreamController`'s Javadoc -- this is defence in depth, not a workaround for a gap).
 */
export function withAutoStopOnTerminal<T extends { phase: string | null | undefined }>(
  handlers: SseHandlers<T>,
  stop: () => void
): SseHandlers<T> {
  return {
    ...handlers,
    onMessage: (event: T) => {
      handlers.onMessage(event)
      if (isRenderTerminal(event.phase)) {
        stop()
      }
    }
  }
}
