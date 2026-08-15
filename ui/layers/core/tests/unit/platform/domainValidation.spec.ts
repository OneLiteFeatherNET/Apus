import { describe, expect, it } from 'vitest'
import { validateAllowedDomain, validateAllowedDomains } from '../../../app/utils/domainValidation'

describe('validateAllowedDomain', () => {
  it('accepts a plain hostname', () => {
    expect(validateAllowedDomain('maps.friends.example.net')).toEqual({ valid: true, error: null })
  })

  it('accepts a single leading wildcard label', () => {
    expect(validateAllowedDomain('*.friends.example.net')).toEqual({ valid: true, error: null })
  })

  it('trims surrounding whitespace before validating', () => {
    expect(validateAllowedDomain('  maps.example.net  ')).toEqual({ valid: true, error: null })
  })

  it('rejects an empty or blank entry', () => {
    expect(validateAllowedDomain('').valid).toBe(false)
    expect(validateAllowedDomain('   ').valid).toBe(false)
  })

  it('rejects a bare "*" with an explanation of what it would grant', () => {
    // The one rule this module exists for: a bare "*" would let this tenant claim every
    // hostname on the platform -- exactly the hole Phase 3 closed.
    const result = validateAllowedDomain('*')

    expect(result.valid).toBe(false)
    expect(result.error).toMatch(/every hostname/)
  })

  it('rejects a wildcard that is not the leading label', () => {
    expect(validateAllowedDomain('sub.*.example.net').valid).toBe(false)
    expect(validateAllowedDomain('example.*').valid).toBe(false)
  })

  it('rejects a lone wildcard with no domain to scope it', () => {
    expect(validateAllowedDomain('*.').valid).toBe(false)
  })

  it('rejects whitespace inside the value', () => {
    expect(validateAllowedDomain('maps example.net').valid).toBe(false)
  })

  it('rejects a value carrying a scheme, path, or port', () => {
    expect(validateAllowedDomain('https://maps.example.net').valid).toBe(false)
    expect(validateAllowedDomain('maps.example.net/path').valid).toBe(false)
    expect(validateAllowedDomain('maps.example.net:8080').valid).toBe(false)
  })

  it('rejects empty labels from consecutive or leading/trailing dots', () => {
    expect(validateAllowedDomain('maps..example.net').valid).toBe(false)
    expect(validateAllowedDomain('.maps.example.net').valid).toBe(false)
    expect(validateAllowedDomain('maps.example.net.').valid).toBe(false)
  })

  it('rejects a label starting or ending with a hyphen', () => {
    expect(validateAllowedDomain('-maps.example.net').valid).toBe(false)
    expect(validateAllowedDomain('maps-.example.net').valid).toBe(false)
  })

  it('rejects a label over 63 characters', () => {
    const tooLong = 'a'.repeat(64)
    expect(validateAllowedDomain(`${tooLong}.example.net`).valid).toBe(false)
  })

  it('accepts a single-label hostname', () => {
    expect(validateAllowedDomain('localhost')).toEqual({ valid: true, error: null })
  })
})

describe('validateAllowedDomains', () => {
  it('accepts an empty list', () => {
    // Per TenantSpec.Hosting's Javadoc: empty means "hosting not yet allowed", a valid state,
    // not an error.
    expect(validateAllowedDomains([])).toEqual({ valid: true, error: null })
  })

  it('accepts a list of distinct valid domains', () => {
    expect(validateAllowedDomains(['maps.a.example.net', '*.b.example.net'])).toEqual({ valid: true, error: null })
  })

  it('rejects the list as soon as one entry is invalid', () => {
    const result = validateAllowedDomains(['maps.example.net', '*'])

    expect(result.valid).toBe(false)
    expect(result.error).toMatch(/every hostname/)
  })

  it('rejects case-insensitive duplicates', () => {
    const result = validateAllowedDomains(['Maps.Example.Net', 'maps.example.net'])

    expect(result.valid).toBe(false)
    expect(result.error).toMatch(/more than once/)
  })
})
