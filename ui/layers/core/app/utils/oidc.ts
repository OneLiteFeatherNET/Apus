/**
 * Redirect URIs for the OIDC public client, derived from where the app is actually served.
 *
 * Both applications in this workspace share one broker client but do not share a base path: the
 * tenant app is served at `/`, the management console under `/console/` (design doc
 * 2026-08-15, §1.2 -- same origin, because the api module configures no CORS).
 * `window.location.origin` alone therefore cannot produce a correct callback address, and
 * getting it wrong is not a visible error: the broker would happily redirect a console sign-in
 * into the tenant app, which would come up signed in as if nothing had happened.
 *
 * Kept as a pure function with no Nuxt dependency so it unit-tests without a browser or an app
 * context -- see ui/README.md, "Why plain Vitest".
 */
export interface OidcRedirectUris {
  redirectUri: string
  silentRedirectUri: string
  postLogoutRedirectUri: string
}

/**
 * @param origin an absolute origin, e.g. `window.location.origin`. A trailing slash is tolerated.
 * @param baseUrl the app's base path, i.e. `useRuntimeConfig().app.baseURL`. Nuxt normalises this
 *   to a leading and trailing slash (`/` or `/console/`); leading/trailing slashes are optional
 *   here so a hand-written value cannot produce a doubled or missing separator.
 */
export function buildOidcRedirectUris(origin: string, baseUrl: string): OidcRedirectUris {
  const root = origin.replace(/\/+$/, '')
  const path = baseUrl.replace(/^\/+/, '').replace(/\/+$/, '')
  const prefix = path === '' ? root : `${root}/${path}`

  return {
    redirectUri: `${prefix}/auth/callback`,
    silentRedirectUri: `${prefix}/auth/silent-renew`,
    postLogoutRedirectUri: `${prefix}/`
  }
}
