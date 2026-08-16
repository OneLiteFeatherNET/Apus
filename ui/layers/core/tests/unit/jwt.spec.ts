import { describe, expect, it } from 'vitest'
import { decodeJwtPayload } from '../../app/utils/jwt'

/** Base64url-encodes a claims object into a fake (unsigned) JWT for testing the decoder alone. */
function fakeJwt(claims: Record<string, unknown>): string {
  const header = base64Url(JSON.stringify({ alg: 'none', typ: 'JWT' }))
  const payload = base64Url(JSON.stringify(claims))
  return `${header}.${payload}.signature`
}

function base64Url(json: string): string {
  const bytes = new TextEncoder().encode(json)
  const binary = Array.from(bytes, (byte) => String.fromCharCode(byte)).join('')
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '')
}

describe('decodeJwtPayload', () => {
  it('decodes claims from a well-formed token', () => {
    const token = fakeJwt({ sub: 'user-1', organization: 'friends-server', roles: ['tenant-owner'] })

    expect(decodeJwtPayload(token)).toEqual({
      sub: 'user-1',
      organization: 'friends-server',
      roles: ['tenant-owner']
    })
  })

  it('decodes non-ASCII claim values correctly', () => {
    const token = fakeJwt({ name: 'Jörg Müller' })

    expect(decodeJwtPayload(token)).toEqual({ name: 'Jörg Müller' })
  })

  it('decodes an unpadded base64url payload (no trailing =)', () => {
    // A payload whose base64 length is not a multiple of 4 requires the decoder to re-pad it.
    const token = fakeJwt({ a: 1 })
    expect(token.split('.')[1].length % 4).not.toBe(0)

    expect(decodeJwtPayload(token)).toEqual({ a: 1 })
  })

  it('rejects a string with fewer than two segments', () => {
    expect(() => decodeJwtPayload('not-a-jwt')).toThrow(/at least a header and payload/)
  })

  it('rejects a payload that is not a JSON object', () => {
    const notAnObject = `${base64Url('{}')}.${base64Url('[1,2,3]')}.sig`

    expect(() => decodeJwtPayload(notAnObject)).toThrow(/not a JSON object/)
  })
})
