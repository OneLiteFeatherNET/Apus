import { describe, expect, it } from 'vitest'
import { describeRenderProgress, formatDuration, isRenderTerminal } from '../../../app/utils/renderProgress'

describe('isRenderTerminal', () => {
  it('is true for Succeeded and Failed', () => {
    expect(isRenderTerminal('Succeeded')).toBe(true)
    expect(isRenderTerminal('Failed')).toBe(true)
  })

  it('is false for in-progress phases', () => {
    expect(isRenderTerminal('Pending')).toBe(false)
    expect(isRenderTerminal('Syncing')).toBe(false)
    expect(isRenderTerminal('Rendering')).toBe(false)
    expect(isRenderTerminal('Finalizing')).toBe(false)
  })

  it('is false for null/undefined rather than throwing', () => {
    expect(isRenderTerminal(null)).toBe(false)
    expect(isRenderTerminal(undefined)).toBe(false)
  })
})

describe('formatDuration', () => {
  it('formats sub-minute durations as seconds only', () => {
    expect(formatDuration(0)).toBe('0s')
    expect(formatDuration(45)).toBe('45s')
  })

  it('formats sub-hour durations as minutes and seconds', () => {
    expect(formatDuration(65)).toBe('1m 5s')
    expect(formatDuration(600)).toBe('10m 0s')
  })

  it('formats durations of an hour or more as hours and minutes, dropping seconds', () => {
    expect(formatDuration(3600)).toBe('1h 0m')
    expect(formatDuration(8130)).toBe('2h 15m')
  })

  it('rounds to the nearest second', () => {
    expect(formatDuration(44.6)).toBe('45s')
  })

  it('clamps a negative input to zero rather than producing a negative label', () => {
    expect(formatDuration(-5)).toBe('0s')
  })
})

describe('describeRenderProgress', () => {
  it('reports a known percent and eta as-is when non-negative', () => {
    const display = describeRenderProgress({ phase: 'Rendering', percent: 42, etaSeconds: 90, degraded: false })

    expect(display.percentKnown).toBe(true)
    expect(display.percent).toBe(42)
    expect(display.etaKnown).toBe(true)
    expect(display.etaLabel).toBe('1m 30s')
    expect(display.degraded).toBe(false)
    expect(display.terminal).toBe(false)
  })

  it('reports percent/eta as unknown -- never a fabricated 0 or invented eta -- when the api sends -1', () => {
    const display = describeRenderProgress({ phase: 'Rendering', percent: -1, etaSeconds: -1, degraded: true })

    expect(display.percentKnown).toBe(false)
    expect(display.percent).toBeNull()
    expect(display.etaKnown).toBe(false)
    expect(display.etaLabel).toBeNull()
    expect(display.degraded).toBe(true)
  })

  it('clamps an out-of-range percent into [0, 100] rather than passing it straight to a progress bar', () => {
    expect(describeRenderProgress({ phase: null, percent: 150, etaSeconds: 0, degraded: false }).percent).toBe(100)
    expect(describeRenderProgress({ phase: null, percent: 0, etaSeconds: 0, degraded: false }).percent).toBe(0)
  })

  it('treats percent/eta unknown and degraded as independent signals', () => {
    // A measurement can degrade on just one of the two values -- both are checked separately,
    // not inferred from `degraded` alone.
    const display = describeRenderProgress({ phase: 'Rendering', percent: 60, etaSeconds: -1, degraded: true })

    expect(display.percentKnown).toBe(true)
    expect(display.percent).toBe(60)
    expect(display.etaKnown).toBe(false)
    expect(display.degraded).toBe(true)
  })

  it('flags terminal phases', () => {
    expect(describeRenderProgress({ phase: 'Succeeded', percent: 100, etaSeconds: 0, degraded: false }).terminal).toBe(true)
    expect(describeRenderProgress({ phase: 'Failed', percent: -1, etaSeconds: -1, degraded: true }).terminal).toBe(true)
    expect(describeRenderProgress({ phase: 'Rendering', percent: 50, etaSeconds: 10, degraded: false }).terminal).toBe(false)
  })
})
