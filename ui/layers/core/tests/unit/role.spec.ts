import { describe, expect, it } from 'vitest'
import {
  canReadTenant,
  canWriteTenant,
  isPlatformAdmin,
  isRole,
  parsePrincipal,
  type ApusUiPrincipal
} from '../../app/utils/role'

describe('parsePrincipal', () => {
  it('reads subject, tenant and recognised roles from token claims', () => {
    const principal = parsePrincipal({
      sub: 'user-1',
      organization: 'friends-server',
      roles: ['tenant-owner', 'tenant-operator']
    })

    expect(principal).toEqual({
      subject: 'user-1',
      tenant: 'friends-server',
      roles: ['tenant-owner', 'tenant-operator']
    })
  })

  it('silently drops roles the broker sends that Apus does not recognise', () => {
    // Mirrors Role.fromClaim on the api module: one unrecognised entry must not reject the
    // whole token, only that entry.
    const principal = parsePrincipal({ sub: 'user-1', roles: ['tenant-viewer', 'some-future-role'] })

    expect(principal.roles).toEqual(['tenant-viewer'])
  })

  it('normalises role casing and whitespace the same way Role.fromClaim does', () => {
    const principal = parsePrincipal({ sub: 'user-1', roles: [' Tenant-Owner ', 'PLATFORM-ADMIN'] })

    expect(principal.roles).toEqual(['tenant-owner', 'platform-admin'])
  })

  it('maps a missing organization claim to null, never a default tenant', () => {
    const principal = parsePrincipal({ sub: 'user-1' })

    expect(principal.tenant).toBeNull()
  })

  it('maps a blank organization claim to null', () => {
    // Mirrors ApusPrincipal's compact constructor: blank -> null, not an empty-string tenant.
    const principal = parsePrincipal({ sub: 'user-1', organization: '   ' })

    expect(principal.tenant).toBeNull()
  })

  it('maps a missing subject claim to null rather than throwing', () => {
    const principal = parsePrincipal({ organization: 'friends-server' })

    expect(principal.subject).toBeNull()
  })

  it('ignores a non-array roles claim instead of throwing', () => {
    const principal = parsePrincipal({ sub: 'user-1', roles: 'tenant-owner' })

    expect(principal.roles).toEqual([])
  })
})

describe('isRole', () => {
  it('accepts exactly the four spec roles', () => {
    expect(isRole('platform-admin')).toBe(true)
    expect(isRole('tenant-owner')).toBe(true)
    expect(isRole('tenant-operator')).toBe(true)
    expect(isRole('tenant-viewer')).toBe(true)
  })

  it('rejects near-miss spellings (no separator tolerance)', () => {
    expect(isRole('platform_admin')).toBe(false)
    expect(isRole('admin')).toBe(false)
    expect(isRole('')).toBe(false)
  })
})

function principalWith(roles: ApusUiPrincipal['roles']): ApusUiPrincipal {
  return { subject: 'user-1', tenant: 'friends-server', roles }
}

describe('isPlatformAdmin', () => {
  it('is true only for platform-admin', () => {
    expect(isPlatformAdmin(principalWith(['platform-admin']))).toBe(true)
    expect(isPlatformAdmin(principalWith(['tenant-owner']))).toBe(false)
  })

  it('is false for null/undefined principal (unauthenticated)', () => {
    expect(isPlatformAdmin(null)).toBe(false)
    expect(isPlatformAdmin(undefined)).toBe(false)
  })
})

describe('canWriteTenant', () => {
  it('is true for tenant-owner and tenant-operator', () => {
    expect(canWriteTenant(principalWith(['tenant-owner']))).toBe(true)
    expect(canWriteTenant(principalWith(['tenant-operator']))).toBe(true)
  })

  it('is false for tenant-viewer', () => {
    expect(canWriteTenant(principalWith(['tenant-viewer']))).toBe(false)
  })

  it('excludes platform-admin, mirroring ApusPrincipal.canWrite() on the api module', () => {
    // A platform-admin's write access is to platform resources (tenants, quotas), not to a
    // tenant's own sources/maps/renders -- see ApusPrincipal.canWrite()'s Javadoc.
    expect(canWriteTenant(principalWith(['platform-admin']))).toBe(false)
  })

  it('is false without a principal', () => {
    expect(canWriteTenant(null)).toBe(false)
  })
})

describe('canReadTenant', () => {
  it('is true for any of the three tenant roles', () => {
    expect(canReadTenant(principalWith(['tenant-owner']))).toBe(true)
    expect(canReadTenant(principalWith(['tenant-operator']))).toBe(true)
    expect(canReadTenant(principalWith(['tenant-viewer']))).toBe(true)
  })

  it('is false for platform-admin alone, mirroring TenantAccess.canRead()', () => {
    // A narrow-scope caller with no tenant role fails this gate even with a tenant claim
    // present -- see TenantAccess's Javadoc on service tokens for the same rule server-side.
    expect(canReadTenant(principalWith(['platform-admin']))).toBe(false)
  })

  it('is false with no roles at all', () => {
    expect(canReadTenant(principalWith([]))).toBe(false)
  })
})
