/**
 * Storage usage math for the platform dashboard (design spec §11.2: "Mandanten, Quotas mit
 * Verbrauchsanzeige"). Pure TypeScript, no Nuxt/Vue dependency, so it is directly unit-testable
 * -- see tests/unit/platform/storageUsage.spec.ts.
 *
 * IMPORTANT -- read before changing thresholds or wording here: the quota is enforced by Ceph
 * (RGW), not by Apus or this UI (operator/src/main/java/net/onelitefeather/apus/operator/api/
 * TenantSpec.java's `StorageQuota` Javadoc: "Hard storage limit, enforced by Ceph rather than by
 * this operator"). Nothing in this module, or in any component that uses it, may present usage
 * as something the platform-admin can push past the limit from here -- it is an observation of
 * `Tenant.status.storageUsedBytes`, not a control. What this module *is* for: making it obvious
 * when a tenant is close to its limit, because uploads start failing once it is reached (design
 * spec §12: "Speicherlimit erreicht -> RGW-Fehler -> Condition StorageQuotaExceeded, kein
 * Retry").
 */

const QUANTITY_PATTERN = /^(\d+(?:\.\d+)?)\s*([a-zA-Z]*)$/

/**
 * Byte multiplier for a Kubernetes resource quantity suffix -- binary (IEC: `Ki`..`Ei`) and
 * decimal (SI: `k`/`K`..`E`) alike, plus the empty string for a plain byte count. `null` for an
 * unrecognised suffix. A plain `if`/`else` chain rather than a lookup object so the result stays
 * a definite `number`, not `number | undefined`, under this project's `noUncheckedIndexedAccess`.
 */
function unitMultiplier(unit: string): number | null {
  if (unit.length === 0) return 1
  if (unit === 'Ki') return 2 ** 10
  if (unit === 'Mi') return 2 ** 20
  if (unit === 'Gi') return 2 ** 30
  if (unit === 'Ti') return 2 ** 40
  if (unit === 'Pi') return 2 ** 50
  if (unit === 'Ei') return 2 ** 60
  if (unit === 'k' || unit === 'K') return 1e3
  if (unit === 'M') return 1e6
  if (unit === 'G') return 1e9
  if (unit === 'T') return 1e12
  if (unit === 'P') return 1e15
  if (unit === 'E') return 1e18
  return null
}

/**
 * Parses a Kubernetes-style resource quantity string (`TenantResponse.storage.quota`, e.g.
 * `"100Gi"` -- see `TenantSpec.StorageQuota` on the operator side) into a byte count.
 *
 * Returns `null` for anything unparseable -- a quota the UI cannot make sense of must not be
 * silently treated as "0" or "unlimited"; callers surface that as "unknown", same as no usage
 * being reported yet.
 */
export function parseQuotaBytes(quota: string | null | undefined): number | null {
  if (quota == null) return null
  const trimmed = quota.trim()
  if (trimmed.length === 0) return null

  const match = QUANTITY_PATTERN.exec(trimmed)
  if (!match) return null

  const numeric = match[1]
  const unit = match[2]
  if (numeric === undefined || unit === undefined) return null

  const value = Number(numeric)
  if (!Number.isFinite(value)) return null

  const multiplier = unitMultiplier(unit)
  if (multiplier === null) return null

  return value * multiplier
}

const BYTE_UNIT_NAMES = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB', 'EiB']

/** Formats a non-negative byte count as a human-readable IEC size, e.g. `"12.34 GiB"`. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'

  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), BYTE_UNIT_NAMES.length - 1)
  const value = bytes / 1024 ** exponent
  const formatted = exponent === 0 ? String(value) : value.toFixed(2)
  return `${formatted} ${BYTE_UNIT_NAMES[exponent]}`
}

/**
 * `'unknown'` -- usage or quota could not be determined (e.g. `status.storageUsedBytes` has not
 *   been reported yet, see `describeStorageUsage`'s "no usage known" edge case).
 * `'ok'` / `'warning'` / `'critical'` -- below, approaching, and just under the quota.
 * `'over'` -- reported usage is at or above the quota. This is a stale-metrics or edge-timing
 *   observation, not a contradiction: Ceph already refuses further writes at this point (see
 *   this module's own Javadoc), so the UI shows it plainly rather than clamping it to 100%.
 */
export type StorageUsageLevel = 'unknown' | 'ok' | 'warning' | 'critical' | 'over'

export interface StorageUsageSummary {
  readonly usedBytes: number | null
  readonly quotaBytes: number | null
  /** Fraction of quota consumed (e.g. `0.42` for 42%); `null` when usage or quota is unknown. */
  readonly ratio: number | null
  readonly level: StorageUsageLevel
  /** Human-readable usage, or an explanatory placeholder when unknown. */
  readonly usedLabel: string
  /** Human-readable quota, or the raw (unparseable) string when it could not be interpreted. */
  readonly quotaLabel: string
}

/** At or above this fraction of quota, usage is flagged as approaching the limit. */
const WARNING_RATIO = 0.8
/** At or above this fraction of quota, usage is flagged as critically close to the limit. */
const CRITICAL_RATIO = 0.95

/**
 * Builds a display-ready summary of one tenant's storage usage against its quota. Handles the
 * two edge cases that matter most for an operator glancing at the dashboard: no usage reported
 * yet (`usedBytes` is `null` -- the tenant may be brand new, or the operator has not synced a
 * status yet) and usage at or beyond the quota (see `StorageUsageLevel`'s `'over'` case).
 */
export function describeStorageUsage(
  usedBytes: number | null | undefined,
  quota: string | null | undefined
): StorageUsageSummary {
  const quotaBytes = parseQuotaBytes(quota)
  const used = usedBytes ?? null

  if (used === null || quotaBytes === null || quotaBytes <= 0) {
    return {
      usedBytes: used,
      quotaBytes,
      ratio: null,
      level: 'unknown',
      usedLabel: used === null ? 'Not yet reported' : formatBytes(used),
      quotaLabel: quotaBytes === null ? (quota?.trim() || 'Not set') : formatBytes(quotaBytes)
    }
  }

  const ratio = used / quotaBytes
  let level: StorageUsageLevel
  if (ratio >= 1) {
    level = 'over'
  } else if (ratio >= CRITICAL_RATIO) {
    level = 'critical'
  } else if (ratio >= WARNING_RATIO) {
    level = 'warning'
  } else {
    level = 'ok'
  }

  return {
    usedBytes: used,
    quotaBytes,
    ratio,
    level,
    usedLabel: formatBytes(used),
    quotaLabel: formatBytes(quotaBytes)
  }
}

/** Maps a {@link StorageUsageLevel} to a Nuxt UI color token, for progress bars and badges. */
export function storageUsageColor(level: StorageUsageLevel): 'neutral' | 'success' | 'warning' | 'error' {
  switch (level) {
    case 'ok':
      return 'success'
    case 'warning':
      return 'warning'
    case 'critical':
    case 'over':
      return 'error'
    case 'unknown':
    default:
      return 'neutral'
  }
}
