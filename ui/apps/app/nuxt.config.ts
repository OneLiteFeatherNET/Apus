// https://nuxt.com/docs/api/configuration/nuxt-config
//
// The Apus tenant application. A pure SPA (design spec §11.2): `ssr: false`, no server-rendered
// routes, no backend-for-frontend session. Auth (layers/core, app/composables/useAuth.ts) is
// therefore a client-only, public OIDC client -- see ui/README.md, "Why no server-side session".
//
// The container runs Nitro's own node-server build; ui/README.md, "Serving the built SPA",
// covers what that means for the output layout, the headers below and runtime config.
export default defineNuxtConfig({
  // Domain/auth/transport, then the design system. Order matters only for overrides: a file in
  // this app at the same path wins over both layers.
  extends: ['@apus/ui-core', '@apus/ui-design'],
  compatibilityDate: '2026-08-09',
  devtools: { enabled: true },
  ssr: false,
  nitro: {
    // Explicit, so CI cannot auto-detect a deploy provider and build an output the
    // Dockerfile's CMD refuses to start.
    preset: 'node-server'
  },
  // Nitro sends no Cache-Control on the shell at all; `/_nuxt/**` repeats Nitro's own
  // immutable because `/**` would otherwise override it. Held by tests/server/nitro.spec.ts.
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
      // Must match `APUS_JWT_ISSUER` on the `api` module -- see that module's
      // application.yml. Which broker sits here is intentionally undecided (design spec §15).
      oidcIssuer: '',
      // The public (no client secret) OIDC client registered for this SPA at the broker. The
      // console shares it; only the redirect URI differs (layers/core, app/utils/oidc.ts).
      oidcClientId: ''
    }
  },
  app: {
    head: {
      title: 'Apus'
    }
  },
  typescript: {
    typeCheck: false
  }
})
