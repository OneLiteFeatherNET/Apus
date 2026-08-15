/**
 * Pure formatting/decision logic for render progress display (design spec §11.2: "Renders...
 * percentage progress with estimated remaining time for the render in progress, live").
 *
 * Framework-free so it stays unit-testable without mounting a component -- see
 * tests/unit/tenant/renderProgress.spec.ts.
 *
 * The api module sends `-1` for `percent`/`etaSeconds` together with `degraded: true` when its
 * progress *measurement* has degraded (design spec: this can happen without the render itself
 * being at risk) -- see `BlueMapRenderProgressResponse`/`RenderProgressEvent` in
 * app/utils/apiTypes.ts. The one binding rule here: never turn an unknown value into a fabricated
 * number (a bar frozen at 0%, an invented ETA) -- report it as unknown instead.
 */
import type { RenderProgressEvent } from './apiTypes'

/** Mirrors `RenderPhases.TERMINAL` (api module, api/src/main/java/.../events/RenderPhases.java)
 * exactly -- the only two phases after which a render's progress stream will not change again. */
const TERMINAL_PHASES: ReadonlySet<string> = new Set(['Succeeded', 'Failed'])

/** Whether `phase` is terminal, mirroring `RenderPhases.isTerminal` on the api module. */
export function isRenderTerminal(phase: string | null | undefined): boolean {
  return phase != null && TERMINAL_PHASES.has(phase)
}

/** The subset of `BlueMapRenderProgressResponse`/`RenderProgressEvent` this module needs. */
export interface RenderProgressSnapshot {
  phase: string | null
  percent: number
  etaSeconds: number
  degraded: boolean
}

/** A `RenderProgressEvent` (SSE payload) is already a valid snapshot -- same shape. */
export type RenderProgressSseSnapshot = RenderProgressEvent

/** Ready-to-render form of a {@link RenderProgressSnapshot}: no `-1` sentinels left for a
 * template to accidentally display. */
export interface RenderProgressDisplay {
  /** `false` when the api module reports `percent < 0` -- show this honestly, never as 0%. */
  readonly percentKnown: boolean
  /** Clamped to [0, 100] when known; `null` when not -- feed straight to a progress bar. */
  readonly percent: number | null
  /** `false` when the api module reports `etaSeconds < 0`. */
  readonly etaKnown: boolean
  /** Human-readable remaining time (e.g. `"2h 15m"`), or `null` when unknown. */
  readonly etaLabel: string | null
  /** Whether the *measurement* has degraded -- render may still be healthy, see module doc. */
  readonly degraded: boolean
  readonly terminal: boolean
}

/** Turns a raw snapshot into display-ready values -- the one place `-1` gets interpreted. */
export function describeRenderProgress(snapshot: RenderProgressSnapshot): RenderProgressDisplay {
  const percentKnown = snapshot.percent >= 0
  const etaKnown = snapshot.etaSeconds >= 0
  return {
    percentKnown,
    percent: percentKnown ? Math.min(100, Math.max(0, snapshot.percent)) : null,
    etaKnown,
    etaLabel: etaKnown ? formatDuration(snapshot.etaSeconds) : null,
    degraded: snapshot.degraded,
    terminal: isRenderTerminal(snapshot.phase)
  }
}

/**
 * Formats a non-negative duration in seconds as a short human-readable label. Callers are
 * expected to have already checked the value is known (see {@link describeRenderProgress}) --
 * this function has no "unknown" case of its own, on purpose, so that decision cannot be made
 * twice in two different ways.
 */
export function formatDuration(totalSeconds: number): string {
  const seconds = Math.max(0, Math.round(totalSeconds))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const remainingSeconds = seconds % 60

  if (hours > 0) {
    return `${hours}h ${minutes}m`
  }
  if (minutes > 0) {
    return `${minutes}m ${remainingSeconds}s`
  }
  return `${remainingSeconds}s`
}
