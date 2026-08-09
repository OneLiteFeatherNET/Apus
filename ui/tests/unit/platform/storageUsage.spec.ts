import { describe, expect, it } from 'vitest'
import {
  describeStorageUsage,
  formatBytes,
  parseQuotaBytes,
  storageUsageColor
} from '../../../app/components/platform/storageUsage'

describe('parseQuotaBytes', () => {
  it('parses binary (IEC) suffixes', () => {
    expect(parseQuotaBytes('100Gi')).toBe(100 * 2 ** 30)
    expect(parseQuotaBytes('1Ki')).toBe(2 ** 10)
    expect(parseQuotaBytes('2Ti')).toBe(2 * 2 ** 40)
  })

  it('parses decimal (SI) suffixes', () => {
    expect(parseQuotaBytes('5M')).toBe(5 * 1e6)
    expect(parseQuotaBytes('1k')).toBe(1e3)
  })

  it('parses a plain number as bytes', () => {
    expect(parseQuotaBytes('2048')).toBe(2048)
  })

  it('parses fractional quantities', () => {
    expect(parseQuotaBytes('1.5Gi')).toBe(1.5 * 2 ** 30)
  })

  it('returns null for null, undefined, or blank input', () => {
    expect(parseQuotaBytes(null)).toBeNull()
    expect(parseQuotaBytes(undefined)).toBeNull()
    expect(parseQuotaBytes('   ')).toBeNull()
  })

  it('returns null for an unrecognised suffix', () => {
    expect(parseQuotaBytes('100Xi')).toBeNull()
  })

  it('returns null for non-numeric input', () => {
    expect(parseQuotaBytes('not-a-quantity')).toBeNull()
  })
})

describe('formatBytes', () => {
  it('formats zero and negative input as "0 B"', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(-5)).toBe('0 B')
  })

  it('formats sub-kibibyte values in bytes', () => {
    expect(formatBytes(512)).toBe('512 B')
  })

  it('picks the largest unit that keeps the value >= 1', () => {
    expect(formatBytes(2 ** 30)).toBe('1.00 GiB')
    expect(formatBytes(1.5 * 2 ** 30)).toBe('1.50 GiB')
  })
})

describe('describeStorageUsage', () => {
  it('reports "unknown" when no usage has been observed yet', () => {
    // Edge case called out in the task brief: a brand-new tenant, or one whose operator has
    // not synced a status yet.
    const summary = describeStorageUsage(null, '100Gi')

    expect(summary.level).toBe('unknown')
    expect(summary.ratio).toBeNull()
    expect(summary.usedLabel).toBe('Not yet reported')
    expect(summary.quotaLabel).toBe('100.00 GiB')
  })

  it('reports "unknown" when the quota cannot be parsed', () => {
    const summary = describeStorageUsage(1024, 'not-a-quantity')

    expect(summary.level).toBe('unknown')
    expect(summary.ratio).toBeNull()
    expect(summary.quotaLabel).toBe('not-a-quantity')
  })

  it('reports "unknown" when the quota is null', () => {
    const summary = describeStorageUsage(1024, null)

    expect(summary.level).toBe('unknown')
    expect(summary.quotaLabel).toBe('Not set')
  })

  it('reports "ok" comfortably below the warning threshold', () => {
    const summary = describeStorageUsage(10 * 2 ** 30, '100Gi')

    expect(summary.level).toBe('ok')
    expect(summary.ratio).toBeCloseTo(0.1)
  })

  it('reports "warning" at 80% and above', () => {
    const summary = describeStorageUsage(80 * 2 ** 30, '100Gi')

    expect(summary.level).toBe('warning')
  })

  it('reports "critical" at 95% and above', () => {
    const summary = describeStorageUsage(95 * 2 ** 30, '100Gi')

    expect(summary.level).toBe('critical')
  })

  it('reports "over" when usage is at or beyond the quota', () => {
    // Edge case called out in the task brief: Ceph enforces the limit, not this UI, so the
    // dashboard must be able to show usage that has reached or (per stale metrics) exceeded it.
    const atLimit = describeStorageUsage(100 * 2 ** 30, '100Gi')
    const beyondLimit = describeStorageUsage(120 * 2 ** 30, '100Gi')

    expect(atLimit.level).toBe('over')
    expect(beyondLimit.level).toBe('over')
    expect(beyondLimit.ratio).toBeCloseTo(1.2)
  })
})

describe('storageUsageColor', () => {
  it('maps each level to the expected color token', () => {
    expect(storageUsageColor('unknown')).toBe('neutral')
    expect(storageUsageColor('ok')).toBe('success')
    expect(storageUsageColor('warning')).toBe('warning')
    expect(storageUsageColor('critical')).toBe('error')
    expect(storageUsageColor('over')).toBe('error')
  })
})
