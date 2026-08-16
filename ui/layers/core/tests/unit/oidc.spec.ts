import { describe, expect, it } from 'vitest'
import { buildOidcRedirectUris } from '~/utils/oidc'

// The console is served under a path prefix (design doc 2026-08-15, §1.2/§3.3). Building the
// redirect URI from the origin alone would return a signed-in admin to the *tenant app's*
// callback route -- a different application, which would then hold a session nobody asked it
// for, and no error would be raised anywhere.
describe('buildOidcRedirectUris', () => {
  it('uses bare paths when the app is served at the root', () => {
    const uris = buildOidcRedirectUris('https://apus.example.net', '/')

    expect(uris.redirectUri).toBe('https://apus.example.net/auth/callback')
    expect(uris.silentRedirectUri).toBe('https://apus.example.net/auth/silent-renew')
    expect(uris.postLogoutRedirectUri).toBe('https://apus.example.net/')
  })

  it('prefixes every URI with the base path when the app is served under one', () => {
    const uris = buildOidcRedirectUris('https://apus.example.net', '/console/')

    expect(uris.redirectUri).toBe('https://apus.example.net/console/auth/callback')
    expect(uris.silentRedirectUri).toBe('https://apus.example.net/console/auth/silent-renew')
    expect(uris.postLogoutRedirectUri).toBe('https://apus.example.net/console/')
  })

  it('tolerates a base path without a trailing slash', () => {
    // Nuxt normalises app.baseURL to a trailing slash, but a hand-set value may not have one.
    const uris = buildOidcRedirectUris('https://apus.example.net', '/console')

    expect(uris.redirectUri).toBe('https://apus.example.net/console/auth/callback')
    expect(uris.postLogoutRedirectUri).toBe('https://apus.example.net/console/')
  })

  it('tolerates an empty base path', () => {
    const uris = buildOidcRedirectUris('https://apus.example.net', '')

    expect(uris.redirectUri).toBe('https://apus.example.net/auth/callback')
    expect(uris.postLogoutRedirectUri).toBe('https://apus.example.net/')
  })

  it('does not double a slash when the origin carries a trailing one', () => {
    const uris = buildOidcRedirectUris('https://apus.example.net/', '/console/')

    expect(uris.redirectUri).toBe('https://apus.example.net/console/auth/callback')
  })

  it('keeps a nested base path intact', () => {
    // Nothing deploys this way today, but the prefix is a chart value: a future
    // ingress.consolePath of /admin/console must not silently lose a segment.
    const uris = buildOidcRedirectUris('https://apus.example.net', '/admin/console/')

    expect(uris.redirectUri).toBe('https://apus.example.net/admin/console/auth/callback')
    expect(uris.postLogoutRedirectUri).toBe('https://apus.example.net/admin/console/')
  })
})
