import { describe, expect, it, vi } from 'vitest'
import { openSseController, withAutoStopOnTerminal } from '../../../app/components/tenant/sseController'
import type { SseHandlers } from '../../../app/utils/apiClient'

describe('openSseController', () => {
  it('calls open with the handlers and a fresh, not-yet-aborted signal', () => {
    const open = vi.fn().mockResolvedValue(undefined)
    const handlers: SseHandlers<string> = { onMessage: vi.fn() }

    openSseController(open, handlers)

    expect(open).toHaveBeenCalledTimes(1)
    const [passedHandlers, signal] = open.mock.calls[0] as [SseHandlers<string>, AbortSignal]
    expect(passedHandlers).toBe(handlers)
    expect(signal.aborted).toBe(false)
  })

  it('aborts the signal passed to open() when stop() is called', () => {
    const open = vi.fn().mockResolvedValue(undefined)
    const controller = openSseController(open, { onMessage: vi.fn() })

    const [, signal] = open.mock.calls[0] as [SseHandlers<string>, AbortSignal]
    expect(signal.aborted).toBe(false)

    controller.stop()

    expect(signal.aborted).toBe(true)
  })

  it('tolerates stop() being called more than once', () => {
    const open = vi.fn().mockResolvedValue(undefined)
    const controller = openSseController(open, { onMessage: vi.fn() })

    expect(() => {
      controller.stop()
      controller.stop()
    }).not.toThrow()
  })

  it('swallows a rejection from open() instead of producing an unhandled promise rejection', async () => {
    const open = vi.fn().mockRejectedValue(new Error('stream failed'))

    expect(() => openSseController(open, { onMessage: vi.fn() })).not.toThrow()
    // Let the rejected promise's microtask settle; if the catch in openSseController were
    // missing, vitest would report an unhandled rejection for this test.
    await new Promise((resolve) => setTimeout(resolve, 0))
  })
})

describe('withAutoStopOnTerminal', () => {
  it('always forwards the event to the wrapped onMessage', () => {
    const onMessage = vi.fn()
    const stop = vi.fn()
    const wrapped = withAutoStopOnTerminal({ onMessage }, stop)

    wrapped.onMessage({ phase: 'Rendering' })

    expect(onMessage).toHaveBeenCalledWith({ phase: 'Rendering' })
  })

  it('does not stop the stream for a non-terminal phase', () => {
    const stop = vi.fn()
    const wrapped = withAutoStopOnTerminal({ onMessage: vi.fn() }, stop)

    wrapped.onMessage({ phase: 'Rendering' })

    expect(stop).not.toHaveBeenCalled()
  })

  it('stops the stream once a terminal phase is observed', () => {
    const stop = vi.fn()
    const wrapped = withAutoStopOnTerminal({ onMessage: vi.fn() }, stop)

    wrapped.onMessage({ phase: 'Succeeded' })

    expect(stop).toHaveBeenCalledTimes(1)
  })

  it('preserves onError/onClose from the wrapped handlers unchanged', () => {
    const onError = vi.fn()
    const onClose = vi.fn()
    const wrapped = withAutoStopOnTerminal({ onMessage: vi.fn(), onError, onClose }, vi.fn())

    expect(wrapped.onError).toBe(onError)
    expect(wrapped.onClose).toBe(onClose)
  })
})
