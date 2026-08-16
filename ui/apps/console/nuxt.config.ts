// https://nuxt.com/docs/api/configuration/nuxt-config
//
// The Apus management console. Same shape as the tenant app (SPA, node-server Nitro, the same
// header contract) with one structural difference: it is served under the path prefix /console
// on the *same* origin as the tenant app and the API.
//
// Why the same origin: the api module configures no CORS
// (api/src/main/resources/application.yml has no micronaut.server.cors block), so a console on
// its own hostname would fail every request at the preflight. Same origin also means one
// certificate, one DNS record and one OIDC client -- see the design doc 2026-08-15, §1.2.
export default defineNuxtConfig({
  extends: ['@apus/ui-core', '@apus/ui-design'],
  compatibilityDate: '2026-08-09',
  devtools: { enabled: true },
  ssr: false,
  nitro: {
    // Explicit, so CI cannot auto-detect a deploy provider and build an output the
    // Dockerfile's CMD refuses to start.
    preset: 'node-server'
  },
  app: {
    // The ingress routes /console here without a rewrite, so this app must own the prefix
    // itself: asset URLs, the router and -- via layers/core's app/utils/oidc.ts -- the OIDC
    // redirect URIs all derive from this value.
    baseURL: '/console/',
    head: {
      title: 'Apus Console'
    }
  },
  // Same contract as the tenant app: Nitro sends no Cache-Control on the shell at all, and
  // `/_nuxt/**` has to repeat Nitro's own immutable because `/**` would otherwise override it.
  // The patterns stay prefix-free -- routeRules are matched against the route, which Nuxt
  // resolves relative to baseURL. Held by tests/server/nitro.spec.ts.
  routeRules: {
    '/**': {
      headers: {
        'cache-control': 'no-store',
        'x-content-type-options': 'nosniff'
      }
    },
    '/_nuxt/**': {
      headers: {
        'cache-control': 'public, max-age=31536000, immutable',
        'x-content-type-options': 'nosniff'
      }
    }
  },
  modules: ['@nuxt/eslint'],
  runtimeConfig: {
    public: {
      // Base URL of the `api` module (design spec §11.1). No default -- an empty value would
      // silently point every request at the SPA's own origin.
      apiBaseUrl: '',
      // Must match `APUS_JWT_ISSUER` on the `api` module -- see that module's application.yml.
      oidcIssuer: '',
      // The same public client as the tenant app. Only the redirect URI differs, and the
      // broker must have /console/auth/callback and /console/auth/silent-renew registered
      // alongside the app's -- see ui/README.md, "Two applications, one OIDC client".
      oidcClientId: '',
      // Which scopes to request. Must match the tenant app's: same client, same API, same token.
      // See layers/core, app/composables/useAuth.ts for why this is not hardcoded.
      oidcScope: 'openid profile email'
    }
  },
  typescript: {
    typeCheck: false
  }
})
