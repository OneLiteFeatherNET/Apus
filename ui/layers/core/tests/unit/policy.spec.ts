import { describe, expect, it } from 'vitest'
import { allowedSourceTypes, forceAllowed, maximumKeepVersions, minimumPollSeconds } from '~/utils/policy'
import type { PolicyEntryResponse } from '~/utils/apiTypes'

function locked(key: string, type: PolicyEntryResponse['type'], value: string): PolicyEntryResponse {
  return { key, type, value, locked: true, enforced: true }
}

describe('policy readers', () => {
  it('returns null when nothing is regulated, so a caller can tell "no rule" from "rule of zero"', () => {
    // An empty allow-list is a real policy ("no source type at all"); undefined must not be
    // confused with it, or the UI would offer nothing to a tenant with no policy whatsoever.
    expect(allowedSourceTypes([])).toBeNull()
    expect(minimumPollSeconds([])).toBeNull()
    expect(maximumKeepVersions([])).toBeNull()
    expect(forceAllowed([])).toBeNull()
  })

  it('reads a locked source type list', () => {
    expect(allowedSourceTypes([locked('source.types.allowed', 'stringList', 's3, push')]))
      .toEqual(['s3', 'push'])
  })

  it('reads an empty locked list as "none allowed", not as "no rule"', () => {
    expect(allowedSourceTypes([locked('source.types.allowed', 'stringList', '')])).toEqual([])
  })

  it('ignores an unlocked entry, because the UI must not disable what the API would accept', () => {
    const advisory: PolicyEntryResponse[] = [
      { key: 'source.types.allowed', type: 'stringList', value: 's3', locked: false, enforced: true }
    ]

    expect(allowedSourceTypes(advisory)).toBeNull()
  })

  it('ignores an entry the API says it does not enforce', () => {
    // The mirror of the rule above: hiding a control the API would happily accept is just as
    // wrong as offering one it refuses, and `enforced` is the API telling us which is which.
    const decorative: PolicyEntryResponse[] = [
      { key: 'source.types.allowed', type: 'stringList', value: 's3', locked: true, enforced: false }
    ]

    expect(allowedSourceTypes(decorative)).toBeNull()
  })

  it('parses durations the way the API does', () => {
    expect(minimumPollSeconds([locked('source.poll.minimum', 'duration', '5m')])).toBe(300)
    expect(minimumPollSeconds([locked('source.poll.minimum', 'duration', '1h30m')])).toBe(5400)
    expect(minimumPollSeconds([locked('source.poll.minimum', 'duration', '45s')])).toBe(45)
  })

  it('treats a value that does not parse as no rule, exactly as the API does', () => {
    expect(minimumPollSeconds([locked('source.poll.minimum', 'duration', 'later')])).toBeNull()
    expect(maximumKeepVersions([locked('source.keepVersions.maximum', 'integer', 'lots')])).toBeNull()
  })

  it('reads the caps and the force-render ban', () => {
    expect(maximumKeepVersions([locked('source.keepVersions.maximum', 'integer', '3')])).toBe(3)
    expect(forceAllowed([locked('render.force.allowed', 'boolean', 'false')])).toBe(false)
    expect(forceAllowed([locked('render.force.allowed', 'boolean', 'true')])).toBe(true)
  })
})
