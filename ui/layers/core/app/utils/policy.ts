/**
 * Reading a tenant's policy the way the API enforces it.
 *
 * Two filters apply to every reader here, and both matter:
 *
 * - **`locked`** — an unlocked entry is the platform's recommendation, and the API accepts values
 *   that deviate from it. Disabling a control over one would refuse in the interface what the
 *   backend allows.
 * - **`enforced`** — the API computes this from its own registry. An entry it does not enforce
 *   changes nothing, no matter how firmly it is locked, so the interface must not act on it
 *   either.
 *
 * Together they say: the UI hides only what the API would actually refuse. It is convenience, in
 * the same sense as `role.ts` — the api module remains the enforcement point.
 *
 * Deliberately plain TypeScript, no Nuxt: this is the logic the source flow depends on, and it
 * unit-tests without a browser (ui/README.md, "Why plain Vitest").
 */
import type { PolicyEntryResponse } from '#core/utils/apiTypes'

/** Mirrors PolicyType.DURATION's pattern in the api module -- Go spelling, not ISO-8601. */
const DURATION = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/

function enforcedValue(
  policy: PolicyEntryResponse[],
  key: string,
  type: PolicyEntryResponse['type']
): string | null {
  const entry = policy.find(
    candidate => candidate.key === key && candidate.locked && candidate.enforced && candidate.type === type
  )
  return entry ? entry.value : null
}

/** The source types a tenant may create, or `null` when unregulated. An empty array means none. */
export function allowedSourceTypes(policy: PolicyEntryResponse[]): string[] | null {
  const raw = enforcedValue(policy, 'source.types.allowed', 'stringList')
  if (raw === null) return null
  return raw.split(',').map(part => part.trim()).filter(part => part.length > 0)
}

/** The shortest permitted poll interval in seconds, or `null` when unregulated. */
export function minimumPollSeconds(policy: PolicyEntryResponse[]): number | null {
  const raw = enforcedValue(policy, 'source.poll.minimum', 'duration')
  if (raw === null) return null
  return parseDurationSeconds(raw)
}

/** The most snapshots a tenant may keep, or `null` when unregulated. */
export function maximumKeepVersions(policy: PolicyEntryResponse[]): number | null {
  const raw = enforcedValue(policy, 'source.keepVersions.maximum', 'integer')
  if (raw === null) return null
  const parsed = Number.parseInt(raw.trim(), 10)
  // A value that does not parse is no rule at all -- the same call the API makes, so the two
  // cannot disagree about whether something is regulated.
  return Number.isSafeInteger(parsed) && String(parsed) === raw.trim() ? parsed : null
}

/** Whether forced renders are permitted, or `null` when unregulated. */
export function forceAllowed(policy: PolicyEntryResponse[]): boolean | null {
  const raw = enforcedValue(policy, 'render.force.allowed', 'boolean')
  if (raw === null) return null
  const trimmed = raw.trim()
  if (trimmed !== 'true' && trimmed !== 'false') return null
  return trimmed === 'true'
}

/** Go-style duration to seconds, or `null` when it does not parse. */
export function parseDurationSeconds(value: string): number | null {
  const match = DURATION.exec(value.trim())
  // Every group is optional, so the pattern also matches "" and would read a bare "5" as zero.
  if (!match || (match[1] === undefined && match[2] === undefined && match[3] === undefined)) {
    return null
  }
  const hours = match[1] ? Number.parseInt(match[1], 10) : 0
  const minutes = match[2] ? Number.parseInt(match[2], 10) : 0
  const seconds = match[3] ? Number.parseInt(match[3], 10) : 0
  return hours * 3600 + minutes * 60 + seconds
}
