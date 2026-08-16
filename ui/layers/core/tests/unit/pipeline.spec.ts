import { describe, expect, it } from 'vitest'
import { cellsFilled, displayPercent } from '~/utils/pipeline'

describe('displayPercent', () => {
  it('agrees with the cells at both ends', () => {
    // The bug this exists to prevent: cells leave one empty at 99.6 while the readout prints
    // "100%", so the meter contradicts itself and one of the two has lied to the reader.
    expect(displayPercent(99.6)).toBe(99)
    expect(displayPercent(0.4)).toBe(1)
    expect(displayPercent(0)).toBe(0)
    expect(displayPercent(100)).toBe(100)
  })

  it('rounds normally in between', () => {
    expect(displayPercent(37.4)).toBe(37)
    expect(displayPercent(37.6)).toBe(38)
  })

  it('clamps out-of-range and non-numeric input', () => {
    expect(displayPercent(-2)).toBe(0)
    expect(displayPercent(140)).toBe(100)
    expect(displayPercent(Number.NaN)).toBe(0)
  })
})

describe('cellsFilled', () => {
  it('fills none at zero and all at a hundred', () => {
    expect(cellsFilled(0, 24)).toBe(0)
    expect(cellsFilled(100, 24)).toBe(24)
  })

  it('rounds to the nearest cell', () => {
    expect(cellsFilled(50, 24)).toBe(12)
    expect(cellsFilled(52, 24)).toBe(12)
    expect(cellsFilled(54, 24)).toBe(13)
  })

  it('never shows a full meter for work that is not finished', () => {
    // The lie that matters. A full meter beside a still-running render reads as "your map is
    // ready" and sends someone looking for a URL that does not exist yet.
    expect(cellsFilled(99.6, 24)).toBe(23)
    expect(cellsFilled(99.99, 8)).toBe(7)
  })

  it('never shows an empty meter for work that has started', () => {
    // The mirror case: 0.4% is not nothing, and an empty meter reads as "stuck".
    expect(cellsFilled(0.4, 24)).toBe(1)
    expect(cellsFilled(0.01, 8)).toBe(1)
  })

  it('clamps values outside the range rather than overflowing the row', () => {
    expect(cellsFilled(-5, 24)).toBe(0)
    expect(cellsFilled(140, 24)).toBe(24)
  })

  it('treats a non-numeric percentage as no progress rather than rendering NaN cells', () => {
    expect(cellsFilled(Number.NaN, 24)).toBe(0)
    expect(cellsFilled(Number.POSITIVE_INFINITY, 24)).toBe(24)
  })

  it('handles a degenerate cell count', () => {
    expect(cellsFilled(50, 0)).toBe(0)
    expect(cellsFilled(50, -3)).toBe(0)
  })

  it('still distinguishes started from finished on a single-cell meter', () => {
    // With one cell the two guards collide: min(cells - 1, ...) is 0 while max(1, ...) is 1.
    // Finished must win, or a completed render on a compact row would show as empty forever.
    expect(cellsFilled(100, 1)).toBe(1)
    expect(cellsFilled(50, 1)).toBe(0)
  })
})
